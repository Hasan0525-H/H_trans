package com.arabiflow.analysis

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalApkAnalyzerTest {
    private fun fixture(vararg names: String): File =
        File.createTempFile("arabiflow-fixture-", ".apk").apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { zip ->
                names.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }
        }

    @Test fun reportsOnlyArchivePathHints() {
        val path = fixture("AndroidManifest.xml", "resources.arsc", "classes.dex",
            "classes2.dex", "res/layout/main.xml", "res/values-es/strings.xml",
            "assets/config.txt", "lib/arm64-v8a/libfoo.so")
        val summary = LocalApkAnalyzer.inspect(path)
        assertEquals(8, summary.entries)
        assertEquals(2, summary.dexFiles)
        assertEquals(2, summary.resourceFiles)
        assertEquals(1, summary.layoutPathCandidates)
        assertEquals(listOf("es"), summary.localePathHints)
        assertEquals(listOf("arm64-v8a"), summary.abis)
    }

    @Test fun rejectsMissingResourceTable() {
        val path = fixture("AndroidManifest.xml", "classes.dex")
        assertThrows(IllegalArgumentException::class.java) { LocalApkAnalyzer.inspect(path) }
    }

    @Test fun rejectsTraversalMember() {
        val path = fixture("AndroidManifest.xml", "resources.arsc", "classes.dex", "../escape")
        assertThrows(IllegalArgumentException::class.java) { LocalApkAnalyzer.inspect(path) }
    }

    @Test fun rejectsBadZip() {
        val path = File.createTempFile("invalid", ".apk").apply {
            deleteOnExit()
            writeText("not a zip")
        }
        assertThrows(IllegalArgumentException::class.java) { LocalApkAnalyzer.inspect(path) }
    }
}
