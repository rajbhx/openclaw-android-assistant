# ── Compose ──────────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-keep class kotlin.Metadata { *; }

# ── Material 3 ──────────────────────────────────────────────────────────
-keep class com.google.android.material.** { *; }

# ── AndroidX ────────────────────────────────────────────────────────────
-keep class androidx.core.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.webkit.** { *; }
-keep class androidx.security.** { *; }

# ── Kotlin Coroutines ───────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Keep data classes for serialization ──────────────────────────────────
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# ── R8 full mode optimizations ──────────────────────────────────────────
-allowaccessmodification
-repackageclasses ''
-optimizations !code/simplification/arithmetic,!field/*,!method/inlining/*

# ── WebView ──────────────────────────────────────────────────────────────
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
