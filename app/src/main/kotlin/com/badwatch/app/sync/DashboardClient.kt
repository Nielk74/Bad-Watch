package com.badwatch.app.sync

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SyncEnvelope
import com.badwatch.core.sync.SyncResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * @param baseUrl Root of the dashboard server, e.g. `https://badwatch.example.com`.
     * @param token Optional bearer token; the server rejects uploads when it is configured
     *   with a token and the request omits or mismatches it.
     */
    suspend fun upload(
        baseUrl: String,
        token: String?,
        sessions: List<SessionExport>
    ): Result<SyncResponse> = withContext(Dispatchers.IO) {
        if (sessions.isEmpty()) return@withContext Result.success(SyncResponse())

        runCatching {
            val url = URL(baseUrl.trimEnd('/') + SYNC_PATH)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                token?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }

            try {
                val payload = BadWatchJson.encodeToString(
                    SyncEnvelope.serializer(),
                    SyncEnvelope(sessions = sessions)
                )
                connection.outputStream.bufferedWriter().use { it.write(payload) }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    throw IOException("Dashboard rejected upload: HTTP $code ${detail.orEmpty()}")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                BadWatchJson.decodeFromString(SyncResponse.serializer(), body)
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val SYNC_PATH = "/api/v1/sessions"
    }
}
