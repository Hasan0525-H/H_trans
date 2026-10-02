package com.arabiflow.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.arabiflow.ArabiFlowApp
import com.arabiflow.MainActivity
import com.arabiflow.data.ServerConfig
import com.arabiflow.data.ServerAutoConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MultipartBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = (context.applicationContext as ArabiFlowApp).db.dao()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(20, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val source = dao.get(id) ?: return@withContext Result.failure()
        val config = ServerConfig(applicationContext)
        val selected = ServerAutoConnector().discover(config).endpoint
        if (selected == null) {
            dao.fail(id, "لا يوجد خادم موثوق وجاهز. أضف خادمًا احتياطيًا أو حاول لاحقًا.")
            return@withContext Result.failure()
        }
        config.activate(selected)
        // Pin this exact origin and its credential for the lifetime of a job.
        // Do not switch during a started job; doing so would duplicate uploads or
        // query an unrelated server using a different job identifier.
        val url = selected.url
        val token = selected.token
        if (!File(source.sourcePath).isFile) {
            dao.fail(id, "الملف الأصلي غير موجود")
            return@withContext Result.failure()
        }
        try {
            setForeground(makeForeground(0))
            dao.updateStage(id, "running", "رفع الملف إلى الخادم", 0)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("apk", source.originalName,
                    File(source.sourcePath).asRequestBody("application/vnd.android.package-archive".toMediaType()))
                .build()
            val created = request(url + "/jobs", token, "POST", body)
            val remoteId = JSONObject(created).getString("id")
            var attempts = 0
            while (attempts++ < 3600) {
                if (isStopped) throw CancellationException("Job cancelled")
                delay(2000)
                val state = JSONObject(request(url + "/jobs/" + remoteId, token))
                val progress = state.optInt("progress", 0).coerceIn(0, 100)
                val stage = state.optString("stage", "Processing")
                dao.updateStage(id, "running", stage, progress)
                setProgress(workDataOf("progress" to progress, "stage" to stage))
                setForeground(makeForeground(progress))
                when (state.optString("status")) {
                    "failed" -> throw IOException(state.optString("error", "الخادم لم يتمكن من معالجة APK"))
                    "completed" -> {
                        val outDir = File(applicationContext.filesDir, "outputs").apply { mkdirs() }
                        val part = File(outDir, "$id.part")
                        val final = File(outDir, "$id.apk")
                        try {
                            val download = Request.Builder().url(url + "/jobs/" + remoteId + "/download")
                                .header("Authorization", "Bearer $token").get().build()
                            client.newCall(download).execute().use { response ->
                                if (!response.isSuccessful) throw IOException("Download: HTTP ${response.code}")
                                val data = response.body ?: throw IOException("الخادم أرسل ملفًا فارغًا")
                                part.outputStream().use { output ->
                                    data.byteStream().use { input -> input.copyTo(output, 1024 * 1024) }
                                }
                            }
                            if (!validApk(part)) throw IOException("ملف APK الناتج غير صالح")
                            if (!part.renameTo(final)) throw IOException("تعذّر حفظ الملف النهائي")
                            val report = state.optJSONObject("report")
                            // Remove server-side artifact after verified local persistence.
                            try {
                                request(url + "/jobs/" + remoteId, token, "DELETE")
                            } catch (_: Exception) {
                                report?.optJSONArray("warnings")?.put(
                                    "Server artifact could not be deleted automatically; ask your server administrator.")
                            }
                            dao.finish(id, final.absolutePath, final.length(),
                                report?.optDouble("elapsed_seconds", 0.0) ?: 0.0,
                                report?.toString() ?: "{}")
                            return@withContext Result.success()
                        } finally { part.delete() }
                    }
                }
            }
            throw IOException("انتهت مهلة الانتظار الطويلة للتحويل")
        } catch (ex: CancellationException) {
            dao.cancel(id)
            throw ex
        } catch (ex: Exception) {
            dao.fail(id, ex.message?.take(450) ?: "حدث خطأ غير متوقع")
            Result.failure()
        }
    }

    private fun request(url: String, token: String, method: String = "GET",
                        body: okhttp3.RequestBody? = null): String {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .method(method, body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful)
                throw IOException("Server HTTP ${response.code}: " + (response.body?.string()?.take(300) ?: ""))
            return response.body?.string() ?: throw IOException("خادم التحويل لم يرسل استجابة")
        }
    }

    private fun validApk(file: File): Boolean = try {
        ZipFile(file).use { zip ->
            file.length() > 0 && zip.getEntry("AndroidManifest.xml") != null &&
                zip.getEntry("resources.arsc") != null
        }
    } catch (_: Exception) { false }

    private fun makeForeground(progress: Int): ForegroundInfo {
        val intent = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = NotificationCompat.Builder(applicationContext, "conversion")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ArabiFlow AI")
            .setContentText("تعريب APK: $progress%")
            .setProgress(100, progress, false)
            .setOngoing(progress < 100).setContentIntent(intent).build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(120, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(120, notification)
    }
    companion object { const val KEY_ID = "conversion_id" }
}
