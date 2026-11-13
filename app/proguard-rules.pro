# Pure Video Player R8 Release Optimization, Log Stripping & Anti-Reverse-Engineering Rules
-ignorewarnings
-dontwarn org.videolan.**
-dontwarn androidx.media3.**
-dontwarn com.google.android.exoplayer2.**
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# Strip all Debug, Verbose, and Info logs in Release builds for security and smaller APK size
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep LibVLC & ExoPlayer/Media3 JNI engine classes, interfaces, and native callbacks intact
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }

# Keep JNI native methods
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep Android Manifest entry points & ViewBinding classes
-keep class com.pureplayer.video.PureVideoApp { *; }
-keep class com.pureplayer.video.MainActivity {
    public <init>();
}
-keep class com.pureplayer.video.PlayerActivity {
    public <init>();
}
-keep class com.pureplayer.video.databinding.** { *; }

# Keep Kotlin Coroutines internal service fields
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Enum constants & valueOf for IPTV & Settings persistence
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

# Keep Parcelable creators
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
