package com.arabiflow.analysis

import java.io.File
import java.util.zip.ZipFile

/** Archive-index inspection only: does not decode resources.arsc or execute foreign code. */
data class LocalApkSummary(
    val entries: Int,
    val dexFiles: Int,
    val resourceFiles: Int,
    val xmlCandidates: Int,
    val layoutPathCandidates: Int,
    val assets: Int,
    val abis: List<String>,
    val localePathHints: List<String>,
    val compiledResourcesPresent: Boolean
)

object LocalApkAnalyzer {
    private const val MAX_ENTRIES = 70000
    private const val MAX_UNCOMPRESSED = 2L * 1024 * 1024 * 1024
    private val localePath = Regex("^res/values-([A-Za-z0-9+_-]+)/")

    fun inspect(path: File): LocalApkSummary {
        if (!path.isFile || path.length() <= 0L || path.length() > 512L * 1024 * 1024) {
            throw IllegalArgumentException("حجم أو مسار APK غير صالح")
        }
        try {
            ZipFile(path).use { zip ->
                val names = HashSet<String>()
                val abis = sortedSetOf<String>()
                val locales = sortedSetOf<String>()
                var count = 0
                var total = 0L
                var dex = 0
                var resources = 0
                var xml = 0
                var layouts = 0
                var assets = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    count++
                    if (count > MAX_ENTRIES) throw IllegalArgumentException("يحتوي الملف على عناصر كثيرة جدًا")
                    val name = entry.name
                    if (name.isBlank() || name.startsWith("/") || name.contains('\\') ||
                        name.split('/').any { it == ".." } || name.contains(':') ||
                        !names.add(name)) throw IllegalArgumentException("مسارات غير آمنة أو مكررة داخل APK")
                    if (entry.size > 0) {
                        total += entry.size
                        if (total > MAX_UNCOMPRESSED) throw IllegalArgumentException("حجم الموارد غير المضغوطة كبير جدًا")
                    }
                    if (entry.isDirectory) continue
                    if (Regex("(^|/)classes[0-9]*\\.dex$").containsMatchIn(name)) dex++
                    if (name.startsWith("res/")) {
                        resources++
                        if (name.endsWith(".xml")) xml++
                        if (name.startsWith("res/layout")) layouts++
                        localePath.find(name)?.groupValues?.get(1)?.let { locales.add(it) }
                    }
                    if (name.startsWith("assets/")) assets++
                    if (name.startsWith("lib/")) name.split('/').getOrNull(1)
                        ?.takeIf { it.isNotBlank() }?.let { abis.add(it) }
                }
                if (!names.contains("AndroidManifest.xml") || !names.contains("resources.arsc") || dex == 0) {
                    throw IllegalArgumentException("الملف ليس APK أساسيًا مدعومًا أو تنقصه الموارد")
                }
                return LocalApkSummary(count, dex, resources, xml, layouts, assets,
                    abis.toList(), locales.toList(), true)
            }
        } catch (e: java.util.zip.ZipException) {
            throw IllegalArgumentException("ملف APK تالف أو ليس أرشيفًا صالحًا", e)
        }
    }
}
