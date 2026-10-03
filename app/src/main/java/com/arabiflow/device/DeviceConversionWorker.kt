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
 * Actual phone-only pipeline. Phase 1 uses a small bundled phrase glossary;
 * coverage is explicitly partial, not AI-generated or arbitrary-language translation.
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
                val resources = table.getLocalResources("string")
                while (resources.hasNext()) {
                    if (isStopped) throw CancellationException()
                    val resource = resources.next()
                    val entries = resource.iterator()
                    val original = mutableListOf<com.reandroid.arsc.value.Entry>()
                    var candidate: String? = null
                    while (entries.hasNext()) {
                        val entry = entries.next()
                        if (entry.valueType != ValueType.STRING || entry.isComplex) continue
                        val sourceText = entry.valueAsString ?: continue
                        original.add(entry)
                        if (candidate == null) candidate = OfflineGlossary.translate(sourceText)
                    }
                    examined++
                    if (candidate != null) {
                        // Apply one consistent Arabic label to matching locale variants only.
                        original.forEach { entry ->
                            if (OfflineGlossary.translate(entry.valueAsString ?: "") != null) {
                                entry.setValueAsString(candidate)
                                changed++
                            }
                        }
                    } else unknown++
                }
                if (changed == 0) throw IllegalArgumentException(
                    "لم يجد القاموس المحلي أي نص يمكن تعريبه؛ لن أصدر APK غير معرّب")
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
                .put("mode", "on_device_limited_glossary")
                .put("translated_strings", changed)
                .put("total_resource_names", examined)
                .put("untranslated_resource_names", unknown)
                .put("rtl_manifest", rtlApplied)
                .put("input_entries", scan.entries)
                .put("limitations", JSONArray(listOf(
                    "القاموس المحلي محدود؛ معظم النصوص غير المعروفة ستبقى بلغتها الأصلية.",
                    "تم تعديل موارد النصوص المعروفة وتصريح RTL، وليس كل تخطيطات التطبيق أو النصوص داخل DEX أو الصور أو WebView.",
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
