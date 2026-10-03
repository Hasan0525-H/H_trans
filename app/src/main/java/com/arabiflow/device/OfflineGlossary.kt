package com.arabiflow.device

import java.util.Locale

/** Small deterministic, completely offline starter glossary. Not a neural translator. */
object OfflineGlossary {
    private val phrases = mapOf(
        "welcome" to "مرحبًا", "hello" to "مرحبًا", "home" to "الرئيسية",
        "settings" to "الإعدادات", "about" to "حول", "help" to "المساعدة",
        "search" to "بحث", "save" to "حفظ", "cancel" to "إلغاء",
        "close" to "إغلاق", "ok" to "موافق", "yes" to "نعم", "no" to "لا",
        "next" to "التالي", "back" to "رجوع", "continue" to "متابعة",
        "submit" to "إرسال", "login" to "تسجيل الدخول", "log in" to "تسجيل الدخول",
        "sign in" to "تسجيل الدخول", "logout" to "تسجيل الخروج",
        "sign out" to "تسجيل الخروج", "register" to "التسجيل",
        "name" to "الاسم", "email" to "البريد الإلكتروني",
        "password" to "كلمة المرور", "username" to "اسم المستخدم",
        "phone" to "الهاتف", "contact" to "التواصل", "send" to "إرسال",
        "share" to "مشاركة", "delete" to "حذف", "remove" to "إزالة",
        "edit" to "تعديل", "update" to "تحديث", "install" to "تثبيت",
        "download" to "تنزيل", "upload" to "رفع", "language" to "اللغة",
        "notification" to "إشعار", "notifications" to "الإشعارات",
        "profile" to "الملف الشخصي", "account" to "الحساب",
        "privacy" to "الخصوصية", "security" to "الأمان",
        "information" to "معلومات", "confirm" to "تأكيد",
        "loading" to "جارٍ التحميل", "error" to "خطأ", "retry" to "إعادة المحاولة",
        "done" to "تم", "finish" to "إنهاء", "completed" to "مكتمل",
        "start" to "بدء", "stop" to "إيقاف", "pause" to "إيقاف مؤقت",
        "play" to "تشغيل", "open" to "فتح", "menu" to "القائمة",
        "more" to "المزيد", "history" to "السجل", "recent" to "الأخيرة",
        "favorites" to "المفضلة", "favorite" to "مفضلة",
        "description" to "الوصف", "details" to "التفاصيل",
        "address" to "العنوان", "city" to "المدينة",
        "country" to "الدولة", "date" to "التاريخ", "time" to "الوقت",
        "status" to "الحالة", "connection" to "الاتصال",
        "connected" to "متصل", "disconnected" to "غير متصل",
        "file" to "ملف", "files" to "الملفات",
        "image" to "صورة", "images" to "الصور",
        "camera" to "الكاميرا", "gallery" to "المعرض",
        "allow" to "سماح", "deny" to "رفض", "enabled" to "مفعّل",
        "disabled" to "معطّل", "on" to "تشغيل", "off" to "إيقاف",
        "welcome back" to "مرحبًا بعودتك",
        "get started" to "ابدأ الآن", "try again" to "حاول مرة أخرى",
        "no results" to "لا توجد نتائج", "no internet connection" to "لا يوجد اتصال بالإنترنت",
        "something went wrong" to "حدث خطأ ما",
        "enter password" to "أدخل كلمة المرور",
        "enter email" to "أدخل البريد الإلكتروني",
        "français" to "الفرنسية", "configuración" to "الإعدادات",
        "inicio" to "الرئيسية", "guardar" to "حفظ", "annuler" to "إلغاء",
        "paramètres" to "الإعدادات", "accueil" to "الرئيسية",
        "你好" to "مرحبًا", "设置" to "الإعدادات"
    )
    private val welcomeFormat = Regex("^welcome[,!]?\\s+(%(?:\\d+\\$)?s)$", RegexOption.IGNORE_CASE)

    /** Return null for unsupported phrases: never invent an Arabic translation. */
    fun translate(source: String): String? {
        val core = source.trim()
        if (core.isEmpty() || core.startsWith("@") || core.startsWith("?") ||
            core.contains('<') || core.contains('>') || core.contains('{') ||
            core.contains('}') || core.contains("\\n") || core.contains("\\t")) return null
        val normalized = core.lowercase(Locale.ROOT)
        val translated = phrases[normalized] ?: welcomeFormat.matchEntire(core)?.let {
            "مرحبًا، " + it.groupValues[1]
        } ?: return null
        val prefix = source.takeWhile { it.isWhitespace() }
        val suffix = source.takeLastWhile { it.isWhitespace() }
        return prefix + translated + suffix
    }
}
