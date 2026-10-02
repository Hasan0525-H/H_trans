package com.arabiflow

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arabiflow.data.Conversion
import java.text.DecimalFormat

private val Teal = Color(0xFF104450)
private val Mint = Color(0xFF1AB59E)
private val Gold = Color(0xFFF4CE8A)
private val Muted = Color(0xFF72838B)

fun readableSize(size: Long): String = when {
    size >= 1073741824L -> DecimalFormat("#,##0.0").format(size / 1073741824.0) + " GB"
    size >= 1048576L -> DecimalFormat("#,##0.0").format(size / 1048576.0) + " MB"
    else -> DecimalFormat("#,##0").format(size / 1024.0) + " KB"
}

@Composable
fun HomeScreen(history: List<Conversion>, connection: ServerConnection, modifier: Modifier,
               onImport: () -> Unit, onSelect: (String) -> Unit,
               onConfigure: () -> Unit, onCheckConnection: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(238.dp)
                .clip(RoundedCornerShape(29.dp))
                .background(Brush.linearGradient(listOf(Teal, Color(0xFF127684))))) {
                Column(Modifier.align(Alignment.CenterStart).padding(23.dp)) {
                    Text("اللغة العربية،", fontSize = 17.sp,
                        color = Color(0xFFC0F9F0), fontWeight = FontWeight.Bold)
                    Text("بلا حدود", fontSize = 34.sp, color = Color.White,
                        fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Text("افحص APK محليًا ثم ابدأ التعريب عند تجهيز الخادم",
                        fontSize = 13.sp, color = Color.White.copy(alpha = .82f))
                    Spacer(Modifier.height(19.dp))
                    Button(onClick = onImport,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Gold, contentColor = Teal)) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(9.dp))
                        Text("فحص APK محليًا", fontWeight = FontWeight.Bold)
                    }
                }
                Icon(Icons.Default.Inventory2, contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(19.dp).size(60.dp),
                    tint = Color.White.copy(alpha = .20f))
            }
        }
        if (connection.phase != ServerPhase.READY) item {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = Mint)
                        Text("تجهيز التعريب", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (connection.phase == ServerPhase.UNCONFIGURED)
                            "يمكنك فحص APK دون اتصال بالخادم. لتفعيل التعريب وإعادة بناء الملف، جهّز خادم معالجة."
                        else connection.detail,
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = if (connection.phase == ServerPhase.UNCONFIGURED)
                        onConfigure else onCheckConnection,
                        enabled = connection.phase != ServerPhase.CHECKING,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(when (connection.phase) {
                            ServerPhase.UNCONFIGURED -> "إعداد الخادم"
                            ServerPhase.CHECKING -> "جارٍ فحص الاتصال..."
                            else -> "فحص الاتصال"
                        })
                    }
                    if (connection.phase != ServerPhase.UNCONFIGURED) {
                        TextButton(onClick = onConfigure,
                            modifier = Modifier.fillMaxWidth()) { Text("تعديل الإعدادات") }
                    }
                }
            }
        }
        item {
            Text("مساحة العمل", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard("التحويلات", history.size.toString(),
                    Icons.Default.AutoAwesome, Modifier.weight(1f))
                SummaryCard("حجم الملفات", readableSize(
                    history.sumOf { it.originalBytes + it.resultBytes }),
                    Icons.Default.Storage, Modifier.weight(1f))
            }
        }
        item {
            Column(Modifier.fillMaxWidth()
                .border(1.dp, Mint.copy(alpha = .6f), RoundedCornerShape(23.dp))
                .clip(RoundedCornerShape(23.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onImport).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CloudUpload, contentDescription = null,
                    tint = Mint, modifier = Modifier.size(39.dp))
                Spacer(Modifier.height(9.dp))
                Text("اختر APK لتحليله على جهازك، دون رفع الملف",
                    textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                Text("APK أساسي • حتى 512 ميجابايت", fontSize = 12.sp, color = Muted)
            }
        }
        item {
            Text("آخر العمليات", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
        }
        if (history.isEmpty()) item {
            EmptyState("لا توجد عمليات بعد",
                "ستظهر هنا عمليات التعريب بمجرد استيراد أول تطبيق.")
        } else items(history.take(4), key = { it.id }) { item ->
            ConversionCard(item, { onSelect(item.id) })
        }
        item {
            Text("الملفات تُرفع فقط إلى خادمك المحدد. عالج التطبيقات التي لديك إذن بتعديلها.",
                fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String,
                        icon: androidx.compose.ui.graphics.vector.ImageVector,
                        modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, contentDescription = null, tint = Mint)
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Black)
            Text(label, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
fun ConversionCard(item: Conversion, onClick: () -> Unit,
                   onDelete: (() -> Unit)? = null) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp))
                .background(Mint.copy(alpha = .12f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Android, contentDescription = null, tint = Mint)
            }
            Column(Modifier.weight(1f)) {
                Text(item.appLabel.ifBlank { item.originalName }, maxLines = 1, fontWeight = FontWeight.Bold)
                Text(item.packageName, maxLines = 1, fontSize = 11.sp, color = Muted)
                Text(when (item.status) {
                    "completed" -> "مكتمل • " + readableSize(item.resultBytes)
                    "analyzed" -> "تم التحليل محليًا"
                    "failed" -> "فشل التحويل"
                    "cancelled" -> "ألغي التحويل"
                    else -> "قيد المعالجة • ${item.progress}%"
                }, fontSize = 12.sp,
                    color = if (item.status == "failed")
                        MaterialTheme.colorScheme.error else Mint)
            }
            if (onDelete != null && item.status !in listOf("queued", "running")) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "حذف العملية")
                }
            } else Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Muted)
        }
    }
}

