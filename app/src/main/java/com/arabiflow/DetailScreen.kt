package com.arabiflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arabiflow.core.Stage
import com.arabiflow.data.Conversion
import org.json.JSONObject

private val Accent = Color(0xFF15B69C)

@Composable
fun DetailScreen(item: Conversion, modifier: Modifier,
                 onCancel: () -> Unit, onRetry: () -> Unit,
                 onAnalyze: () -> Unit, onStartConversion: () -> Unit,
                 onConfigure: () -> Unit, serverConfigured: Boolean,
                 onInstall: () -> Unit, onShare: () -> Unit,
                 onSave: () -> Unit, onHome: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(when(item.status) {
                "completed" -> "اكتمل التحويل"
                "analyzed" -> "نتيجة التحليل المحلي"
                else -> "حالة التحويل"
            },
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(item.originalName, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (item.status) {
            "analyzed" -> {
                item {
                    val data = runCatching { JSONObject(item.report) }.getOrNull()
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("تحليل APK على جهازك", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            InfoLine("التطبيق", item.appLabel.ifBlank { item.originalName })
                            InfoLine("اسم الحزمة", item.packageName)
                            InfoLine("الإصدار", item.version)
                            InfoLine("حجم APK", readableSize(item.originalBytes))
                            InfoLine("عناصر الأرشيف", data?.optInt("entries")?.toString() ?: "—")
                            InfoLine("ملفات DEX", data?.optInt("dex_files")?.toString() ?: "—")
                            InfoLine("ملفات الموارد", data?.optInt("resource_files")?.toString() ?: "—")
                            InfoLine("مرشحات XML", data?.optInt("xml_path_candidates")?.toString() ?: "—")
                            InfoLine("مرشحات تخطيط الواجهة", data?.optInt("layout_path_candidates")?.toString() ?: "—")
                            InfoLine("الأصول (Assets)", data?.optInt("assets")?.toString() ?: "—")
                            val abis = data?.optJSONArray("abi_names")
                            InfoLine("معماريات المكتبات", if (abis == null || abis.length() == 0)
                                "غير ظاهرة" else (0 until abis.length()).joinToString("، ") { abis.optString(it) })
                            val locales = data?.optJSONArray("locale_path_hints")
                            InfoLine("مؤشرات اللغات", if (locales == null || locales.length() == 0)
                                "لا تظهر في مسارات ZIP" else (0 until locales.length()).joinToString("، ") { locales.optString(it) })
                        }
                    }
                }
                item {
                    Text("التحويل على هاتفك باستخدام ML Kit. أول استخدام يحتاج إلى Wi-Fi لتنزيل نموذج اللغة مجانًا، وبعض النصوص ستحتاج مراجعة.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onStartConversion, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Translate, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("بدء التعريب على الهاتف")
                    }

                }
            }
            "completed" -> {
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = Accent.copy(alpha = .12f))) {
                        Column(Modifier.fillMaxWidth().padding(25.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                modifier = Modifier.size(60.dp), tint = Accent)
                            Spacer(Modifier.height(10.dp))
                            Text("تم التعريب الجزئي", fontWeight = FontWeight.Black, fontSize = 25.sp)
                            Text("راجع حدود التغطية قبل التثبيت",
                                textAlign = TextAlign.Center, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    val report = runCatching { JSONObject(item.report) }.getOrNull()
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(19.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoLine("اسم التطبيق", item.appLabel.ifBlank { item.originalName })
                            InfoLine("اسم الملف", item.originalName)
                            InfoLine("بصمة توقيع المصدر", item.signingCertificateSha256.ifBlank { "غير متاحة" })
                            InfoLine("الحزمة", item.packageName)
                            InfoLine("الإصدار الأصلي", item.version)
                            InfoLine("الحجم الأصلي", readableSize(item.originalBytes))
                            InfoLine("حجم النسخة العربية", readableSize(item.resultBytes))
                            InfoLine("مدة المعالجة", "${item.elapsedSeconds} ثانية")
                            InfoLine("سلاسل مترجمة",
                                report?.optInt("translated_strings")?.toString() ?: "—")
                            InfoLine("نصوص التخطيط",
                                report?.optInt("hardcoded_xml_strings")?.toString() ?: "—")
                            InfoLine("أسماء موارد لم تُترجم", report?.optInt("untranslated_resource_names")?.toString() ?: "—")
                            InfoLine("تخطيطات RTL",
                                report?.optInt("rtl_layouts")?.toString() ?: "—")
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.InstallMobile, contentDescription = null)
                            Spacer(Modifier.width(8.dp)); Text("تثبيت APK")
                        }
                        OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null)
                            Spacer(Modifier.width(8.dp)); Text("حفظ APK")
                        }
                        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp)); Text("مشاركة APK")
                        }
                    }
                }
                item {
                    val report = runCatching { JSONObject(item.report) }.getOrNull()
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(18.dp)) {
                            Text("تقرير الحدود والتنبيهات", fontWeight = FontWeight.Bold)
                            listOf("warnings", "limitations").forEach { field ->
                                val array = report?.optJSONArray(field)
                                if (array != null) for (i in 0 until array.length()) {
                                    Spacer(Modifier.height(9.dp))
                                    Text("• " + array.optString(i), fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            "failed", "cancelled" -> {
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text(if (item.status == "failed") "تعذّر التحويل" else "تم إلغاء العملية",
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(9.dp))
                            Text(item.error.ifBlank { "لم تكتمل العملية" })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onAnalyze, modifier = Modifier.fillMaxWidth()) {
                        Text("تحليل الملف على الجهاز دون خادم")
                    }
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("إعادة المحاولة محليًا")
                    }
                }
            }
            else -> {
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(20.dp)) {
                            Text(if (item.status == "queued") "بانتظار المعالجة المحلية"
                                 else item.stage,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Text("${item.progress}%", fontSize = 41.sp,
                                fontWeight = FontWeight.Black)
                            LinearProgressIndicator(
                                progress = { item.progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(9.dp),
                                color = Accent)
                            Spacer(Modifier.height(12.dp))
                            Text(item.stage, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp)
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Stage.entries.forEach { stage ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                                Icon(if (item.progress >= stage.percent)
                                    Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (item.progress >= stage.percent) Accent
                                           else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${stage.percent}%  ${stage.titleAr}")
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("إلغاء المعالجة")
                    }
                }
            }
        }
        item {
            TextButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
                Text("العودة للرئيسية / تحويل تطبيق آخر")
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(.42f),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, modifier = Modifier.weight(.58f),
            fontWeight = FontWeight.Medium, fontSize = 13.sp, textAlign = TextAlign.End)
    }
    HorizontalDivider()
}
