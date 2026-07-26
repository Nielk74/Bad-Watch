package com.badwatch.app.data

import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.CaptureDataUse
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureProtocol
import com.badwatch.core.sync.SyncResponse
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectionPersistsAndAcceptanceSupersedesItWithoutChangingPayload() = runTest {
        val directory = temporaryFolder.newFolder("captures")
        val store = CaptureStore(directory)
        val saved = store.save(export("capture-1", startedAtMillis = 1_000L))
        val payloadBefore = saved.file.readText()
        val uploaded = store.unsynced()

        store.applySyncResponse(
            uploaded,
            SyncResponse(rejected = mapOf("capture-1" to "Consent metadata rejected"))
        )

        val rejected = CaptureStore(directory).refresh().single()
        assertThat(rejected.synced).isFalse()
        assertThat(rejected.rejected).isTrue()
        assertThat(rejected.syncRejection?.reason).isEqualTo("Consent metadata rejected")
        assertThat(CaptureStore(directory).unsynced()).isEmpty()
        assertThat(saved.file.readText()).isEqualTo(payloadBefore)

        store.markSynced(listOf("capture-1"))
        val accepted = CaptureStore(directory).refresh().single()
        assertThat(accepted.synced).isTrue()
        assertThat(accepted.rejected).isFalse()
        assertThat(accepted.syncRejection).isNull()
    }

    @Test
    fun fullyWrittenTempIsRecoveredAfterWriterFailsBeforeMove() = runTest {
        val directory = temporaryFolder.newFolder("interrupted-capture")
        val interrupted = CaptureStore(directory) { file, text ->
            File(file.parentFile, "${file.name}.tmp").writeText(text)
            throw IOException("simulated crash before move")
        }
        val export = export("capture-recover", startedAtMillis = 2_000L)

        val failure = runCatching { interrupted.save(export) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(directory.listFiles { file -> file.extension == "json" }).isEmpty()
        assertThat(directory.listFiles { file -> file.name.endsWith(".json.tmp") })
            .hasLength(1)

        val recovered = CaptureStore(directory).refresh()
        assertThat(recovered.map { it.export }).containsExactly(export)
        assertThat(directory.listFiles { file -> file.name.endsWith(".json.tmp") })
            .isEmpty()
    }

    @Test
    fun corruptOrphanTempIsQuarantinedInsteadOfEnteringTheDataset() = runTest {
        val directory = temporaryFolder.newFolder("corrupt-temp")
        File(directory, "1000-Smash-broken.json.tmp").writeText("{not capture json")

        val loaded = CaptureStore(directory).refresh()

        assertThat(loaded).isEmpty()
        assertThat(directory.listFiles { file -> file.name.endsWith(".invalid") })
            .hasLength(1)
    }

    @Test
    fun corruptMainCaptureIsQuarantinedWithoutOverwritingEarlierEvidence() = runTest {
        val directory = temporaryFolder.newFolder("corrupt-main-capture")
        val existingQuarantine = File(directory, "broken.json.invalid").apply {
            writeText("older corrupt payload")
        }
        val corrupt = File(directory, "broken.json").apply {
            writeText("{not capture json")
        }

        val loaded = CaptureStore(directory).refresh()

        assertThat(loaded).isEmpty()
        assertThat(corrupt.exists()).isFalse()
        assertThat(existingQuarantine.readText()).isEqualTo("older corrupt payload")
        assertThat(directory.listFiles { file -> file.name.startsWith("broken.json.invalid") })
            .hasLength(2)
    }

    private fun export(id: String, startedAtMillis: Long): CaptureExport = CaptureExport(
        deviceId = "device",
        participantId = "participant",
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
}
