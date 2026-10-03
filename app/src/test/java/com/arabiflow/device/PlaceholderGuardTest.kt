package com.arabiflow.device

import org.junit.Assert.*
import org.junit.Test

class PlaceholderGuardTest {
    @Test fun preservesIndexedAndNamedVariables() {
        val source = "Welcome, %1\$s {user} @string/app_name"
        val payload = PlaceholderGuard.protect(source)
        assertFalse(payload.text.contains("%1\$s"))
        assertEquals(source, PlaceholderGuard.restore(payload.text, payload))
    }
    @Test fun discardsTranslationsThatLoseOrDuplicateVariables() {
        val payload = PlaceholderGuard.protect("Price %1\$s")
        assertNull(PlaceholderGuard.restore("السعر", payload))
        assertNull(PlaceholderGuard.restore(payload.text + " " + payload.text, payload))
    }
}
