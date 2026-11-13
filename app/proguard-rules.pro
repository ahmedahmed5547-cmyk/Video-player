# Pure Video Player R8 Optimization & Protection Rules
-ignorewarnings
-dontwarn org.videolan.**
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# Keep LibVLC JNI engine classes, interfaces, and native callbacks intact
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }

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
