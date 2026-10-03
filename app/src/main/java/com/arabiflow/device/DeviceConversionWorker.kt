package com.arabiflow.device

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.arabiflow.ArabiFlowApp
import com.arabiflow.MainActivity
import com.arabiflow.analysis.LocalApkAnalyzer
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ValueType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.security.auth.x500.X500Principal
import java.math.BigInteger
import java.util.Date

/**
 * Actual phone-only pipeline. ML Kit runs neural translations on the phone after a
 * Wi-Fi-only one-time model download. A small bundled glossary is its fallback.
 * Every successful output is newly signed and cryptographically verified.
 */
class DeviceConversionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val dao = (applicationContext as ArabiFlowApp).db.dao()
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val item = dao.get(id) ?: return@withContext Result.failure()
        val source = File(item.sourcePath)
        val dir = File(applicationContext.cacheDir, "device-conversion/$id").apply { mkdirs() }
        val unsigned = File(dir, "unsigned.apk")
        val staged = File(dir, "signed.apk")
        val destDir = File(applicationContext.filesDir, "outputs").apply { mkdirs() }
        val target = File(destDir, "$id.apk")
        val started = System.nanoTime()
        var changed = 0
        var examined = 0
        var rtlApplied = false
        var unknown = 0
        var modelCount = 0
        var needsModel = false
        try {
            setForeground(foreground(5))
            progress(id, 5, "فحص APK على الهاتف")
            val scan = LocalApkAnalyzer.inspect(source)
            require(source.length() <= 128L * 1024 * 1024) {
                "المعالجة المحلية محددة حاليًا بملفات 128 ميجابايت لتجنب نفاد الذاكرة"
            }
            progress(id, 20, "قراءة الموارد الثنائية")
            val module = ApkModule.loadApkFile(source)
            try {
                if (isStopped) throw CancellationException()
                val table = module.getTableBlock() ?: throw IllegalArgumentException(
                    "تعذّر قراءة resources.arsc على الهاتف")
                progress(id, 35, "تحميل نموذج اللغة أو استخدام النموذج المحفوظ")
                PhoneTranslator().use { translator ->
                    val resources = table.getLocalResources("string")
                    while (resources.hasNext()) {
                        if (isStopped) throw CancellationException()
                        if (++examined > 20_000) throw IllegalArgumentException(
                            "تجاوز التطبيق حد موارد النصوص لهذا الإصدار")
                        val resource = resources.next()
                        // Names such as app_name usually contain brands; do not alter them.
                        if (resource.name in setOf("app_name", "application_name", "appName")) {
                            unknown++
                            continue
                        }
                        val entries = resource.iterator().asSequence().filter {
                            it.valueType == ValueType.STRING && !it.isComplex &&
                                !it.valueAsString.isNullOrBlank()
                        }.toList()
                        val nativeArabic = entries.firstOrNull {
                            it.resConfig.language == "ar" &&
                                (it.valueAsString ?: "").any { c -> c in '\u0600'..'\u06ff' }
                        }
                        val primary = nativeArabic ?:
                            entries.firstOrNull { it.resConfig.isDefault } ?: entries.firstOrNull()
                        if (primary == null) {
                            unknown++
                            continue
                        }
                        val sourceValue = primary.valueAsString ?: ""
                        val candidate = if (nativeArabic != null) sourceValue else
                            translator.translate(sourceValue, primary.resConfig.language)
                        if (candidate.isNullOrBlank() || !candidate.any { it in '\u0600'..'\u06ff' }) {
                            unknown++
                            continue
                        }
                        val expectedTokens = PlaceholderGuard.protect(sourceValue).originals
                        var replaced = false
                        // Keep non-Arabic locale values unchanged. Rewriting every locale can
                        // corrupt language-sensitive resources or programmatic string comparisons.
                        // Translate the default and existing Arabic configurations only.
                        entries.filter { entry ->
                            entry.resConfig.isDefault || entry.resConfig.language == "ar"
                        }.forEach { entry ->
                            val original = entry.valueAsString ?: ""
                            if (original == candidate) return@forEach
                            if (PlaceholderGuard.protect(original).originals != expectedTokens)
                                return@forEach
                            entry.setValueAsString(candidate)
                            replaced = true
                            changed++
                        }
                        if (!replaced) unknown++
                    }
                    modelCount = translator.modelTranslations
                    needsModel = translator.modelDownloadFailed
                }
                if (changed == 0) throw IllegalArgumentException(
                    "لم أجد نصوصًا قابلة للترجمة. اتصل بشبكة Wi-Fi لتنزيل نماذج ML Kit المجانية إذا لم تكن محفوظة.")
                progress(id, 55, "تطبيق إعدادات RTL")
                val manifest = module.getAndroidManifest() ?: throw IllegalArgumentException(
                    "تعذّرت قراءة AndroidManifest.xml")
                val app = manifest.getOrCreateApplicationElement()
                app.getOrCreateAndroidAttribute("supportsRtl", android.R.attr.supportsRtl)
                    .setValueAsBoolean(true)
                rtlApplied = true
                module.setApkSignatureBlock(null)
                if (isStopped) throw CancellationException()
                progress(id, 70, "إعادة بناء APK على الهاتف")
                module.writeApk(unsigned)
            } finally {
                module.close()
            }
            if (isStopped) throw CancellationException()
            progress(id, 86, "توقيع APK بمفتاحك المحلي")
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "arabiflow_local_user_key"
            if (!store.containsAlias(alias)) {
                val gen = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
                val begin = Date(System.currentTimeMillis() - 86_400_000L)
                val end = Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L)
                gen.initialize(KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(3072)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=ArabiFlow Local User Signature"))
                    .setCertificateSerialNumber(BigInteger.valueOf(42))
                    .setCertificateNotBefore(begin).setCertificateNotAfter(end)
                    .build())
                gen.generateKeyPair()
            }
            val privateKey = store.getKey(alias, null) as java.security.PrivateKey
            val certificate = store.getCertificate(alias) as java.security.cert.X509Certificate
            val signer = ApkSigner.SignerConfig.Builder(
                "ArabiFlow-Local", privateKey, listOf(certificate)).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(unsigned).setOutputApk(staged)
                .setV1SigningEnabled(false).setV2SigningEnabled(true).build().sign()
            progress(id, 96, "التحقق من التوقيع")
            require(ApkVerifier.Builder(staged).build().verify().isVerified) {
                "فشل التحقق من توقيع APK الناتج"
            }
            LocalApkAnalyzer.inspect(staged)
            if (!staged.renameTo(target)) {
                staged.copyTo(target, overwrite = true)
            }
            val report = JSONObject()
                .put("mode", "on_device_mlkit")
                .put("mlkit_translations", modelCount)
                .put("model_download_failed", needsModel)
                .put("translated_strings", changed)
                .put("total_resource_names", examined)
                .put("untranslated_resource_names", unknown)
                .put("rtl_manifest", rtlApplied)
                .put("input_entries", scan.entries)
                .put("limitations", JSONArray(listOf(
                    "تعمل ML Kit على الهاتف بعد تنزيل النموذج عبر Wi-Fi. قد تبقى النصوص غير المعروفة أو التي فشلت حماية متغيراتها بلا ترجمة.",
                    "الترجمة للموارد النصية وتصريح RTL فقط؛ لا تغطي DEX وCompose وWebView والصور أو التخطيطات المخصصة.",
                    "مفتاح التوقيع محلي وجديد؛ لا يمكن تحديث التطبيق الأصلي ذي التوقيع المختلف.",
                    "لم يُختبر تشغيل هذا التطبيق الناتج على جهاز المستخدم؛ بعض التطبيقات قد لا تعمل بعد التعديل."
                )))
            dao.finish(id, target.absolutePath, target.length(),
                (System.nanoTime() - started) / 1e9, report.toString())
            Result.success()
        } catch (e: CancellationException) {
            dao.cancel(id)
            throw e
        } catch (e: Exception) {
            target.delete()
            dao.fail(id, e.message?.take(450) ?: "فشل التحويل المحلي")
            Result.failure()
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun progress(id: String, pct: Int, title: String) {
        dao.updateStage(id, "running", title, pct)
        setForeground(foreground(pct))
    }
    private fun foreground(pct: Int): ForegroundInfo {
        val pending = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice: Notification = NotificationCompat.Builder(applicationContext, "conversion")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ArabiFlow: معالجة محلية")
            .setContentText("الهاتف يعالج APK: $pct%")
            .setProgress(100, pct, false).setContentIntent(pending).build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(121, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(121, notice)
    }
    companion object { const val KEY_ID = "conversion_id" }
}
