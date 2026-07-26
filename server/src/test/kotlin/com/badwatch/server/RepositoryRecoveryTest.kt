package com.badwatch.server

import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CaptureDataUse
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureProtocol
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.SessionExport
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RepositoryRecoveryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sessionValidTempRecoversOverInvalidMainAndPreservesEveryCorruptCopy() {
        val directory = temporaryFolder.newFolder("session-invalid-main")
        val export = SyntheticSessions.session(101_000L, rallies = 2, shotsPerRally = 3)
        val destination = sessionFile(directory, export)
        val temporary = File(directory, "${destination.name}.tmp")
        val earlierEvidence = File(directory, "${destination.name}.quarantine-invalid")
        destination.writeText("first-invalid-main")
        earlierEvidence.writeText("earlier-invalid-main")
        temporary.writeExport(export)

        val recovered = SessionRepository(directory).find(export.session.id)

        assertThat(recovered).isEqualTo(export)
        assertThat(destination.readText()).isEqualTo(encoded(export))
        assertThat(temporary.exists()).isFalse()
        assertThat(earlierEvidence.readText()).isEqualTo("earlier-invalid-main")
        assertThat(File(directory, "${destination.name}.quarantine-invalid.1").readText())
            .isEqualTo("first-invalid-main")
    }

    @Test
    fun captureValidTempRecoversOverInvalidMainAndPreservesCorruptBytes() {
        val directory = temporaryFolder.newFolder("capture-invalid-main")
        val export = captureExport("capture-invalid-main", startedAtMillis = 201_000L)
        val destination = captureFile(directory, export)
        val temporary = File(directory, "${destination.name}.tmp")
        destination.writeText("invalid-capture-main")
        temporary.writeExport(export)

        val recovered = CaptureRepository(directory).find(export.capture.id)

        assertThat(recovered).isEqualTo(export)
        assertThat(destination.readText()).isEqualTo(encoded(export))
        assertThat(temporary.exists()).isFalse()
        assertThat(File(directory, "${destination.name}.quarantine-invalid").readText())
            .isEqualTo("invalid-capture-main")
    }

    @Test
    fun sessionOrphanTempBecomesTheCanonicalRecord() {
        val directory = temporaryFolder.newFolder("session-orphan")
        val export = SyntheticSessions.session(301_000L, rallies = 3, shotsPerRally = 2)
        val destination = sessionFile(directory, export)
        val temporary = File(directory, "${destination.name}.tmp")
        temporary.writeExport(export)

        assertThat(SessionRepository(directory).all()).containsExactly(export)
        assertThat(destination.exists()).isTrue()
        assertThat(temporary.exists()).isFalse()
    }

    @Test
    fun captureOrphanTempBecomesTheCanonicalRecord() {
        val directory = temporaryFolder.newFolder("capture-orphan")
        val export = captureExport("capture-orphan", startedAtMillis = 401_000L)
        val destination = captureFile(directory, export)
        val temporary = File(directory, "${destination.name}.tmp")
        temporary.writeExport(export)

        assertThat(CaptureRepository(directory).all()).containsExactly(export)
        assertThat(destination.exists()).isTrue()
        assertThat(temporary.exists()).isFalse()
    }

    @Test
    fun sessionValidMainWinsAndConflictingTempIsPreserved() {
        val directory = temporaryFolder.newFolder("session-conflict")
        val stored = SyntheticSessions.session(501_000L, rallies = 3, shotsPerRally = 4)
        val conflicting = stored.copy(
            report = PostSessionReport(notes = "uncommitted alternate diary")
        )
        val destination = sessionFile(directory, stored)
        val temporary = File(directory, "${destination.name}.tmp")
        destination.writeExport(stored)
        temporary.writeExport(conflicting)

        assertThat(SessionRepository(directory).find(stored.session.id)).isEqualTo(stored)
        assertThat(destination.readExport<SessionExport>()).isEqualTo(stored)
        assertThat(
            File(directory, "${temporary.name}.quarantine-conflict")
                .readExport<SessionExport>()
        ).isEqualTo(conflicting)
    }

    @Test
    fun captureValidMainWinsAndConflictingTempCannotChangeAnImmutableId() {
        val directory = temporaryFolder.newFolder("capture-conflict")
        val stored = captureExport("immutable-capture", startedAtMillis = 601_000L)
        val conflicting = stored.copy(
            capture = stored.capture.copy(endedAtMillis = stored.capture.endedAtMillis + 500L)
        )
        val destination = captureFile(directory, stored)
        val temporary = File(directory, "${destination.name}.tmp")
        val earlierEvidence = File(directory, "${temporary.name}.quarantine-conflict")
        destination.writeExport(stored)
        temporary.writeExport(conflicting)
        earlierEvidence.writeText("earlier-conflict")

        assertThat(CaptureRepository(directory).find(stored.capture.id)).isEqualTo(stored)
        assertThat(destination.readExport<CaptureExport>()).isEqualTo(stored)
        assertThat(earlierEvidence.readText()).isEqualTo("earlier-conflict")
        assertThat(
            File(directory, "${temporary.name}.quarantine-conflict.1")
                .readExport<CaptureExport>()
        ).isEqualTo(conflicting)
    }

    @Test
    fun invalidTempBesideValidCaptureIsQuarantinedWithoutReplacingTheMain() {
        val directory = temporaryFolder.newFolder("capture-invalid-temp")
        val stored = captureExport("capture-invalid-temp", startedAtMillis = 701_000L)
        val destination = captureFile(directory, stored)
        val temporary = File(directory, "${destination.name}.tmp")
        destination.writeExport(stored)
        temporary.writeText("truncated-temp")

        assertThat(CaptureRepository(directory).all()).containsExactly(stored)
        assertThat(destination.readExport<CaptureExport>()).isEqualTo(stored)
        assertThat(File(directory, "${temporary.name}.quarantine-invalid").readText())
            .isEqualTo("truncated-temp")
    }

    @Test
    fun unreadableJsonEntryPropagatesIoFailureWithoutQuarantiningIt() {
        val directory = temporaryFolder.newFolder("unreadable-entry")
        val unreadable = File(directory, "unreadable.json")
        assertThat(unreadable.mkdir()).isTrue()

        val failure = runCatching { SessionRepository(directory).all() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(unreadable.isDirectory).isTrue()
        assertThat(directory.listFiles().orEmpty().map(File::getName))
            .containsExactly("unreadable.json")
    }

    private fun captureExport(id: String, startedAtMillis: Long): CaptureExport = CaptureExport(
        deviceId = "recovery-test-device",
        participantId = "recovery-test-player",
        appVersion = "test",
        profile = PlayerProfile(),
        capture = CaptureSession(
            id = id,
            startedAtMillis = startedAtMillis,
            endedAtMillis = startedAtMillis + 1_000L,
            label = ShotType.Smash,
            swings = emptyList()
        ),
        samplingRateHz = 100,
        dataUse = CaptureDataUse.SelfHostedModelTraining,
        protocol = CaptureProtocol()
    )

    private fun sessionFile(directory: File, export: SessionExport): File =
        File(directory, "${export.session.id}.json")

    private fun captureFile(directory: File, export: CaptureExport): File = File(
        directory,
        "${export.capture.startedAtMillis}-${export.capture.label.name}-${export.capture.id}.json"
    )

    private fun File.writeExport(export: SessionExport) = writeText(encoded(export))

    private fun File.writeExport(export: CaptureExport) = writeText(encoded(export))

    private fun encoded(export: SessionExport): String = BadWatchJson.encodeToString(
        SessionExport.serializer(),
        export
    )

    private fun encoded(export: CaptureExport): String = BadWatchJson.encodeToString(
        CaptureExport.serializer(),
        export
    )

    private inline fun <reified T> File.readExport(): T = when (T::class) {
        SessionExport::class -> BadWatchJson.decodeFromString(
            SessionExport.serializer(),
            readText()
        ) as T
        CaptureExport::class -> BadWatchJson.decodeFromString(
            CaptureExport.serializer(),
            readText()
        ) as T
        else -> error("Unsupported recovery test payload ${T::class}")
    }
}
