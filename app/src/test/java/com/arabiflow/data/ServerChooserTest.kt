package com.arabiflow.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServerChooserTest {
    private val first = ServerEndpoint("https://primary.example", "primary-secret")
    private val second = ServerEndpoint("https://backup.example", "backup-secret")

    @Test fun failsOverOnlyBeforeUploadAndKeepsOwnToken() = runBlocking {
        val visited = mutableListOf<ServerEndpoint>()
        val selection = ServerChooser.choose(listOf(first, second)) {
            visited.add(it)
            ProbeResult(it == second, if (it == first) "temporarily down" else "")
        }
        assertEquals(second, selection.endpoint)
        assertEquals(listOf(first, second), visited)
        assertEquals("backup-secret", selection.endpoint?.token)
    }

    @Test fun doesNotScanUnregisteredServer() = runBlocking {
        val selection = ServerChooser.choose(emptyList()) {
            throw AssertionError("unregistered endpoints cannot be contacted")
        }
        assertNull(selection.endpoint)
    }

    @Test fun rejectsInsecureOriginsWithoutSendingTokens() = runBlocking {
        val visited = mutableListOf<ServerEndpoint>()
        val selection = ServerChooser.choose(listOf(
            ServerEndpoint("http://untrusted.example", "s"), second)) {
            visited.add(it)
            ProbeResult(true)
        }
        assertEquals(second, selection.endpoint)
        assertEquals(listOf(second), visited)
    }

    @Test fun failedReadinessNeverSelectsAnotherDomain() = runBlocking {
        val selection = ServerChooser.choose(listOf(first, second)) {
            ProbeResult(false, "not ready")
        }
        assertNull(selection.endpoint)
    }
}
