package com.badwatch.server

import com.badwatch.core.sync.CaptureEnvelope
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.sync.SyncEnvelope
import com.badwatch.core.sync.SyncResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Bad Watch dashboard server.
 *
 * Deliberately a single small self-hostable process: it accepts session uploads from the
 * watch and serves the dashboard that reads them back. There is no account system — the
 * watch identifies itself with an install id, and an optional shared token is the only
 * access control, which is the right weight for something you run for yourself or a club.
 *
 * Configuration:
 *   `BADWATCH_HOST`      bind address (default 127.0.0.1; use 0.0.0.0 for watch/LAN access)
 *   `BADWATCH_PORT`      listen port (default 8080)
 *   `BADWATCH_DATA_DIR`  where sessions are written (default ./badwatch-data)
 *   `BADWATCH_TOKEN`     shared bearer token; required for non-loopback binding
 */
fun main() {
    val host = System.getenv("BADWATCH_HOST")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_HOST
    val port = System.getenv("BADWATCH_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    require(port in 1..65_535) { "BADWATCH_PORT must be between 1 and 65535" }
    val dataDir = File(System.getenv("BADWATCH_DATA_DIR") ?: "badwatch-data")
    val token = System.getenv("BADWATCH_TOKEN")?.takeIf { it.isNotBlank() }

    requireSafeServerBinding(host = host, token = token)
    println("[bad-watch] Storing sessions in ${dataDir.absolutePath}")
    println("[bad-watch] Dashboard listening on http://$host:$port/")
    if (token == null) println("[bad-watch] Loopback-only development mode; data APIs have no token")

    embeddedServer(Netty, host = host, port = port) {
        badWatchModule(
            repository = SessionRepository(dataDir),
            token = token,
            captureRepository = CaptureRepository(File(dataDir, "captures"))
        )
    }.start(wait = true)
}

const val DEFAULT_HOST = "127.0.0.1"
const val DEFAULT_PORT = 8080

internal fun String.isLoopbackBindAddress(): Boolean =
    this == "127.0.0.1" || this == "::1" || equals("localhost", ignoreCase = true)

internal fun requireSafeServerBinding(host: String, token: String?) {
    require(token != null || host.isLoopbackBindAddress()) {
        "BADWATCH_TOKEN is required when BADWATCH_HOST is not a loopback address"
    }
}

/** Unknown properties are rejected on the deliberately narrow diary mutation contract. */
private val diaryUpdateJson = Json { ignoreUnknownKeys = false }

fun Application.badWatchModule(
    repository: SessionRepository,
    token: String?,
    captureRepository: CaptureRepository = CaptureRepository(java.io.File("badwatch-data/captures"))
) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<StoredRecordValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(cause.message ?: "Stored record conflicts with this payload")
            )
        }
        exception<StoredRecordConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "Edit conflict"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled failure", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal error")
            )
        }
    }

    routing {
        staticResources("/", "static") { default("index.html") }

        // Keep liveness public so a reverse proxy can check the process without holding
        // the user's data token. Every endpoint that exposes or mutates data is guarded.
        get("/api/v1/health") {
            call.respond(HealthResponse(schemaVersion = SessionExport.SCHEMA_VERSION))
        }

        /** Small authenticated handshake used by the release watch configuration screen. */
        get("/api/v1/status") {
            if (call.rejectIfUnauthorised(token)) return@get
            call.respond(
                DashboardStatusResponse(
                    schemaVersion = SessionExport.SCHEMA_VERSION,
                    sessionCount = repository.all().size,
                    consentedCaptureCount = captureRepository.trainingEligible().size
                )
            )
        }

        /** Upload endpoint the watch's SyncWorker posts to. */
        post("/api/v1/sessions") {
            if (call.rejectIfUnauthorised(token)) return@post

            val envelope = try {
                call.receive<SyncEnvelope>()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid session sync JSON"))
                return@post
            }
            if (envelope.schemaVersion != SessionExport.SCHEMA_VERSION) {
                call.respond(
                    SyncResponse(
                        rejected = envelope.sessions.associate { export ->
                            export.session.id to
                                "Unsupported envelope schema ${envelope.schemaVersion}; " +
                                "this server speaks ${SessionExport.SCHEMA_VERSION}"
                        }
                    )
                )
                return@post
            }

            val accepted = mutableListOf<String>()
            val rejected = mutableMapOf<String, String>()
            envelope.sessions.forEach { export ->
                val validationErrors = DataPortability.sessionValidationErrors(export)
                if (validationErrors.isNotEmpty()) {
                    rejected[export.session.id] = validationErrors.joinToString("; ")
                    return@forEach
                }
                try {
                    repository.upsert(export)
                    accepted += export.session.id
                } catch (error: StoredRecordValidationException) {
                    // Permanent: identical bytes can never satisfy this record invariant.
                    rejected[export.session.id] = error.message ?: "Invalid session record"
                } catch (error: StoredRecordConflictException) {
                    // Also deterministic for this exact append-only history. Rejecting only the
                    // conflicting id lets independent records in the batch finish normally.
                    rejected[export.session.id] = error.message ?: "Conflicting session history"
                }
            }
            call.respond(SyncResponse(accepted = accepted, rejected = rejected))
        }

        get("/api/v1/sessions") {
            if (call.rejectIfUnauthorised(token)) return@get
            call.respond(repository.all())
        }

        /** Labelled training data from a capture drill. */
        post("/api/v1/captures") {
            if (call.rejectIfUnauthorised(token)) return@post

            val envelope = try {
                call.receive<CaptureEnvelope>()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid capture sync JSON"))
                return@post
            }
            if (envelope.schemaVersion != SessionExport.SCHEMA_VERSION) {
                call.respond(
                    SyncResponse(
                        rejected = envelope.captures.associate { export ->
                            export.capture.id to
                                "Unsupported envelope schema ${envelope.schemaVersion}; " +
                                "this server speaks ${SessionExport.SCHEMA_VERSION}"
                        }
                    )
                )
                return@post
            }

            val accepted = mutableListOf<String>()
            val rejected = mutableMapOf<String, String>()
            envelope.captures.forEach { export ->
                val validationErrors = DataPortability.captureValidationErrors(export)
                if (validationErrors.isNotEmpty()) {
                    rejected[export.capture.id] = validationErrors.joinToString("; ")
                    return@forEach
                }
                try {
                    captureRepository.upsert(export)
                    accepted += export.capture.id
                } catch (error: StoredRecordValidationException) {
                    rejected[export.capture.id] = error.message ?: "Invalid capture record"
                }
            }
            call.respond(SyncResponse(accepted = accepted, rejected = rejected))
        }

        /** Full labelled corpus, used by tools/ingest.py to build a training dataset. */
        get("/api/v1/captures") {
            if (call.rejectIfUnauthorised(token)) return@get
            call.respond(captureRepository.trainingEligible())
        }

        /** Dataset progress: how many labelled swings exist, per stroke. */
        get("/api/v1/captures/summary") {
            if (call.rejectIfUnauthorised(token)) return@get
            call.respond(captureRepository.summary())
        }

        /** How well the shipped rule-based classifier does on the collected ground truth. */
        get("/api/v1/captures/evaluation") {
            if (call.rejectIfUnauthorised(token)) return@get
            call.respond(captureRepository.evaluateClassifier())
        }

        get("/api/v1/dashboard") {
            if (call.rejectIfUnauthorised(token)) return@get
            val filter = runCatching { call.dashboardFilter() }.getOrElse { error ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error.message ?: "Invalid dashboard filter")
                )
                return@get
            }
            call.respond(Analytics.build(repository.all(), filter))
        }

        /** Lossless owner backup. No export timestamp: identical stored data yields bytes. */
        get("/api/v1/export/archive") {
            if (call.rejectIfUnauthorised(token)) return@get
            val archive = DataPortability.archive(
                repository.all(),
                captureRepository.trainingEligible()
            )
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"badwatch-archive.json\""
            )
            call.respondText(
                DataPortability.encodeArchive(archive),
                contentType = ContentType.Application.Json
            )
        }

        /** Human-readable diary view; the JSON archive above is the restore format. */
        get("/api/v1/export/sessions.csv") {
            if (call.rejectIfUnauthorised(token)) return@get
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"badwatch-sessions.csv\""
            )
            call.respondText(
                SessionCsvExporter.encode(repository.all()),
                contentType = ContentType.parse("text/csv; charset=utf-8")
            )
        }

        /** Validates the complete archive before merge/upsert begins. */
        post("/api/v1/import/archive") {
            if (call.rejectIfUnauthorised(token)) return@post
            val archive = try {
                call.receive<BadWatchArchive>()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid archive JSON"))
                return@post
            }
            val errors = DataPortability.validationErrors(archive)
            if (errors.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(errors.joinToString("; ")))
                return@post
            }
            call.respond(DataPortability.restore(archive, repository, captureRepository))
        }

        /** Reviewed values for the browser, alongside the immutable raw export for audit. */
        get("/api/v1/sessions/{id}/detail") {
            if (call.rejectIfUnauthorised(token)) return@get
            val id = call.parameters["id"]
            val session = id?.let { repository.find(it) }
            if (session == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("No such session"))
            } else {
                call.respond(Analytics.detail(session, repository.all()))
            }
        }

        /** Lossless raw export retained for API compatibility and owner audit. */
        get("/api/v1/sessions/{id}") {
            if (call.rejectIfUnauthorised(token)) return@get
            val id = call.parameters["id"]
            val session = id?.let { repository.find(it) }
            if (session == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("No such session"))
            } else {
                call.respond(session)
            }
        }

        /** Replaces only the player-reported diary envelope, never raw detector output. */
        put("/api/v1/sessions/{id}/diary") {
            if (call.rejectIfUnauthorised(token)) return@put
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("No such session"))
                return@put
            }
            val request = try {
                diaryUpdateJson.decodeFromString(
                    SessionDiaryUpdateRequest.serializer(),
                    call.receiveText()
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid diary update JSON"))
                return@put
            }
            val updated = try {
                repository.updateDiary(id, request.baseDiaryRevision, request::applyTo)
            } catch (error: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error.message ?: "Invalid diary update")
                )
                return@put
            }
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("No such session"))
            } else {
                call.respond(updated)
            }
        }

        delete("/api/v1/sessions/{id}") {
            if (call.rejectIfUnauthorised(token)) return@delete
            val id = call.parameters["id"]
            val removed = id != null && repository.delete(id)
            call.respond(DeleteResponse(deleted = removed))
        }
    }
}

