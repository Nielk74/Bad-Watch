package com.badwatch.app.data

import com.badwatch.app.domain.revisedDiary
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SyncResponse
import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

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
    fun syncingNormalizesDiaryLineageThenRemainsByteStable() = runTest {
        val directory = temporaryFolder.newFolder("sessions")
        val store = SessionStore(directory)
        val saved = store.save(export(startedAtMillis = 1_000L, shots = 6))
        val before = saved.file.readText()

        store.markSynced(listOf(saved.export.session.id))

        val normalizedBytes = saved.file.readText()
        val normalized = BadWatchJson.decodeFromString(
            SessionExport.serializer(),
            normalizedBytes
        )
        assertThat(normalizedBytes).isNotEqualTo(before)
        assertThat(normalized.diaryRevision).isEqualTo(0L)
        assertThat(normalized.diaryBaseRevision).isEqualTo(0L)

        store.markSynced(listOf(saved.export.session.id))
        assertThat(saved.file.readText()).isEqualTo(normalizedBytes)
    }

    @Test
    fun legacyTimestampOnlySyncedMarkerStillLoadsAsAccepted() = runTest {
        val directory = temporaryFolder.newFolder("legacy-sync-marker")
        val saved = SessionStore(directory).save(export(startedAtMillis = 1_000L, shots = 2))
        acceptedMarkerFor(saved.file).writeText("1700000000000")

        val reopened = SessionStore(directory).refresh().single()

        assertThat(reopened.synced).isTrue()
        assertThat(reopened.syncRejection).isNull()
        assertThat(SessionStore(directory).unsynced()).isEmpty()
    }

    @Test
    fun rejectionPersistsWithReasonAndLaterAcceptanceSupersedesIt() = runTest {
        val directory = temporaryFolder.newFolder("rejected")
        val store = SessionStore(directory)
        val saved = store.save(export(startedAtMillis = 1_000L, shots = 6))
        val payloadBefore = saved.file.readText()
        val uploaded = store.unsynced()

        store.applySyncResponse(
            uploaded = uploaded,
            response = SyncResponse(
                rejected = mapOf(saved.export.session.id to "Unsupported diary value")
            )
        )

        val rejected = SessionStore(directory).refresh().single()
        assertThat(rejected.synced).isFalse()
        assertThat(rejected.rejected).isTrue()
        assertThat(rejected.syncRejection?.reason).isEqualTo("Unsupported diary value")
        assertThat(rejected.syncRejection?.recordedAtMillis).isGreaterThan(0L)
        assertThat(SessionStore(directory).unsynced()).isEmpty()
        assertThat(saved.file.readText()).isEqualTo(payloadBefore)

        // An eventual acceptance is authoritative and keeps the legacy UI boolean intact.
        store.markSynced(listOf(saved.export.session.id))
        val accepted = SessionStore(directory).refresh().single()
        assertThat(accepted.synced).isTrue()
        assertThat(accepted.rejected).isFalse()
        assertThat(accepted.syncRejection).isNull()
    }

    @Test
    fun changedSessionClearsRejectionAndIgnoresStaleInFlightResponse() = runTest {
        val directory = temporaryFolder.newFolder("rejected-edit")
        val store = SessionStore(directory)
        val original = export(startedAtMillis = 4_000L, shots = 7)
        store.save(original)
        val firstUpload = store.unsynced()
        store.applySyncResponse(
            firstUpload,
            SyncResponse(rejected = mapOf(original.session.id to "Review required"))
        )

        // Reapplying the identical envelope is not an edit and remains quarantined.
        assertThat(store.update(original).rejected).isTrue()
        assertThat(store.unsynced()).isEmpty()

        val revised = original.copy(notes = mapOf("review" to "fixed"))
        val updated = store.update(revised)
        assertThat(updated.synced).isFalse()
        assertThat(updated.syncRejection).isNull()
        assertThat(store.unsynced().single().export).isEqualTo(revised)

        // The response for the old bytes arrived after the edit; it cannot quarantine new data.
        store.applySyncResponse(
            firstUpload,
            SyncResponse(rejected = mapOf(original.session.id to "Old payload rejected"))
        )
        val current = store.refresh().single()
        assertThat(current.export).isEqualTo(revised)
        assertThat(current.syncRejection).isNull()
        assertThat(store.unsynced()).hasSize(1)

        val revisedUpload = store.unsynced()
        store.applySyncResponse(
            revisedUpload,
            SyncResponse(accepted = listOf(original.session.id))
        )
        val acceptedRevision = store.refresh().single()
        assertThat(acceptedRevision.synced).isTrue()
        assertThat(acceptedRevision.syncRejection).isNull()
    }

    @Test
    fun corruptMainPayloadIsUniquelyQuarantinedRatherThanSilentlySkipped() = runTest {
        val directory = temporaryFolder.newFolder("sessions")
        val store = SessionStore(directory)
        store.save(export(startedAtMillis = 1_000L, shots = 3))
        val existingQuarantine = File(directory, "9999-broken.json.invalid").apply {
            writeText("preserve earlier evidence")
        }
        val corrupt = File(directory, "9999-broken.json").apply {
            writeText("{ this is not valid json")
        }

        val listed = store.refresh()

        assertThat(listed).hasSize(1)
        assertThat(corrupt.exists()).isFalse()
        assertThat(existingQuarantine.readText()).isEqualTo("preserve earlier evidence")
        assertThat(directory.listFiles { file -> file.name.startsWith("9999-broken.json.invalid") })
            .hasLength(2)
    }

    @Test
    fun fullyWrittenSessionTempIsRecoveredAfterWriterFailsBeforeMove() = runTest {
        val directory = temporaryFolder.newFolder("interrupted-session")
        val interrupted = SessionStore(
            directory = directory,
            atomicWriter = { destination, text ->
                File(destination.parentFile, "${destination.name}.tmp").writeText(text)
                throw IOException("simulated crash before move")
            }
        )
        val export = export(startedAtMillis = 2_000L, shots = 4)

        val failure = runCatching { interrupted.save(export) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(directory.listFiles { file -> file.name.endsWith(".json.tmp") })
            .hasLength(1)

        val recovered = SessionStore(directory).refresh()
        assertThat(recovered.map { it.export }).containsExactly(export)
        assertThat(directory.listFiles { file -> file.name.endsWith(".json.tmp") }).isEmpty()
    }

    @Test
    fun invalidSessionTempIsQuarantined() = runTest {
        val directory = temporaryFolder.newFolder("invalid-session-temp")
        val temporary = File(directory, "1000-broken.json.tmp").apply {
            writeText("{not session json")
        }

        val loaded = SessionStore(directory).refresh()

        assertThat(loaded).isEmpty()
        assertThat(temporary.exists()).isFalse()
        assertThat(directory.listFiles { file -> file.name.contains(".tmp.invalid") })
            .hasLength(1)
    }

    @Test
    fun validOrphanNeverOverwritesAnExistingGoodSession() = runTest {
        val directory = temporaryFolder.newFolder("session-temp-conflict")
        val store = SessionStore(directory)
        val original = export(startedAtMillis = 3_000L, shots = 2)
        val saved = store.save(original)
        val conflicting = original.copy(appVersion = "conflicting-temp")
        val temporary = File(directory, "${saved.file.name}.tmp").apply {
            writeText(BadWatchJson.encodeToString(SessionExport.serializer(), conflicting))
        }
        val originalBytes = saved.file.readText()

        val loaded = store.refresh()

        assertThat(loaded.single().export).isEqualTo(original)
        assertThat(saved.file.readText()).isEqualTo(originalBytes)
        assertThat(temporary.exists()).isFalse()
        assertThat(directory.listFiles { file -> file.name.contains(".tmp.conflict") })
            .hasLength(1)
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

    @Test
    fun savingTheSameSessionTwiceIsIdempotent() = runTest {
        val directory = temporaryFolder.newFolder("idempotent")
        val store = SessionStore(directory)
        val export = export(startedAtMillis = 4_000L, shots = 7)

        val first = store.save(export)
        val second = store.save(export.copy(appVersion = "must-not-overwrite"))

        assertThat(second.export).isEqualTo(first.export)
        assertThat(store.refresh()).hasSize(1)
        assertThat(directory.listFiles { file -> file.extension == "json" }?.size).isEqualTo(1)
    }

    @Test
    fun explicitUpdateAtomicallyReplacesAndMakesSessionPendingAgain() = runTest {
        val directory = temporaryFolder.newFolder("update")
        var changeNotifications = 0
        val store = SessionStore(
            directory = directory,
            onSessionsChanged = { changeNotifications++ }
        )
        val original = export(startedAtMillis = 4_000L, shots = 7)
        val saved = store.save(original)
        store.markSynced(listOf(original.session.id))
        val acknowledged = store.refresh().single().export

        // Ordinary save remains immutable and does not report a change.
        val idempotent = store.save(original.copy(appVersion = "ignored-save"))
        assertThat(idempotent.export).isEqualTo(acknowledged)
        assertThat(changeNotifications).isEqualTo(1)

        val revised = acknowledged.copy(
            appVersion = "reviewed",
            notes = mapOf("review" to "kept")
        )
        val updated = store.update(revised)

        assertThat(updated.file).isEqualTo(saved.file)
        assertThat(updated.export).isEqualTo(revised)
        assertThat(updated.synced).isFalse()
        assertThat(File(directory, "${saved.file.name}.synced").exists()).isFalse()
        assertThat(SessionStore(directory).refresh().single().export).isEqualTo(revised)
        assertThat(changeNotifications).isEqualTo(2)

        // Reapplying the exact revision is a no-op, just like a duplicate command retry.
        assertThat(store.update(revised).export).isEqualTo(revised)
        assertThat(changeNotifications).isEqualTo(2)
    }

    @Test
    fun serverSyncedDiaryAndCorrectionMutationsComposeAgainstLatestEnvelope() = runTest {
        val directory = temporaryFolder.newFolder("serialized-review")
        val store = SessionStore(directory)
        val original = export(startedAtMillis = 7_000L, shots = 3).copy(diaryRevision = 12L)
        store.save(original)
        store.markSynced(listOf(original.session.id))
        val reviewedContext = SessionContext(
            activityMode = com.badwatch.core.sync.ActivityMode.DoublesMatch
        )
        val correction = HitCorrectionRevision(
            falseHitIds = listOf(original.session.shots.first().id),
            provenance = CorrectionProvenance(
                revisionId = "watch-review",
                actor = CorrectionActor.Player,
                recordedAtMillis = 8_000L
            )
        )

        val diarySave = async {
            store.mutateReview(original.session.id) { latest ->
                latest.revisedDiary(reviewedContext, latest.report)
            }
        }
        val correctionSave = async {
            store.mutateReview(original.session.id) { latest ->
                latest.copy(
                    corrections = SessionCorrections(
                        hitRevisions = latest.corrections.hitRevisions + correction,
                        trimRevisions = latest.corrections.trimRevisions
                    )
                )
            }
        }
        diarySave.await()
        correctionSave.await()

        val persisted = store.refresh().single()
        assertThat(persisted.export.context).isEqualTo(reviewedContext)
        assertThat(persisted.export.corrections.hitRevisions).containsExactly(correction)
        assertThat(persisted.export.diaryRevision).isEqualTo(13L)
        assertThat(persisted.export.diaryBaseRevision).isEqualTo(12L)
        assertThat(persisted.synced).isFalse()
        assertThat(store.unsynced()).hasSize(1)
    }

    @Test
    fun multipleOfflineDiaryEditsKeepOriginalBaseUntilAccepted() = runTest {
        val store = SessionStore(temporaryFolder.newFolder("offline-lineage"))
        val original = export(startedAtMillis = 9_000L, shots = 2).copy(
            diaryRevision = 4L
        )
        store.save(original)
        store.markSynced(listOf(original.session.id))

        store.mutateReview(original.session.id) { latest ->
            latest.revisedDiary(
                latest.context.copy(
                    activityMode = com.badwatch.core.sync.ActivityMode.DoublesMatch
                ),
                latest.report
            )
        }
        store.mutateReview(original.session.id) { latest ->
            latest.revisedDiary(
                latest.context,
                latest.report.copy(rpe = 8)
            )
        }

        val offlineHead = store.unsynced().single()
        assertThat(offlineHead.export.diaryRevision).isEqualTo(6L)
        assertThat(offlineHead.export.diaryBaseRevision).isEqualTo(4L)

        store.applySyncResponse(
            uploaded = listOf(offlineHead),
            response = SyncResponse(accepted = listOf(original.session.id))
        )

        val accepted = store.refresh().single()
        assertThat(accepted.synced).isTrue()
        assertThat(accepted.export.diaryRevision).isEqualTo(6L)
        assertThat(accepted.export.diaryBaseRevision).isEqualTo(6L)
    }

    @Test
    fun exhaustedDiaryRevisionFailsWithoutChangingDurablePayload() = runTest {
        val store = SessionStore(temporaryFolder.newFolder("revision-overflow"))
        val original = export(startedAtMillis = 11_000L, shots = 1).copy(
            diaryRevision = Long.MAX_VALUE
        )
        store.save(original)

        val failure = runCatching {
            store.mutateReview(original.session.id) { latest ->
                latest.revisedDiary(
                    latest.context.copy(
                        activityMode = com.badwatch.core.sync.ActivityMode.FreePlay
                    ),
                    latest.report
                )
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(store.refresh().single().export).isEqualTo(original)
    }

    @Test
    fun changeCallbackFiresOnlyForActualCorpusMutationsAndCannotBreakStorage() = runTest {
        val directory = temporaryFolder.newFolder("callback")
        var changeNotifications = 0
        val store = SessionStore(
            directory = directory,
            onSessionsChanged = {
                changeNotifications++
                error("platform callback unavailable")
            }
        )
        val export = export(startedAtMillis = 8_000L, shots = 2)

        store.refresh()
        store.delete("missing")
        store.clear()
        assertThat(changeNotifications).isEqualTo(0)

        store.save(export)
        store.markSynced(listOf(export.session.id))
        store.refresh()
        assertThat(changeNotifications).isEqualTo(1)
        assertThat(store.refresh()).hasSize(1)

        store.delete(export.session.id)
        store.delete(export.session.id)
        assertThat(changeNotifications).isEqualTo(2)
        assertThat(store.refresh()).isEmpty()

        store.save(export)
        store.clear()
        store.clear()
        assertThat(changeNotifications).isEqualTo(4)
        assertThat(store.refresh()).isEmpty()
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
                    recoveryScore = 0f,
                    fatigueScore = 0f,
                    effortScore = 0f,
                    heartRateZoneHistogram = emptyMap()
                ),
                shots = shotEvents
            ),
            rallyProfile = RallyProfile.EMPTY
        )
    }
}
