package com.arabiflow.core

enum class Stage(val percent: Int, val titleAr: String) {
    PREPARING(0, "تجهيز الحزمة"),
    EXTRACTION(15, "استخراج الموارد"),
    ANALYSIS(30, "تحليل التطبيق"),
    TRANSLATION(50, "الترجمة إلى العربية"),
    RTL(70, "تطبيق اتجاه RTL"),
    REBUILD(85, "إعادة بناء APK"),
    SIGN(95, "التوقيع والتحقق"),
    COMPLETED(100, "مكتمل");
    companion object {
        fun fromProgress(progress: Int) = entries.last { progress >= it.percent }
    }
}