/**
 * Response bodies are explicit types rather than ad-hoc maps: kotlinx.serialization cannot
 * serialize a heterogeneous `Map<String, Any>`, and the failure only shows up at runtime.
 */
@kotlinx.serialization.Serializable
data class HealthResponse(val status: String = "ok", val schemaVersion: Int)

@kotlinx.serialization.Serializable
data class DashboardStatusResponse(
    val status: String = "ok",
    val schemaVersion: Int,
    val sessionCount: Int,
    val consentedCaptureCount: Int
)

@kotlinx.serialization.Serializable
data class DeleteResponse(val deleted: Boolean)

@kotlinx.serialization.Serializable
data class ErrorResponse(val error: String)

private suspend fun io.ktor.server.application.ApplicationCall.rejectIfUnauthorised(
    token: String?
): Boolean {
    // Session responses are personal health/training records. They must not become a
    // reusable browser/proxy cache entry, even on a trusted self-hosted network.
    response.header(HttpHeaders.CacheControl, "no-store")
    if (isAuthorised(token)) return false
    respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
    return true
}

private fun io.ktor.server.application.ApplicationCall.isAuthorised(token: String?): Boolean {
    if (token == null) return true
    val header = request.header(io.ktor.http.HttpHeaders.Authorization) ?: return false
    val separator = header.indexOf(' ')
    if (separator <= 0 || !header.substring(0, separator).equals("Bearer", ignoreCase = true)) {
        return false
    }
    val supplied = header.substring(separator + 1).trim()
    if (supplied.isEmpty()) return false
    return MessageDigest.isEqual(supplied.encodeToByteArray(), token.encodeToByteArray())
}

/**
 * Optional repeatable query parameters for server-side diary filtering. Enum values are
 * case-insensitive and may also be comma-separated for lightweight clients.
 */
private fun io.ktor.server.application.ApplicationCall.dashboardFilter(): SessionAnalyticsFilter {
    val query = request.queryParameters
    return SessionAnalyticsFilter(
        activityModes = query.enumSet<ActivityMode>("activityMode"),
        comparisonTag = query["comparisonTag"],
        completions = query.enumSet<SessionCompletion>("completion"),
        recordingQualities = query.enumSet<RecordingQuality>("recordingQuality")
    )
}

private inline fun <reified T : Enum<T>> Parameters.enumSet(name: String): Set<T> =
    getAll(name)
        .orEmpty()
        .flatMap { value -> value.split(',') }
        .map { value ->
            val candidate = value.trim()
            enumValues<T>().firstOrNull { it.name.equals(candidate, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown $name '$candidate'; expected " +
                        enumValues<T>().joinToString { it.name }
                )
        }
        .toSet()
