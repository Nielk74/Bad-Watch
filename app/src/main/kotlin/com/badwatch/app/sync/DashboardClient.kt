package com.badwatch.app.sync

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CaptureEnvelope
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SyncEnvelope
import com.badwatch.core.sync.SyncResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads sessions to the self-hosted dashboard.
 *
 * Deliberately built on `HttpURLConnection` rather than pulling OkHttp/Ktor onto the watch:
 * the entire client is one POST of a JSON body. Keeping the watch APK small matters more
 * here than ergonomics, and there is no streaming, no auth dance and no retry logic to get
 * wrong — WorkManager owns retries.
 */
class DashboardClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000
) {

    /** Authenticated, read-only setup handshake used by the on-watch configuration UI. */
    suspend fun checkConnection(baseUrl: String, token: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = endpoint(baseUrl, STATUS_PATH)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMillis
                    readTimeout = readTimeoutMillis
                    // Never carry the user's bearer token through an HTTP redirect to a
                    // different origin. Dashboard setup must name the canonical endpoint.
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    token?.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("Authorization", "Bearer $it")
                    }
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw IOException("Dashboard connection failed: HTTP $code")
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val status = BadWatchJson.parseToJsonElement(body).jsonObject
                    require(status["status"]?.jsonPrimitive?.content == "ok") {
                        "Dashboard returned an invalid status"
                    }
                    require(status["schemaVersion"]?.jsonPrimitive?.int == SessionExport.SCHEMA_VERSION) {
                        "Dashboard uses an incompatible data schema"
                    }
                    Unit
                } finally {
                    connection.disconnect()
                }
            }
        }

    /**
     * @param baseUrl Root of the dashboard server, e.g. `https://badwatch.example.com`.
     * @param token Optional bearer token; the server rejects uploads when it is configured
     *   with a token and the request omits or mismatches it.
     */
    suspend fun upload(
        baseUrl: String,
        token: String?,
        sessions: List<SessionExport>
    ): Result<SyncResponse> {
        if (sessions.isEmpty()) return Result.success(SyncResponse())
        return post(
            baseUrl = baseUrl,
            path = SESSIONS_PATH,
            token = token,
            body = BadWatchJson.encodeToString(
                SyncEnvelope.serializer(),
                SyncEnvelope(sessions = sessions)
            )
        )
    }

    /**
     * Uploads labelled training data. Separate from sessions because capture payloads carry
     * raw sample windows and are orders of magnitude larger — batching them together would
     * make a single failure retry the whole lot.
     */
    suspend fun uploadCaptures(
        baseUrl: String,
        token: String?,
        captures: List<CaptureExport>
    ): Result<SyncResponse> {
        if (captures.isEmpty()) return Result.success(SyncResponse())
        return post(
            baseUrl = baseUrl,
            path = CAPTURES_PATH,
            token = token,
            body = BadWatchJson.encodeToString(
                CaptureEnvelope.serializer(),
                CaptureEnvelope(captures = captures)
            )
        )
    }

    private suspend fun post(
        baseUrl: String,
        path: String,
        token: String?,
        body: String
    ): Result<SyncResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val url = endpoint(baseUrl, path)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                token?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }

            try {
                connection.outputStream.bufferedWriter().use { it.write(body) }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    throw IOException("Dashboard rejected upload: HTTP $code ${detail.orEmpty()}")
                }
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                BadWatchJson.decodeFromString(SyncResponse.serializer(), responseBody)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun endpoint(baseUrl: String, path: String): URL {
        val url = URL(baseUrl.trim().trimEnd('/') + path)
        require(url.protocol == "https" || url.protocol == "http") {
            "Dashboard URL must use https:// or http://"
        }
        require(url.userInfo.isNullOrBlank()) { "Put the token in its own field" }
        return url
    }

    private companion object {
        const val SESSIONS_PATH = "/api/v1/sessions"
        const val CAPTURES_PATH = "/api/v1/captures"
        const val STATUS_PATH = "/api/v1/status"
    }
}
