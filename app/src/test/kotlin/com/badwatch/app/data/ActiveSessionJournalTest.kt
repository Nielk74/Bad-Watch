package com.badwatch.app.data

import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.session.SessionRecorder
import com.badwatch.core.session.SessionRecorderCheckpoint
import com.badwatch.core.sync.BadWatchJson
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActiveSessionJournalTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun atomicallySavedEntrySurvivesAFreshJournal() = runTest {
        val file = journalFile("valid")
        val entry = entry()

        assertThat(ActiveSessionJournal(file).save(entry)).isTrue()

        assertThat(ActiveSessionJournal(file).load()).isEqualTo(entry)
        assertThat(File(file.parentFile, "${file.name}.tmp").exists()).isFalse()
    }

    @Test
    fun fullyWrittenOrphanTempIsRecovered() = runTest {
        val file = journalFile("orphan")
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(
            BadWatchJson.encodeToString(ActiveSessionJournalEntry.serializer(), entry())
        )

        val loaded = ActiveSessionJournal(file).load()

        assertThat(loaded).isEqualTo(entry())
        assertThat(file.exists()).isTrue()
        assertThat(temporary.exists()).isFalse()
    }

    @Test
    fun corruptJournalIsQuarantinedInsteadOfCrashingEveryRestart() = runTest {
        val file = journalFile("corrupt")
        file.writeText("{ definitely not a checkpoint")

        assertThat(ActiveSessionJournal(file).load()).isNull()
        assertThat(file.exists()).isFalse()
        assertThat(File(file.parentFile, "${file.name}.invalid").exists()).isTrue()
    }

    @Test
    fun unsupportedLegacyEnvelopeIsQuarantined() = runTest {
        val file = journalFile("legacy-envelope")
        file.writeText(
            BadWatchJson.encodeToString(
                ActiveSessionJournalEntry.serializer(),
                entry().copy(schemaVersion = 0)
            )
        )

        assertThat(ActiveSessionJournal(file).load()).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun preEnvelopeCheckpointDocumentIsHandledAsLegacy() = runTest {
        val file = journalFile("legacy-checkpoint")
        file.writeText(
            BadWatchJson.encodeToString(SessionRecorderCheckpoint.serializer(), checkpoint())
        )

        assertThat(ActiveSessionJournal(file).load()).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun clearIsIdempotentAndRemovesMainAndTemp() = runTest {
        val file = journalFile("clear")
        val journal = ActiveSessionJournal(file)
        journal.save(entry())
        File(file.parentFile, "${file.name}.tmp").writeText("interrupted")

        assertThat(journal.clear()).isTrue()
        assertThat(journal.clear()).isTrue()
        assertThat(file.parentFile?.listFiles()?.toList().orEmpty()).isEmpty()
    }

    private fun journalFile(folder: String): File =
        File(temporaryFolder.newFolder(folder), "active.json")

    private fun entry(): ActiveSessionJournalEntry = ActiveSessionJournalEntry(
        checkpoint = checkpoint(),
        deviceId = "device-1",
        appVersion = "test",
        updatedAtMillis = 2_000L
    )

    private fun checkpoint(): SessionRecorderCheckpoint {
        val recorder = SessionRecorder(
            profile = PlayerProfile(),
            sessionId = "session-1"
        )
        recorder.start(1_000L)
        return recorder.checkpoint()!!
    }
}
