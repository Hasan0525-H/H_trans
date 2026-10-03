package com.arabiflow.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.android.apksig.ApkVerifier
import com.arabiflow.ArabiFlowApp
import com.arabiflow.data.Conversion
import com.reandroid.apk.ApkModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Runs the actual file pipeline and AndroidKeyStore on an Android emulator. */
@RunWith(AndroidJUnit4::class)
class DeviceConversionIntegrationTest {
    @Test fun buildsSignedArabicApkWithoutAProcessingServer() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(ctx.filesDir, "inputs/" + UUID.randomUUID() + ".apk")
        fixture.parentFile?.mkdirs()
        // Created by :fixture:assembleDebug from this project's own open test app.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .context.assets.open("fixture.apk").use { input ->
                fixture.outputStream().use { input.copyTo(it) }
            }
        val id = UUID.randomUUID().toString()
        val dao = (ctx.applicationContext as ArabiFlowApp).db.dao()
        dao.upsert(Conversion(id = id, sourcePath = fixture.absolutePath,
            originalName = "fixture.apk", packageName = "com.arabiflow.fixture",
            version = "1.0", originalBytes = fixture.length(), status = "analyzed",
            stage = "جاهز"))
        val worker = TestListenableWorkerBuilder<DeviceConversionWorker>(ctx)
            .setInputData(workDataOf(DeviceConversionWorker.KEY_ID to id)).build()
        val result = worker.doWork()
        assertTrue("On-device pipeline: " + dao.get(id)?.error,
            result is ListenableWorker.Result.Success)
        val record = requireNotNull(dao.get(id))
        assertEquals("completed", record.status)
        val output = File(requireNotNull(record.outputPath))
        assertTrue(output.isFile)
        assertTrue(ApkVerifier.Builder(output).build().verify().isVerified)
        val module = ApkModule.loadApkFile(output)
        try {
            val resource = module.tableBlock.getLocalResource("string", "welcome")
            assertNotNull(resource)
            assertEquals("مرحبًا", resource.get().valueAsString)
            assertTrue(module.androidManifest.applicationElement
                .searchAttributeByName("supportsRtl").valueAsBoolean)
        } finally { module.close() }
    }
}