@Composable
fun HistoryScreen(history: List<Conversion>, modifier: Modifier,
                  onSelect: (String) -> Unit, onDelete: (Conversion) -> Unit) {
    var pending by remember { mutableStateOf<Conversion?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("سجل العمليات", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black)
        }
        if (history.isEmpty()) item {
            EmptyState("السجل فارغ", "لم تُحوّل أي تطبيق حتى الآن.")
        } else items(history, key = { it.id }) { item ->
            ConversionCard(item, onClick = { onSelect(item.id) },
                onDelete = { pending = item })
        }
    }
    pending?.let { target ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text("حذف العملية؟") },
            text = { Text("سيُحذف السجل والنسخة الناتجة إن وُجدت.") },
            confirmButton = {
                TextButton(onClick = { onDelete(target); pending = null }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("إلغاء") }
            })
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Inventory2, contentDescription = null, tint = Mint,
            modifier = Modifier.size(50.dp))
        Spacer(Modifier.height(11.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(subtitle, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SettingsScreen(vm: ConversionViewModel, modifier: Modifier) {
    // This is the owner-deployed HTTPS origin only; never embed bearer credentials in the APK.
    var address by remember { mutableStateOf("http://127.0.0.1:8000") }
    var secret by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    val connection by vm.connection.collectAsState()
    val browser = LocalUriHandler.current
    val registered = remember(revision) { vm.registeredServers() }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)) {
        item {
            Text("الاتصال التلقائي", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black)
            Spacer(Modifier.height(7.dp))
            Text("لا تحتاج إلى اشتراك: يمكن ربط التطبيق بكمبيوترك عبر USB وخادم محلي مفتوح المصدر، أو اختيار HTTPS لخادم تملكه.",
                fontSize = 13.sp, color = Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = Mint,
                        modifier = Modifier.size(31.dp))
                    Text("حالة الاتصال", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(connection.detail,
                        color = if (connection.phase == ServerPhase.ERROR)
                            MaterialTheme.colorScheme.error else Mint, fontSize = 13.sp)
                    Button(onClick = vm::testConnection,
                        enabled = connection.phase != ServerPhase.CHECKING && registered.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (connection.phase == ServerPhase.CHECKING)
                            "جاري اختيار الخادم" else "اتصال تلقائي")
                    }
                }
            }
        }
        item {
            Text("خوادمك الموثوقة", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (registered.isEmpty()) {
                Text("لم يُربط أي خادم بعد. الاستيراد والفحص المحلي يعملان دون خادم.",
                    color = Muted, fontSize = 13.sp)
            } else {
                registered.forEach { endpoint ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = Mint)
                        Column(Modifier.weight(1f)) {
                            Text(endpoint.url, maxLines = 1, fontSize = 12.sp)
                            if (connection.phase == ServerPhase.READY &&
                                endpoint.url == vm.config.url) {
                                Text("الخادم النشط", color = Mint, fontSize = 11.sp)
                            }
                        }
                        IconButton(onClick = { pendingRemoval = endpoint.url }) {
                            Icon(Icons.Default.DeleteOutline,
                                contentDescription = "إزالة الخادم المسجل")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("إضافة خادم موثوق", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("للتشغيل دون اشتراكات: شغّل حزمة Docker المجانية على كمبيوترك، وصِل الهاتف USB وشغّل adb reverse tcp:8000 tcp:8000 ثم أدخل رمز الوصول الذي أنشأه السكربت. لا تستخدم Railway إن كنت تشترط صفر رسوم.",
                        color = Muted, fontSize = 12.sp)
                    OutlinedTextField(value = address, onValueChange = { address = it },
                        label = { Text("HTTPS أو الاتصال المحلي USB") },
                        placeholder = { Text("https://your-server.example") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = secret, onValueChange = { secret = it },
                        label = { Text("رمز وصول هذا الخادم") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (com.arabiflow.data.ServerConfig.validateOrigin(address.trim().trimEnd('/')) &&
                            secret.isNotBlank()) {
                            vm.saveSettings(address, secret)
                            address = "http://127.0.0.1:8000"
                            secret = ""
                            revision++
                        } else {
                            vm.saveSettings(address, secret) // display validation error
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("حفظ الخادم وفحصه") }
                    TextButton(onClick = {
                        browser.openUri("https://github.com/Hasan0525-H/H_trans/blob/main/backend/README.md")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("إرشادات نشر خادمك")
                    }
                }
            }
        }
        item {
            Text("الخصوصية", fontWeight = FontWeight.Bold)
            Text("لا يتصل التطبيق بخوادم عامة عشوائية ولا يرفع أي APK أثناء الفحص. التبديل التلقائي يحدث قبل الرفع فقط، وبعد بدء المهمة يبقى الملف على الخادم الذي قبل العملية.",
                fontSize = 12.sp, color = Muted)
        }
    }
    pendingRemoval?.let { origin ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("إزالة الخادم؟") },
            text = { Text("لن يتصل التطبيق تلقائيًا بهذا العنوان بعد الإزالة. لن تُحذف الملفات من الخادم نفسه.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeServer(origin)
                    revision++
                    pendingRemoval = null
                }) { Text("إزالة") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("إلغاء") }
            }
        )
    }
}
