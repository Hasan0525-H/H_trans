package com.arabiflow

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.arabiflow.data.*
import com.arabiflow.analysis.LocalApkAnalyzer
import org.json.JSONObject
import org.json.JSONArray
import com.arabiflow.work.ConversionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ServerPhase { UNCONFIGURED, UNVERIFIED, CHECKING, READY, ERROR }
data class ServerConnection(val phase: ServerPhase, val detail: String)

class ConversionViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app
    private val dao = (app as ArabiFlowApp).db.dao()
    private val wm = WorkManager.getInstance(app)
    val history: Flow<List<Conversion>> = dao.observeAll()
    val config = ServerConfig(app)
    private val _connection = MutableStateFlow(
        if (hasServerConfiguration()) ServerConnection(ServerPhase.UNVERIFIED, "التحقق مطلوب قبل بدء التحويل")
        else ServerConnection(ServerPhase.UNCONFIGURED, "يلزم إعداد خادم التعريب أولاً")
    )
    val connection = _connection.asStateFlow()
    fun hasServerConfiguration(): Boolean =
        config.url.startsWith("https://") && config.token.isNotBlank()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    /** Import and report on a local APK even when no processing server exists. */
    fun importApk(uri: Uri, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { copyAndInspect(uri) }
                dao.upsert(item)
                onCreated(item.id)
            } catch (ex: Exception) {
                _message.value = ex.message ?: "تعذّر تحليل APK"
            }
        }
    }

    fun analyzeStored(old: Conversion) {
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val source = File(old.sourcePath)
                    val summary = LocalApkAnalyzer.inspect(source)
                    makeLocalReport(summary)
                }
                dao.markAnalyzed(old.id, report)
            } catch (ex: Exception) {
                _message.value = ex.message ?: "تعذّر تحليل النسخة المخزنة"
            }
        }
    }

    /** Analysis must complete before network conversion is offered. */
    fun startConversion(item: Conversion, onSetupNeeded: () -> Unit) {
        viewModelScope.launch {
            if (item.status != "analyzed") return@launch
            if (!probeServer()) {
                onSetupNeeded()
                return@launch
            }
            if (!File(item.sourcePath).isFile) {
                _message.value = "الملف الأصلي لم يعد متاحًا"
                return@launch
            }
            val work = OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(workDataOf(ConversionWorker.KEY_ID to item.id))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            dao.linkWork(item.id, work.id.toString())
            dao.updateStage(item.id, "queued", "بانتظار بدء المعالجة", 0)
            wm.enqueueUniqueWork("conversion-" + item.id, ExistingWorkPolicy.KEEP, work)
        }
    }

    private fun makeLocalReport(summary: com.arabiflow.analysis.LocalApkSummary): String =
        JSONObject().apply {
            put("mode", "offline")
            put("entries", summary.entries)
            put("dex_files", summary.dexFiles)
            put("resource_files", summary.resourceFiles)
            put("xml_path_candidates", summary.xmlCandidates)
            put("layout_path_candidates", summary.layoutPathCandidates)
            put("assets", summary.assets)
            put("abi_names", JSONArray(summary.abis))
            put("locale_path_hints", JSONArray(summary.localePathHints))
            put("resource_table_present", summary.compiledResourcesPresent)
            put("limitations", JSONArray(listOf(
                "هذا فحص محلي لفهرس APK وليس فكًا لموارد resources.arsc.",
                "عدم ظهور اللغات أو التخطيطات في مسارات ZIP لا يعني عدم وجودها.",
                "تعريب النصوص وإعادة بناء APK يحتاجان إلى خادم المعالجة."
            )))
        }.toString()

    private fun copyAndInspect(uri: Uri): Conversion {
        var fileName = "imported.apk"
        application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) fileName = cursor.getString(0) ?: fileName
            }
        if (!fileName.lowercase().endsWith(".apk")) throw IllegalArgumentException("اختر ملفًا بامتداد APK")
        val id = UUID.randomUUID().toString()
        val inputDir = File(application.filesDir, "inputs").apply { mkdirs() }
        val destination = File(inputDir, "$id.apk")
        try {
            application.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > 512L * 1024 * 1024)
                            throw IllegalArgumentException("الحد الأقصى لملف APK هو 512 ميجابايت")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("تعذّر قراءة الملف")
            if (destination.length() == 0L) throw IllegalArgumentException("الملف فارغ")
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= 33) application.packageManager
                .getPackageArchiveInfo(destination.absolutePath,
                    android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            else application.packageManager.getPackageArchiveInfo(destination.absolutePath,
                if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else 0)
            val appInfo = info?.applicationInfo
            val appLabel = try {
                if (appInfo != null) {
                    appInfo.sourceDir = destination.absolutePath
                    appInfo.publicSourceDir = destination.absolutePath
                    application.packageManager.getApplicationLabel(appInfo).toString()
                } else fileName
            } catch (_: Exception) { fileName }
            val sha = if (Build.VERSION.SDK_INT >= 28) {
                val signer = info?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                signer?.let { bytes ->
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString(":") { "%02X".format(it.toInt() and 255) }
                } ?: ""
            } else ""
            val packageName = info?.packageName ?: "غير معروف"
            val version = info?.versionName ?: "غير معروف"
            val summary = LocalApkAnalyzer.inspect(destination)
            return Conversion(id = id, sourcePath = destination.absolutePath, originalName = fileName,
                appLabel = appLabel, signingCertificateSha256 = sha,
                packageName = packageName, version = version, originalBytes = destination.length(),
                status = "analyzed", stage = "اكتمل التحليل المحلي",
                report = makeLocalReport(summary))
        } catch (ex: Exception) {
            destination.delete()
            throw ex
        }
    }

    fun retry(old: Conversion, onCreated: (String) -> Unit, onSetupNeeded: () -> Unit) {
        viewModelScope.launch {
            if (!probeServer()) {
                onSetupNeeded()
                return@launch
            }
            if (!File(old.sourcePath).isFile) {
                _message.value = "الملف الأصلي محذوف؛ استورده من جديد"
                return@launch
            }
            val id = UUID.randomUUID().toString()
            val entry = old.copy(id = id, createdAt = System.currentTimeMillis(),
                status = "queued", stage = "بانتظار المعالجة", progress = 0,
                outputPath = null, resultBytes = 0, elapsedSeconds = 0.0,
                error = "", report = "", workId = null)
            dao.upsert(entry)
            val work = OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(workDataOf(ConversionWorker.KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            dao.linkWork(id, work.id.toString())
            wm.enqueue(work)
            onCreated(id)
        }
    }

    fun cancel(item: Conversion) {
        viewModelScope.launch {
            item.workId?.let { wm.cancelWorkById(UUID.fromString(it)) }
            dao.cancel(item.id)
        }
    }

    fun delete(item: Conversion) {
        viewModelScope.launch(Dispatchers.IO) {
            if (item.status == "running" || item.status == "queued") {
                _message.value = "ألغِ العملية قبل حذفها"
                return@launch
            }
            item.outputPath?.let { File(it).delete() }
            val references = dao.observeAll().first().count { it.sourcePath == item.sourcePath }
            if (references == 1) File(item.sourcePath).delete()
            dao.delete(item.id)
        }
    }

    fun save(item: Conversion, target: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(item.outputPath ?: throw IllegalStateException("ملف النتيجة غير متاح"))
                application.contentResolver.openOutputStream(target, "w")?.use { output ->
                    file.inputStream().use { it.copyTo(output, 1024 * 1024) }
                } ?: throw IllegalStateException("تعذّر فتح موقع الحفظ")
                _message.value = "تم حفظ APK بنجاح"
            } catch (ex: Exception) { _message.value = ex.message ?: "فشل الحفظ" }
        }
    }

    fun saveSettings(url: String, token: String) {
        val parsed = Uri.parse(url.trim())
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) {
            _message.value = "أدخل عنوان HTTPS صحيحًا للخادم"
            return
        }
        if (token.isBlank()) {
            _message.value = "أدخل رمز الوصول الذي أنشأته على الخادم"
            return
        }
        config.url = url.trim().trimEnd('/')
        config.token = token.trim()
        _connection.value = ServerConnection(ServerPhase.UNVERIFIED, "الإعدادات محفوظة؛ جاري اختبار الخادم")
        testConnection()
    }

    fun testConnection() {
        viewModelScope.launch { probeServer() }
    }

    /** Do not upload APKs or create WorkManager tasks before authenticated readiness passes. */
    private suspend fun probeServer(): Boolean {
        if (!hasServerConfiguration()) {
            _connection.value = ServerConnection(ServerPhase.UNCONFIGURED,
                "لم يُجهَّز خادم التعريب. يجب تشغيل الخادم وإضافة عنوانه ورمز وصوله هنا.")
            _message.value = "أعدّ الخادم قبل استيراد APK؛ التطبيق لا يتضمن خادمًا مستضافًا."
            return false
        }
        _connection.value = ServerConnection(ServerPhase.CHECKING, "جاري التحقق من اتصال الخادم وتجهيز أدواته")
        val result = withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS).build()
                val request = Request.Builder().url(config.url + "/ready")
                    .header("Authorization", "Bearer " + config.token).get().build()
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val payload = org.json.JSONObject(response.body?.string() ?: "{}")
                            if (payload.optBoolean("ready")) {
                                ServerConnection(ServerPhase.READY,
                                    "تم التحقق من الخادم وإعداداته. سيتم اختبار الترجمة أثناء التحويل.")
                            } else {
                                val missing = payload.optJSONArray("missing")
                                val details = if (missing != null) (0 until missing.length())
                                    .joinToString("، ") { missing.optString(it) } else "غير معروف"
                                ServerConnection(ServerPhase.ERROR,
                                    "الخادم لا يملك متطلبات المعالجة: " + details)
                            }
                        }
                        401, 403 -> ServerConnection(ServerPhase.ERROR,
                            "رمز الوصول غير صحيح. استخدم نفس ARABIFLOW_API_TOKEN الموجود على الخادم.")
                        404 -> ServerConnection(ServerPhase.ERROR,
                            "لم يُعثر على واجهة /ready. تأكد من عنوان الخادم وتحديث نسخة المعالجة.")
                        else -> ServerConnection(ServerPhase.ERROR,
                            "الخادم غير جاهز (HTTP " + response.code + "). راجع سجل الخادم.")
                    }
                }
            } catch (ex: Exception) {
                ServerConnection(ServerPhase.ERROR,
                    "تعذّر الاتصال بالخادم عبر HTTPS. تحقق من العنوان والإنترنت وشهادة الأمان.")
            }
        }
        _connection.value = result
        if (result.phase != ServerPhase.READY) _message.value = result.detail
        return result.phase == ServerPhase.READY
    }
}
