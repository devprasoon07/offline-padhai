# Offline PadhAI — ProGuard / R8 keep rules (release builds)
# Conservative: native libs aur reflection-heavy code ko chhodo,
# baaki app code obfuscate hone do.

# Entry point
-keep class com.devprasoon.offlinepadhai.MainActivity { *; }

# MediaPipe — native libs + JNI
-keep class com.google.mediapipe.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# CameraX
-keep class androidx.camera.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.android.** { *; }

# Libraries ko chahiye attributes
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
