package com.arabiflow.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

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
}
