package com.arabiflow.device

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Byte-for-byte check for runtime code. APK resource rewrites must not accidentally
 * damage .dex or native .so entries. This does NOT prove an app can launch:
 * signature checks and dynamic/runtime dependencies require separate testing.
 */
object ApkPayloadIntegrity {
    fun verifyUnchangedCode(original: File, generated: File) {
        ZipFile(original).use { from ->
            ZipFile(generated).use { to ->
                val names = mutableSetOf<String>()
                val entries = from.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && (isDex(entry.name) || isNative(entry.name)))
                        names.add(entry.name)
                }
                require(names.isNotEmpty()) { "APK لا يحتوي على كود DEX أو مكتبات أصلية" }
                for (name in names) {
                    val first = from.getEntry(name)
                    val second = to.getEntry(name)
                    require(first != null && second != null) {
                        "فُقد ملف تنفيذي عند إعادة البناء: " + name
                    }
                    val left = MessageDigest.getInstance("SHA-256")
                    val right = MessageDigest.getInstance("SHA-256")
                    from.getInputStream(first).use { stream ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            val count = stream.read(bytes)
                            if (count < 0) break
                            left.update(bytes, 0, count)
                        }
                    }
                    to.getInputStream(second).use { stream ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            val count = stream.read(bytes)
                            if (count < 0) break
                            right.update(bytes, 0, count)
                        }
                    }
                    require(left.digest().contentEquals(right.digest())) {
                        "تغيّرت شيفرة التطبيق دون قصد عند إعادة البناء: " + name
                    }
                }
            }
        }
    }

    private fun isDex(name: String): Boolean =
        Regex("^classes([0-9]+)?\\.dex$").matches(name)

    private fun isNative(name: String): Boolean =
        name.startsWith("lib/") && name.endsWith(".so")
}
