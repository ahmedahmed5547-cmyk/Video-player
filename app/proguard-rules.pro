# IPTV and Video player R8 Release Optimization, Log Stripping & Keep Rules
-dontwarn org.videolan.**
-dontwarn androidx.media3.**
-dontwarn com.google.android.exoplayer2.**
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# Strip Debug, Verbose, and Info logs in Release builds
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

# Keep Room Database generated implementations, Entities, Migrations, and DAOs
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.migration.Migration { *; }
-keep class com.pureplayer.video.ContentTypeCountTuple { *; }
-keep class com.pureplayer.video.CategorySummaryTuple { *; }

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

# Keep ViewModel subclasses instantiated via ViewModelProvider reflection
-keep class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
}
-keep class com.pureplayer.video.VideoLibraryViewModel { *; }
-keep class com.pureplayer.video.VlcSurfaceLayoutMath { *; }
-keep class com.pureplayer.video.VlcAspectMode { *; }

# Keep Jetpack WindowManager FoldingFeature & FoldableAdaptiveController
-dontwarn androidx.window.**
-keep class androidx.window.** { *; }
-keep class com.pureplayer.video.FoldableAdaptiveMath { *; }
-keep class com.pureplayer.video.FoldPosture { *; }

# Keep Media3 Playback Service, Android Auto, Audio Library & AudioFX
-keep class com.pureplayer.video.VexoPlaybackService { *; }
-keep class com.pureplayer.video.VexoPlaybackService$* { *; }
-keep class com.pureplayer.video.BackgroundPlaybackState { *; }
-keep class com.pureplayer.video.AndroidAutoManager { *; }
-keep class com.pureplayer.video.AndroidAutoUxPolicy { *; }
-keep class com.pureplayer.video.AndroidAutoConfig { *; }
-keep class com.pureplayer.video.AndroidAutoConnectionSnapshot { *; }
-keep class com.pureplayer.video.AndroidAutoDiagnosticReport { *; }
-keep class * extends androidx.media3.session.MediaLibraryService { *; }
-keep class * extends androidx.media3.session.MediaSessionService { *; }
-keep class * implements androidx.media3.session.MediaLibraryService$MediaLibrarySession$Callback { *; }
-keep class * implements androidx.media3.session.MediaSession$Callback { *; }
-keep class com.pureplayer.video.AudioLibraryViewModel { *; }
-keep class com.pureplayer.video.AudioPlaylistEntity { *; }
-keep class com.pureplayer.video.AudioPlaylistTrackEntity { *; }
