package com.arabiflow.device

import org.junit.Assert.*
import org.junit.Test

class OfflineGlossaryTest {
    @Test fun fixedPhrasesTranslateWithoutNetworking() {
        assertEquals("الإعدادات", OfflineGlossary.translate("Settings"))
        assertEquals("مرحبًا", OfflineGlossary.translate("Welcome"))
        assertEquals("مرحبًا، %1$s", OfflineGlossary.translate("Welcome, %1$s"))
        assertEquals("الفرنسية", OfflineGlossary.translate("Français"))
        assertEquals("الإعدادات", OfflineGlossary.translate("设置"))
    }
    @Test fun unknownOrFormattedResourcesAreNeverInvented() {
        assertNull(OfflineGlossary.translate("Pay $999 with %{customer}"))
        assertNull(OfflineGlossary.translate("<b>Settings</b>"))
        assertNull(OfflineGlossary.translate("The unique app-specific dashboard headline"))
        assertEquals("  حفظ  ", OfflineGlossary.translate("  Save  "))
    }
}
