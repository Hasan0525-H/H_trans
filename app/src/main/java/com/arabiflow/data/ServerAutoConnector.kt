package com.arabiflow.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Readiness inspection never uploads an APK, follows redirects or shares credentials across origins. */
class ServerAutoConnector {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun discover(config: ServerConfig): ServerSelection = withContext(Dispatchers.IO) {
        ServerChooser.choose(config.endpoints(), ::probe)
    }

    suspend fun probe(endpoint: ServerEndpoint): ProbeResult = withContext(Dispatchers.IO) {
        if (!ServerConfig.validateOrigin(endpoint.url) || endpoint.token.isBlank())
            return@withContext ProbeResult(false, "عنوان HTTPS أو رمز وصول غير صالح")
        try {
            val req = Request.Builder().get().url(endpoint.url + "/ready")
                .header("Authorization", "Bearer " + endpoint.token).build()
            client.newCall(req).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string()?.take(8192).orEmpty()
                        val json = JSONObject(body)
                        if (json.optString("service") != "arabiflow" ||
                            json.optInt("protocol_version", -1) != 1) {
                            ProbeResult(false, "واجهة غير متوافقة مع ArabiFlow")
                        } else if (json.optBoolean("ready")) {
                            ProbeResult(true, "")
                        } else {
                            val missing = json.optJSONArray("missing")
                            val reason = if (missing == null) "الخادم غير جاهز" else
                                (0 until missing.length()).joinToString("، ") { missing.optString(it) }
                            ProbeResult(false, "متطلبات ناقصة: " + reason)
                        }
                    }
                    401, 403 -> ProbeResult(false, "رمز الوصول غير صحيح لهذا الخادم")
                    301, 302, 307, 308 -> ProbeResult(false, "إعادة التوجيه مرفوضة لحماية رمز الوصول")
                    else -> ProbeResult(false, "خطأ HTTP " + response.code)
                }
            }
        } catch (_: Exception) {
            ProbeResult(false, "تعذّر الاتصال أو التحقق من شهادة HTTPS")
        }
    }
}
