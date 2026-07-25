package com.badwatch.server

import com.badwatch.core.sync.CaptureEnvelope
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SyncEnvelope
import com.badwatch.core.sync.SyncResponse
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
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.request.header
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Bad Watch dashboard server.
 *
 * Deliberately a single small self-hostable process: it accepts session uploads from the
 * watch and serves the dashboard that reads them back. There is no account system — the
 * watch identifies itself with an install id, and an optional shared token is the only
 * access control, which is the right weight for something you run for yourself or a club.
 *
 * Configuration, all optional:
 *   `BADWATCH_PORT`      listen port (default 8080)
 *   `BADWATCH_DATA_DIR`  where sessions are written (default ./badwatch-data)
 *   `BADWATCH_TOKEN`     shared bearer token; when unset, uploads are unauthenticated
 */
fun main() {
    val port = System.getenv("BADWATCH_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val dataDir = File(System.getenv("BADWATCH_DATA_DIR") ?: "badwatch-data")
    val token = System.getenv("BADWATCH_TOKEN")?.takeIf { it.isNotBlank() }

    if (token == null) {
        println(
            "[bad-watch] No BADWATCH_TOKEN set — uploads are unauthenticated. " +
                "Set one before exposing this server beyond localhost."
        )
    }
    println("[bad-watch] Storing sessions in ${dataDir.absolutePath}")
    println("[bad-watch] Dashboard: http://localhost:$port/")

    embeddedServer(Netty, port = port) {
        badWatchModule(
            repository = SessionRepository(dataDir),
            token = token,
            captureRepository = CaptureRepository(File(dataDir, "captures"))
        )
    }.start(wait = true)
}

const val DEFAULT_PORT = 8080

fun Application.badWatchModule(
    repository: SessionRepository,
    token: String?,
    captureRepository: CaptureRepository = CaptureRepository(java.io.File("badwatch-data/captures"))
) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
    }
    install(CallLogging)
    install(CORS) {
        // The dashboard is served from this same origin; CORS exists only so a phone
        // browser on the LAN, or a separate front end during development, can read the API.
        anyHost()
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
        allowHeader(io.ktor.http.HttpHeaders.Authorization)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }
    install(StatusPages) {
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

        get("/api/v1/health") {
            call.respond(HealthResponse(schemaVersion = SessionExport.SCHEMA_VERSION))
        }

        /** Upload endpoint the watch's SyncWorker posts to. */
        post("/api/v1/sessions") {
            if (!call.isAuthorised(token)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                return@post
            }

            val envelope = call.receive<SyncEnvelope>()
            if (envelope.schemaVersion != SessionExport.SCHEMA_VERSION) {
                // Refuse rather than guess: a mismatched schema silently misread is far
                // worse than an upload the watch will retry after an app update.
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "Unsupported schema version ${envelope.schemaVersion}; " +
                            "this server speaks ${SessionExport.SCHEMA_VERSION}"
                    )
                )
                return@post
            }

            val accepted = mutableListOf<String>()
            val rejected = mutableMapOf<String, String>()
            envelope.sessions.forEach { export ->
                runCatching { repository.save(export) }
                    .onSuccess { accepted += export.session.id }
                    .onFailure { rejected[export.session.id] = it.message ?: "Could not store session" }
            }
            call.respond(SyncResponse(accepted = accepted, rejected = rejected))
        }

        get("/api/v1/sessions") {
            call.respond(repository.all())
        }

        /** Labelled training data from a capture drill. */
        post("/api/v1/captures") {
            if (!call.isAuthorised(token)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                return@post
            }

            val envelope = call.receive<CaptureEnvelope>()
            if (envelope.schemaVersion != SessionExport.SCHEMA_VERSION) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "Unsupported schema version ${envelope.schemaVersion}; " +
                            "this server speaks ${SessionExport.SCHEMA_VERSION}"
                    )
                )
                return@post
            }

            val accepted = mutableListOf<String>()
            val rejected = mutableMapOf<String, String>()
            envelope.captures.forEach { export ->
                runCatching { captureRepository.save(export) }
                    .onSuccess { accepted += export.capture.id }
                    .onFailure { rejected[export.capture.id] = it.message ?: "Could not store capture" }
            }
            call.respond(SyncResponse(accepted = accepted, rejected = rejected))
        }

        /** Dataset progress: how many labelled swings exist, per stroke. */
        get("/api/v1/captures/summary") {
            call.respond(captureRepository.summary())
        }

        /** How well the shipped rule-based classifier does on the collected ground truth. */
        get("/api/v1/captures/evaluation") {
            call.respond(captureRepository.evaluateClassifier())
        }

        get("/api/v1/dashboard") {
            call.respond(Analytics.build(repository.all()))
        }

        get("/api/v1/sessions/{id}") {
            val id = call.parameters["id"]
            val session = id?.let { repository.find(it) }
            if (session == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("No such session"))
            } else {
                call.respond(session)
            }
        }

        delete("/api/v1/sessions/{id}") {
            if (!call.isAuthorised(token)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                return@delete
            }
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
data class DeleteResponse(val deleted: Boolean)

@kotlinx.serialization.Serializable
data class ErrorResponse(val error: String)

private fun io.ktor.server.application.ApplicationCall.isAuthorised(token: String?): Boolean {
    if (token == null) return true
    val header = request.header(io.ktor.http.HttpHeaders.Authorization) ?: return false
    return header.removePrefix("Bearer ").trim() == token
}
