package com.badwatch.app

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class MainActivityLaunchContractTest {

    @Test
    fun tileStartRequestIsConsumedBeforeStartingAndCannotReplay() {
        var pendingTileRequest = true
        var starts = 0
        val transitions = mutableListOf<String>()

        val firstHandled = consumeTileStartSessionRequest(
            hasRequest = pendingTileRequest,
            consumeRequest = {
                transitions += "consume"
                pendingTileRequest = false
            },
            startSession = {
                transitions += "start"
                starts++
            }
        )
        val replayHandled = consumeTileStartSessionRequest(
            hasRequest = pendingTileRequest,
            consumeRequest = { throw AssertionError("consumed request replayed") },
            startSession = { starts++ }
        )

        assertThat(firstHandled).isTrue()
        assertThat(replayHandled).isFalse()
        assertThat(transitions).containsExactly("consume", "start").inOrder()
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun mainActivityUsesSingleTopSoExistingTaskReceivesTilePayload() {
        val manifest = manifestFile()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val activities = document.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) }
            .first { node ->
                node.attributes
                    .getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue == ".MainActivity"
            }

        assertThat(
            mainActivity.attributes
                .getNamedItemNS(ANDROID_NAMESPACE, "launchMode")
                ?.nodeValue
        ).isEqualTo("singleTop")
    }

    private fun manifestFile(): File = listOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml")
    ).firstOrNull(File::isFile)
        ?: error("Could not locate app/src/main/AndroidManifest.xml from ${File(".").absolutePath}")

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
