package com.badwatch.app.sync

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class DashboardClientTest {
    private var server: ServerSocket? = null
    private var worker: Thread? = null

    @After
    fun stopServer() {
        server?.close()
        worker?.join(1_000L)
    }

    @Test
    fun authenticatedCompatibleStatusPasses() = runTest {
        val baseUrl = serve { authorization ->
            if (authorization == "Bearer court-secret") {
                200 to """{"status":"ok","schemaVersion":1,"sessionCount":4,"consentedCaptureCount":0}"""
            } else {
                401 to """{"error":"Invalid token"}"""
            }
        }

        val result = DashboardClient().checkConnection(baseUrl, "court-secret")

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun badTokenAndIncompatibleSchemaFailClearly() = runTest {
        val baseUrl = serve { authorization ->
            if (authorization == "Bearer expected") {
                200 to """{"status":"ok","schemaVersion":99,"sessionCount":0,"consentedCaptureCount":0}"""
            } else {
                401 to """{"error":"Invalid token"}"""
            }
        }
        val client = DashboardClient()

        val unauthorised = client.checkConnection(baseUrl, "wrong")
        val incompatible = client.checkConnection(baseUrl, "expected")

        assertThat(unauthorised.exceptionOrNull()?.message).contains("HTTP 401")
        assertThat(incompatible.exceptionOrNull()?.message).contains("incompatible")
    }

    @Test
    fun rejectsNonHttpUrlsBeforeOpeningThem() = runTest {
        val result = DashboardClient().checkConnection("file:///tmp/bad-watch", null)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("https:// or http://")
    }

    private fun serve(response: (String?) -> Pair<Int, String>): String {
        val instance = ServerSocket()
        instance.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val thread = Thread {
            try {
                while (!instance.isClosed) {
                    instance.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        require(requestLine.startsWith("GET /api/v1/status "))
                        var authorization: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", ignoreCase = true)) {
                                authorization = line.substringAfter(':').trim()
                            }
                        }
                        val (status, body) = response(authorization)
                        val bytes = body.encodeToByteArray()
                        val reason = if (status == 200) "OK" else "Unauthorized"
                        socket.getOutputStream().bufferedWriter().use { writer ->
                            writer.write("HTTP/1.1 $status $reason\r\n")
                            writer.write("Content-Type: application/json\r\n")
                            writer.write("Content-Length: ${bytes.size}\r\n")
                            writer.write("Connection: close\r\n\r\n")
                            writer.write(body)
                        }
                    }
                }
            } catch (_: SocketException) {
                // Expected when @After closes the listening socket.
            }
        }
        thread.isDaemon = true
        thread.start()
        server = instance
        worker = thread
        return "http://127.0.0.1:${instance.localPort}"
    }
}
