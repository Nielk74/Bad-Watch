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

            val shell = client.get("/")
            assertThat(shell.status).isEqualTo(HttpStatusCode.OK)
            val shellBody = shell.bodyAsText()
            assertThat(shellBody).contains("Bad Watch")
            assertThat(shellBody).contains("Same-ID sessions merge only")
            assertThat(shellBody).doesNotContain("same id are replaced")
            assertThat(client.get("/api/v1/health").status).isEqualTo(HttpStatusCode.OK)

            val protectedPaths = listOf(
                "/api/v1/sessions",
                "/api/v1/sessions/${export.session.id}",
                "/api/v1/dashboard",
                "/api/v1/status",
                "/api/v1/captures",
                "/api/v1/captures/summary",
                "/api/v1/captures/evaluation",
                "/api/v1/export/archive",
                "/api/v1/export/sessions.csv"
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
            assertThat(client.get("/api/v1/status").status).isEqualTo(HttpStatusCode.OK)
            assertThat(client.get("/api/v1/status").bodyAsText()).contains("\"schemaVersion\":1")
        }
    }

    @Test
    fun dashboardQualifiesAutomaticStrokeFamiliesButNotPlayerLabels() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("label-truth-sessions"))

        testApplication {
            application { badWatchModule(repository, token = null) }

            val shell = client.get("/").bodyAsText()
            assertThat(shell).contains(
                "const provisionalShotLabel = type => `${'$'}{shotLabel(type)} · provisional`;"
            )
            assertThat(shell).contains("label.textContent = provisionalShotLabel(s.type);")
            assertThat(shell).contains("escapeHtml(provisionalShotLabel(s.type))")
            assertThat(shell).contains("escapeHtml(provisionalShotLabel(t))")
            assertThat(shell).doesNotContain("SHOT_LABELS[s.type]")

            // Capture drills and accuracy support are labels the player chose, not automatic
            // session classifications, so qualifying those would misstate their provenance.
            assertThat(shell).contains(
                "`${'$'}{shotLabel(l.label)}: ${'$'}{l.swings}`"
            )
            assertThat(shell).contains("<td>${'$'}{shotLabel(c.label)}</td>")
        }
    }

    @Test
    fun dashboardShellWiresAccessibleFiltersToEveryAggregateRefresh() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("filter-shell-sessions"))

        testApplication {
            application { badWatchModule(repository, token = null) }

            val shell = client.get("/").bodyAsText()

            assertThat(shell).contains("id=\"dashboardFilterForm\"")
            assertThat(shell).contains("id=\"filterStatus\" role=\"status\" aria-live=\"polite\"")
            assertThat(shell).contains("params.set(\"activityMode\", filterActivity.value)")
            assertThat(shell).contains("params.set(\"completion\", filterCompletion.value)")
            assertThat(shell).contains("params.set(\"recordingQuality\", filterQuality.value)")
            assertThat(shell).contains("params.set(\"comparisonTag\", comparisonTag)")
            assertThat(shell).contains(
                "const ALL_RECORDING_QUALITIES = \"Unreviewed,Complete,Partial,Unusable\""
            )
            assertThat(shell).contains("hydrateDashboardFilters(new URLSearchParams(window.location.search))")
            assertThat(shell).contains("writeDashboardFilterUrl(dashboardSearchParams())")
            assertThat(shell).contains("setDashboardFiltersVisible(id === null)")
            assertThat(shell).contains("No sessions match these filters")
            assertThat(shell).doesNotContain("apiJson(\"api/v1/dashboard\")")
        }
    }

    @Test
    fun dashboardShellKeepsRecoveryGapsVisibleIndependentOfDiaryQuality() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("recovery-shell-sessions"))

        testApplication {
            application { badWatchModule(repository, token = null) }

            val shell = client.get("/").bodyAsText()

            assertThat(shell).contains("processAbsenceCoverageHtml(s)")
            assertThat(shell).contains("Known unobserved time")
            assertThat(shell).contains("reviewed.processAbsenceCount")
            assertThat(shell).contains("reviewed.unobservedMillis")
            assertThat(shell).contains("rawSession.processAbsenceGaps")
            assertThat(shell).contains("Known unobserved interval")
            assertThat(shell).contains("Diary quality does not change this immutable recovery boundary")
            assertThat(shell).contains("Shaded bands are known unobserved time")
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
            assertThat(response.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        }
    }
}
