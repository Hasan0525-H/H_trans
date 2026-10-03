package com.arabiflow.device

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.util.Locale

/** Download-once model translation: phrases never travel to an ArabiFlow server. */
class PhoneTranslator : Closeable {
    private val identifier = LanguageIdentification.getClient()
    private val active = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()
    private val available = mutableSetOf<String>()
    private val rejected = mutableSetOf<String>()
    private val cache = mutableMapOf<String, String>()
    var modelDownloadFailed = false
        private set
    var modelTranslations = 0
        private set

    /** Falls back to the bundled glossary when ML models are unavailable. */
    suspend fun translate(text: String, sourceHint: String? = null): String? {
        val fallback = OfflineGlossary.translate(text)
        if (!eligible(text)) return fallback
        if (text.any { it in '\u0600'..'\u06ff' } && text.none { it in 'A'..'z' }) return null
        val source = resolveLanguage(text, sourceHint)
        if (source == TranslateLanguage.ARABIC) return null
        val key = source + "\n" + text
        cache[key]?.let { return it }
        if (source in rejected) return fallback
        val protected = PlaceholderGuard.protect(text)
        val client = active.getOrPut(source) {
            Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.ARABIC).build())
        }
        return try {
            if (source !in available) {
                // First use needs Wi-Fi only. Cached models work entirely offline.
                client.downloadModelIfNeeded(
                    DownloadConditions.Builder().requireWifi().build()).await()
                available.add(source)
            }
            val candidate = client.translate(protected.text).await().trim()
            val restored = PlaceholderGuard.restore(candidate, protected)
            if (restored.isNullOrBlank() || !restored.any { it in '\u0600'..'\u06ff' }) fallback
            else restored.also {
                cache[key] = it
                modelTranslations++
            }
        } catch (_: Exception) {
            modelDownloadFailed = true
            rejected.add(source)
            fallback
        }
    }

    private fun eligible(text: String): Boolean {
        if (text.length !in 2..700 || text.startsWith("@") || text.startsWith("?")) return false
        if (text.any { it == '<' || it == '>' || it == '\u0000' }) return false
        return text.any { it.isLetter() }
    }

    private suspend fun resolveLanguage(text: String, hint: String?): String {
        val supported = hint?.substringBefore('-')?.lowercase(Locale.ROOT)
            ?.let(TranslateLanguage::fromLanguageTag)
        if (supported != null) return supported
        if (text.any { it in '\u4e00'..'\u9fff' }) return TranslateLanguage.CHINESE
        if (text.length <= 3) return TranslateLanguage.ENGLISH
        return try {
            val tag = identifier.identifyLanguage(text).await()
            TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
        } catch (_: Exception) { TranslateLanguage.ENGLISH }
    }

    override fun close() {
        active.values.forEach { it.close() }
        identifier.close()
    }
}

/** Strict preservation of Android placeholders, resource references and XML markers. */
object PlaceholderGuard {
    private val tokens = Regex("""%(?:\d+\$)?[-+# 0,(]*\d*(?:\.\d+)?[a-zA-Z%]|\{[\w., ]+\}|@[\w./]+|\\[nrt]""")
    data class Protected(val text: String, val originals: List<String>)
    fun protect(source: String): Protected {
        val found = mutableListOf<String>()
        val safe = tokens.replace(source) {
            found.add(it.value)
            marker(found.size - 1)
        }
        return Protected(safe, found)
    }
    fun restore(candidate: String, payload: Protected): String? {
        if (payload.originals.indices.any { idx ->
                val marker = marker(idx)
                candidate.indexOf(marker) < 0 ||
                    candidate.indexOf(marker) != candidate.lastIndexOf(marker)
            }) return null
        var result = candidate
        payload.originals.forEachIndexed { idx, original ->
            result = result.replace(marker(idx), original)
        }
        return if (result.contains(Regex("ZPH\\d{4}PZ"))) null else result
    }
    private fun marker(index: Int): String = "ZPH" + index.toString().padStart(4, '0') + "PZ"
}
