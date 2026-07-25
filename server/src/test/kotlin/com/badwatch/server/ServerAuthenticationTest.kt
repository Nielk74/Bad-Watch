package com.badwatch.server

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerAuthenticationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun tokenProtectsEveryDataReadButLeavesShellAndHealthPublic() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("sessions"))
        val export = SyntheticSessions.session(5_000L, rallies = 4, shotsPerRally = 7)
        repository.save(export)
        val captures = CaptureRepository(temporaryFolder.newFolder("captures"))

        testApplication {
            application { badWatchModule(repository, token = "s3cret", captureRepository = captures) }

            assertThat(client.get("/").status).isEqualTo(HttpStatusCode.OK)
            assertThat(client.get("/").bodyAsText()).contains("Bad Watch")
            assertThat(client.get("/api/v1/health").status).isEqualTo(HttpStatusCode.OK)

            val protectedPaths = listOf(
                "/api/v1/sessions",
                "/api/v1/sessions/${export.session.id}",
                "/api/v1/dashboard",
                "/api/v1/captures",
                "/api/v1/captures/summary",
                "/api/v1/captures/evaluation"
            )

            protectedPaths.forEach { path ->
                assertWithMessage("$path without a bearer token")
                    .that(client.get(path).status)
                    .isEqualTo(HttpStatusCode.Unauthorized)
                assertWithMessage("$path with the configured bearer token")
                    .that(client.get(path) {
                        header(HttpHeaders.Authorization, "Bearer s3cret")
                    }.status)
                    .isEqualTo(HttpStatusCode.OK)
            }

            // A credential without the Bearer scheme is not an Authorization header.
            assertThat(
                client.get("/api/v1/dashboard") {
                    header(HttpHeaders.Authorization, "s3cret")
                }.status
            ).isEqualTo(HttpStatusCode.Unauthorized)
        }
    }

    @Test
    fun noTokenKeepsLocalDevelopmentReadsFrictionless() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("open-sessions"))

        testApplication {
            application { badWatchModule(repository, token = null) }

            assertThat(client.get("/api/v1/sessions").status).isEqualTo(HttpStatusCode.OK)
            assertThat(client.get("/api/v1/dashboard").status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun dataApisDoNotOptInToCrossOriginBrowserReads() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("cors-sessions"))

        testApplication {
            application { badWatchModule(repository, token = "s3cret") }

            val response = client.get("/api/v1/dashboard") {
                header(HttpHeaders.Authorization, "Bearer s3cret")
                header(HttpHeaders.Origin, "https://attacker.example")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin]).isNull()
        }
    }
}
