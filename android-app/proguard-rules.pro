# ── SecondLife ProGuard / R8 rules ───────────────────────────────────────────

# ── LiteRT / LiteRT-LM (JNI bridge — class names must not be renamed) ────────
-keep class com.google.mediapipe.** { *; }
-keep class com.google.litert.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * {
    @com.google.mediapipe.framework.* <methods>;
}
# Keep JNI-callable methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Google Nearby Connections ─────────────────────────────────────────────────
-keep class com.google.android.gms.nearby.** { *; }
-dontwarn com.google.android.gms.nearby.**

# ── Google Play Services (location, tasks) ───────────────────────────────────
-keep class com.google.android.gms.location.** { *; }
-keep class com.google.android.gms.tasks.** { *; }

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── Jetpack Compose ───────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ── CameraX ───────────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── SecondLife data classes (used for JSON / mesh encoding) ──────────────────
-keep class com.secondlife.mesh.MeshManager$EmergencyBroadcast { *; }
-keep class com.secondlife.mesh.MeshManager$SessionContext { *; }
-keep class com.secondlife.inference.SecondLifeResponse { *; }
-keep class com.secondlife.inference.EmergencySession { *; }
-keep class com.secondlife.inference.TranscriptTurn { *; }

# ── Keep Application / Activity / Service entry points ───────────────────────
-keep class com.secondlife.SecondLifeApplication { *; }
-keep class com.secondlife.MainActivity { *; }
-keep class com.secondlife.mesh.MeshService { *; }

# ── Suppress warnings for optional native libraries ──────────────────────────
-dontwarn libvndksupport.**
-dontwarn libOpenCL.**
-dontwarn sun.misc.Unsafe
