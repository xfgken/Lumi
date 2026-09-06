# Lumi ProGuard 规则
# ---- Kotlin/Compose ----
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
# ---- Room (KSP 生成) ----
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.xfgken.Lumi.data.** { *; }
# ---- JNI / 原生库 ----
-keepclasseswithmembernames class * {
    native <methods>;
}
# ---- 官方 hysteria golib（gomobile AAR：golib 包 + Kotlin 封装）----
-keep class golib.** { *; }
-keep class ru.shapovalov.hysteria.** { *; }
-dontwarn golib.**
-dontwarn ru.shapovalov.hysteria.**
# ---- kotlinx.serialization 生成类 ----
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ru.shapovalov.hysteria.**$$serializer { *; }
-keepclassmembers class ru.shapovalov.hysteria.** { *** Companion; }
-keepclasseswithmembers class ru.shapovalov.hysteria.** { kotlinx.serialization.KSerializer serializer(...); }