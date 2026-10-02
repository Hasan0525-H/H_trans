package com.arabiflow

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.DragEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import com.arabiflow.data.Conversion
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: ConversionViewModel by viewModels()
    private var dropped by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.clipDescription != null
                DragEvent.ACTION_DROP -> {
                    val uri = event.clipData?.getItemAt(0)?.uri
                    if (uri == null) false else {
                        requestDragAndDropPermissions(event)
                        dropped = uri
                        true
                    }
                }
                else -> true
            }
        }
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF55D9C2))
            else lightColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF103942),
                secondary = androidx.compose.ui.graphics.Color(0xFF0EAA91),
                background = androidx.compose.ui.graphics.Color(0xFFF4F7F8))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = colors) {
                    ArabiFlowUI(vm, dropped, { dropped = null }, ::install, ::share)
                }
            }
        }
    }
    private fun fileUri(path: String) = FileProvider.getUriForFile(
        this, "${packageName}.files", File(path))

    private fun install(item: Conversion) {
        val path = item.outputPath ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(fileUri(path), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun share(item: Conversion) {
        val path = item.outputPath ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, fileUri(path))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "مشاركة APK"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArabiFlowUI(vm: ConversionViewModel, dropped: Uri?, onDropHandled: () -> Unit,
                        onInstall: (Conversion) -> Unit, onShare: (Conversion) -> Unit) {
    val history by vm.history.collectAsState(initial = emptyList())
    val feedback by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf("home") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = history.firstOrNull { it.id == selectedId }
    val onSelect: (String) -> Unit = { selectedId = it; page = "details" }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) vm.importApk(it, onSelect)
    }
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        if (uri != null && selected != null) vm.save(selected, uri)
    }
    LaunchedEffect(dropped) {
        if (dropped != null) {
            vm.importApk(dropped, onSelect)
            onDropHandled()
        }
    }
    LaunchedEffect(feedback) {
        feedback?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("ArabiFlow AI") },
            actions = { TextButton(onClick = { page = "settings" }) { Text("الإعدادات") } }) },
        bottomBar = {
            NavigationBar {
                listOf(Triple("home", "الرئيسية", Icons.Default.Home),
                    Triple("history", "السجل", Icons.Default.History),
                    Triple("settings", "الإعدادات", Icons.Default.Settings)).forEach { (route, title, icon) ->
                    NavigationBarItem(selected = page == route,
                        onClick = { page = route; selectedId = null },
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title) })
                }
            }
        }
    ) { inset ->
        val modifier = Modifier.padding(inset)
        when (page) {
            "home" -> HomeScreen(history, modifier, { importer.launch(arrayOf("*/*")) }, onSelect)
            "history" -> HistoryScreen(history, modifier, onSelect, vm::delete)
            "settings" -> SettingsScreen(vm, modifier)
            else -> {
                if (selected != null) DetailScreen(selected, modifier,
                    onCancel = { vm.cancel(selected) },
                    onRetry = { vm.retry(selected, onSelect) },
                    onInstall = { onInstall(selected) },
                    onShare = { onShare(selected) },
                    onSave = { saver.launch("ArabiFlow-${selected.packageName}.apk") },
                    onHome = { page = "home"; selectedId = null })
                else LaunchedEffect(Unit) { page = "home" }
            }
        }
    }
}
