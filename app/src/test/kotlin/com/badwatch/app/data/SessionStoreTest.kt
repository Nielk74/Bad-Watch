package com.badwatch.app.data

import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.sync.SessionExport
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SessionStore] touches no Android APIs, so the real persistence behaviour — atomic writes,
 * sync markers, ordering, corruption tolerance — is testable as a plain JVM test.
 */
class SessionStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savedSessionsSurviveAFreshStore() = runTest {
        val directory = temporaryFolder.newFolder("sessions")
        val export = export(startedAtMillis = 1_000L, shots = 12)

        SessionStore(directory).save(export)

        val reopened = SessionStore(directory).refresh()
        assertThat(reopened).hasSize(1)
        assertThat(reopened.single().export.session.id).isEqualTo(export.session.id)
        assertThat(reopened.single().export.session.summary.totalShots).isEqualTo(12)
    }

    @Test
    fun sessionsAreListedNewestFirst() = runTest {
        val store = SessionStore(temporaryFolder.newFolder("sessions"))
        store.save(export(startedAtMillis = 1_000L, shots = 1))
        store.save(export(startedAtMillis = 9_000L, shots = 2))
        store.save(export(startedAtMillis = 5_000L, shots = 3))

        val listed = store.refresh()

        assertThat(listed.map { it.export.session.startedAtMillis })
            .containsExactly(9_000L, 5_000L, 1_000L)
            .inOrder()
    }

    @Test
    fun markingSyncedMovesSessionsOutOfThePendingSet() = runTest {
        val store = SessionStore(temporaryFolder.newFolder("sessions"))
        val first = export(startedAtMillis = 1_000L, shots = 4)
        val second = export(startedAtMillis = 2_000L, shots = 5)
        store.save(first)
        store.save(second)

        assertThat(store.unsynced()).hasSize(2)

        store.markSynced(listOf(first.session.id))

        val pending = store.unsynced()
        assertThat(pending).hasSize(1)
        assertThat(pending.single().export.session.id).isEqualTo(second.session.id)
        assertThat(store.refresh().first { it.export.session.id == first.session.id }.synced).isTrue()
    }

    @Test
    fun syncingDoesNotAlterTheStoredPayload() = runTest {
        val directory = temporaryFolder.newFolder("sessions")
        val store = SessionStore(directory)
        val saved = store.save(export(startedAtMillis = 1_000L, shots = 6))
        val before = saved.file.readText()

        store.markSynced(listOf(saved.export.session.id))

        // Sync state lives in a sibling marker file, so re-uploading is byte-identical.
        assertThat(saved.file.readText()).isEqualTo(before)
    }

    @Test
    fun aCorruptFileIsSkippedRatherThanBreakingHistory() = runTest {
        val directory = temporaryFolder.newFolder("sessions")
        val store = SessionStore(directory)
        store.save(export(startedAtMillis = 1_000L, shots = 3))
        File(directory, "9999-broken.json").writeText("{ this is not valid json")

        val listed = store.refresh()

        assertThat(listed).hasSize(1)
    }

    @Test
    fun deletingRemovesTheSessionAndItsMarker() = runTest {
        val directory = temporaryFolder.newFolder("sessions")
        val store = SessionStore(directory)
        val stored = store.save(export(startedAtMillis = 1_000L, shots = 3))
        store.markSynced(listOf(stored.export.session.id))

        store.delete(stored.export.session.id)

        assertThat(store.refresh()).isEmpty()
        assertThat(directory.listFiles()?.toList().orEmpty()).isEmpty()
    }

    private fun export(startedAtMillis: Long, shots: Int): SessionExport {
        val shotEvents = (0 until shots).map { index ->
            ShotEvent(
                id = "shot-$startedAtMillis-$index",
                type = ShotType.Smash,
                timestampMillis = startedAtMillis + index * 900L,
                confidence = 0.8f,
                peakAngularVelocity = 6.4f,
                heartRateBpm = 150f,
                swingDurationMillis = 240L
            )
        }
        return SessionExport(
            deviceId = "test-device",
            appVersion = "test",
            profile = PlayerProfile(),
            session = TrainingSession(
                id = "session-$startedAtMillis",
                startedAtMillis = startedAtMillis,
                endedAtMillis = startedAtMillis + 60_000L,
                summary = TrainingSummary(
                    totalShots = shots,
                    shotCounts = mapOf(ShotType.Smash to shots),
                    durationMillis = 60_000L,
                    averageHeartRate = 148f,
                    maxHeartRate = 172f,
                    recoveryScore = 0.5f,
                    fatigueScore = 0.5f,
                    effortScore = 0.5f,
                    heartRateZoneHistogram = emptyMap()
                ),
                shots = shotEvents
            ),
            rallyProfile = RallyProfile.EMPTY
        )
    }
}
