package com.badwatch.app.data

import com.badwatch.core.match.BadmintonMatchTimeline
import com.badwatch.core.match.MatchLog
import com.badwatch.core.match.MatchSide
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MatchStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savedActionLogSurvivesAFreshStore() {
        val file = File(temporaryFolder.newFolder("match"), "active.json")
        val log = BadmintonMatchTimeline.awardPoint(
            MatchLog(id = "match-1", startedAtMillis = 1_000L),
            side = MatchSide.Player,
            atMillis = 2_000L
        )

        MatchStore(file).save(log)

        val loaded = MatchStore(file).load() as MatchLoadResult.Loaded
        assertThat(loaded.log).isEqualTo(log)
        assertThat(BadmintonMatchTimeline.replay(loaded.log).playerPoints).isEqualTo(1)
    }

    @Test
    fun corruptDocumentIsReportedRatherThanTreatedAsNoMatch() {
        val file = File(temporaryFolder.newFolder("corrupt"), "active.json")
        file.writeText("not json")

        val result = MatchStore(file).load()

        assertThat(result).isInstanceOf(MatchLoadResult.Corrupt::class.java)
    }

    @Test
    fun clearRemovesSavedAndTemporaryDocuments() {
        val directory = temporaryFolder.newFolder("clear")
        val file = File(directory, "active.json")
        val temporary = File(directory, "active.json.tmp")
        MatchStore(file).save(MatchLog(id = "match-2", startedAtMillis = 5_000L))
        temporary.writeText("interrupted write")

        MatchStore(file).clear()

        assertThat(file.exists()).isFalse()
        assertThat(temporary.exists()).isFalse()
        assertThat(MatchStore(file).load()).isEqualTo(MatchLoadResult.Empty)
    }
}
