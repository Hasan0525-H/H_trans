package com.arabiflow.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ServerEndpoint(val url: String, val token: String)

class ServerConfig(context: Context) {
    private val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(value) { prefs.edit().putString("url", value.trimEnd('/')).apply() }

    private val key: javax.crypto.SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("arabiflow_server_token", null) as? javax.crypto.SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder("arabiflow_server_token",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generator.generateKey()
        }
    }
    var token: String
        get() {
            val payload = prefs.getString("token", null) ?: return ""
            return try {
                val bytes = Base64.decode(payload, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            } catch (_: Exception) { "" }
        }
        set(value) {
            if (value.isEmpty()) {
                prefs.edit().remove("token").apply()
                return
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("token", Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)).apply()
        }
    // Only HTTPS origins explicitly added by the owner are eligible for automatic failover.
    // The token belongs to that origin; never reuse it for a different host.
    fun endpoints(): List<ServerEndpoint> {
        val encoded = prefs.getString("server_pool", null)
        if (!encoded.isNullOrBlank()) {
            try {
                val array = JSONArray(decrypt(encoded))
                return (0 until array.length()).mapNotNull { i ->
                    val entry = array.optJSONObject(i) ?: return@mapNotNull null
                    val address = entry.optString("url")
                    val secret = entry.optString("token")
                    if (validateOrigin(address) && secret.isNotBlank())
                        ServerEndpoint(address, secret) else null
                }.distinctBy { it.url }.take(4)
            } catch (_: Exception) { return emptyList() }
        }
        // Backward-compatible migration of the original encrypted single-server settings.
        val oldUrl = url
        val oldToken = token
        return if (validateOrigin(oldUrl) && oldToken.isNotBlank())
            listOf(ServerEndpoint(oldUrl, oldToken)) else emptyList()
    }

    fun addEndpoint(address: String, secret: String) {
        val normalized = address.trim().trimEnd('/')
        require(validateOrigin(normalized)) {
            "استخدم HTTPS أو http://127.0.0.1:8000 عند التوصيل المحلي عبر USB"
        }
        require(secret.isNotBlank()) { "رمز الوصول مطلوب لهذا الخادم" }
        val next = (listOf(ServerEndpoint(normalized, secret.trim())) +
            endpoints().filterNot { it.url == normalized }).take(4)
        saveEndpoints(next)
        activate(next.first())
    }

    fun removeEndpoint(address: String) {
        val next = endpoints().filterNot { it.url == address }
        saveEndpoints(next)
        if (next.isEmpty()) {
            url = ""
            token = ""
        } else activate(next.first())
    }

    fun activate(selected: ServerEndpoint) {
        require(endpoints().any { it == selected }) { "خادم غير مسجل" }
        // Set the HTTPS origin before its per-origin token; both are stored locally.
        url = selected.url
        token = selected.token
    }

    private fun saveEndpoints(items: List<ServerEndpoint>) {
        val payload = JSONArray()
        items.forEach { payload.put(JSONObject().put("url", it.url).put("token", it.token)) }
        prefs.edit().putString("server_pool", encrypt(payload.toString())).apply()
    }

    companion object {
        fun validateOrigin(address: String): Boolean {
            val url = address.toHttpUrlOrNull() ?: return false
            val localUsb = url.scheme == "http" && url.host == "127.0.0.1"
            val remoteTls = url.scheme == "https" && url.host.isNotBlank()
            return (localUsb || remoteTls) && url.username.isEmpty() && url.password.isEmpty() &&
                url.encodedPath == "/" && url.query == null && url.fragment == null
        }
    }

    private fun encrypt(clear: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.iv + cipher.doFinal(clear.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12) { "No valid stored server credentials" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

}
