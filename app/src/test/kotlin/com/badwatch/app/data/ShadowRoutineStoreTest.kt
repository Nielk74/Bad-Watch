package com.badwatch.app.data

import com.badwatch.core.training.ShadowStatus
import com.badwatch.core.training.ShadowTrainer
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShadowRoutineStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savedRoutineSurvivesAFreshStore() {
        val file = File(temporaryFolder.newFolder("shadow"), "active.json")
        val document = ShadowRoutineDocument(
            state = ShadowTrainer.start(seed = 7L, targetRepetitions = 12, nowMillis = 1_000L),
            updatedAtMillis = 1_000L
        )

        ShadowRoutineStore(file).save(document)

        val loaded = ShadowRoutineStore(file).load() as ShadowRoutineLoadResult.Loaded
        assertThat(loaded.document).isEqualTo(document)
        assertThat(loaded.document.state.status).isEqualTo(ShadowStatus.Active)
    }

    @Test
    fun corruptRoutineIsReportedRatherThanSilentlyDiscarded() {
        val file = File(temporaryFolder.newFolder("corrupt-shadow"), "active.json")
        file.writeText("not valid json")

        assertThat(ShadowRoutineStore(file).load())
            .isInstanceOf(ShadowRoutineLoadResult.Corrupt::class.java)
    }

    @Test
    fun clearRemovesMainAndInterruptedTemporaryFile() {
        val directory = temporaryFolder.newFolder("clear-shadow")
        val file = File(directory, "active.json")
        val temporary = File(directory, "active.json.tmp")
        val store = ShadowRoutineStore(file)
        store.save(
            ShadowRoutineDocument(
                state = ShadowTrainer.start(1L, 6, 0L),
                updatedAtMillis = 0L
            )
        )
        temporary.writeText("interrupted")

        store.clear()

        assertThat(file.exists()).isFalse()
        assertThat(temporary.exists()).isFalse()
        assertThat(store.load()).isEqualTo(ShadowRoutineLoadResult.Empty)
    }
}
