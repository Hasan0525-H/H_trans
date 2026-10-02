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

class ConversionViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app
    private val dao = (app as ArabiFlowApp).db.dao()
    private val wm = WorkManager.getInstance(app)
    val history: Flow<List<Conversion>> = dao.observeAll()
    val config = ServerConfig(app)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    fun importApk(uri: Uri, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { copyAndInspect(uri) }
                dao.upsert(item)
                val work = OneTimeWorkRequestBuilder<ConversionWorker>()
                    .setInputData(workDataOf(ConversionWorker.KEY_ID to item.id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                dao.linkWork(item.id, work.id.toString())
                wm.enqueueUniqueWork("conversion-" + item.id, ExistingWorkPolicy.KEEP, work)
                onCreated(item.id)
            } catch (ex: Exception) {
                _message.value = ex.message ?: "تعذّر استيراد الملف"
            }
        }
    }

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
                    android.content.pm.PackageManager.PackageInfoFlags.of(0))
            else application.packageManager.getPackageArchiveInfo(destination.absolutePath, 0)
            val packageName = info?.packageName ?: "غير معروف"
            val version = info?.versionName ?: "غير معروف"
            return Conversion(id = id, sourcePath = destination.absolutePath, originalName = fileName,
                packageName = packageName, version = version, originalBytes = destination.length())
        } catch (ex: Exception) {
            destination.delete()
            throw ex
        }
    }

    fun retry(old: Conversion, onCreated: (String) -> Unit) {
        viewModelScope.launch {
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
        if (!url.startsWith("https://") || Uri.parse(url).host.isNullOrEmpty()) {
            _message.value = "يجب استخدام عنوان HTTPS صالح"
            return
        }
        if (token.isBlank()) {
            _message.value = "أدخل رمز الوصول للخادم"
            return
        }
        config.url = url.trimEnd('/')
        config.token = token.trim()
        _message.value = "حُفظت إعدادات الاتصال بشكل آمن"
    }

    fun testConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
                val request = Request.Builder().url(config.url + "/health").get().build()
                client.newCall(request).execute().use { response ->
                    _message.value = if (response.isSuccessful) "الخادم متصل"
                    else "فشل الفحص: HTTP ${response.code}"
                }
            } catch (ex: Exception) { _message.value = "تعذّر الاتصال: " + ex.javaClass.simpleName }
        }
    }
}
