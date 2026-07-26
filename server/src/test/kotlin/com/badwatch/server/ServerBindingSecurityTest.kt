package com.badwatch.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerBindingSecurityTest {

    @Test
    fun unauthenticatedServerIsAllowedOnlyOnLoopback() {
        listOf("127.0.0.1", "::1", "localhost", "LOCALHOST").forEach { host ->
            requireSafeServerBinding(host = host, token = null)
        }

        val failure = runCatching {
            requireSafeServerBinding(host = "0.0.0.0", token = null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("BADWATCH_TOKEN")
    }

    @Test
    fun tokenAllowsLanBinding() {
        requireSafeServerBinding(host = "0.0.0.0", token = "a-long-random-secret")
        requireSafeServerBinding(host = "192.168.1.20", token = "a-long-random-secret")
    }
}
