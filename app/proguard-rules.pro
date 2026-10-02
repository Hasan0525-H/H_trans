# Preserve generated Room implementations referenced by Room's runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
-keepattributes *Annotation*,Signature
