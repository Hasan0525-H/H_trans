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
fun HomeScreen(history: List<Conversion>, modifier: Modifier,
               onImport: () -> Unit, onSelect: (String) -> Unit) {
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
                    Text("حوّل موارد تطبيقك إلى لغة عربية وواجهة RTL",
                        fontSize = 13.sp, color = Color.White.copy(alpha = .82f))
                    Spacer(Modifier.height(19.dp))
                    Button(onClick = onImport,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Gold, contentColor = Teal)) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(9.dp))
                        Text("استيراد APK", fontWeight = FontWeight.Bold)
                    }
                }
                Icon(Icons.Default.Inventory2, contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(19.dp).size(60.dp),
                    tint = Color.White.copy(alpha = .20f))
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
                Text("اضغط للاختيار أو اسحب ملف APK إلى التطبيق",
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
                Text(item.originalName, maxLines = 1, fontWeight = FontWeight.Bold)
                Text(item.packageName, maxLines = 1, fontSize = 11.sp, color = Muted)
                Text(when (item.status) {
                    "completed" -> "مكتمل • " + readableSize(item.resultBytes)
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
    var url by remember { mutableStateOf(vm.config.url) }
    var token by remember { mutableStateOf(vm.config.token) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)) {
        item {
            Text("إعدادات المعالجة", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black)
        }
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null,
                        tint = Mint, modifier = Modifier.size(30.dp))
                    Text("الخادم الخاص", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("تُرفع ملفات APK إلى هذا الخادم فقط عند بدء التحويل. استخدم اتصال HTTPS موثوقًا.",
                        color = Muted, fontSize = 12.sp)
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = { Text("عنوان HTTPS") },
                        placeholder = { Text("https://api.example.com") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = token, onValueChange = { token = it },
                        label = { Text("رمز الوصول") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.saveSettings(url, token) },
                        modifier = Modifier.fillMaxWidth()) { Text("حفظ الإعدادات") }
                    OutlinedButton(onClick = { vm.testConnection() },
                        modifier = Modifier.fillMaxWidth()) { Text("فحص الاتصال") }
                }
            }
        }
        item {
            Text("الخصوصية وحدود التوافق", fontWeight = FontWeight.Bold)
            Text("إعادة التوقيع تغير هوية الناشر. قد تتوقف تطبيقات محمية عن العمل بعد التعديل. لا يمكن ضمان تعريب نصوص WebView أو النصوص البرمجية الديناميكية.",
                color = Muted, fontSize = 13.sp)
        }
    }
}
