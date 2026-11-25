# Application class initialization happens before MultiDexApplication.attachBaseContext.
-keep class com.shilapi.xcertplay.DiPlayApplication { *; }
-keep class com.shilapi.xcertplay.DiPlayApplication$* { *; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keep class androidx.multidex.** { *; }
-keep class com.shilapi.xcertplay.LoggingGate { *; }
-keep class com.shilapi.xcertplay.FloatingDotOverlay { *; }
# Guangqi volume hook: runs in system_server, which never installs the secondary dex files.
-keep class com.shilapi.xcertplay.orchestration.GuangqiVolumeHook { *; }
-keep class com.shilapi.xcertplay.orchestration.GuangqiVolumeHook$* { *; }
-keep class com.shilapi.xcertplay.orchestration.GuangqiVolumeHookReceiver { *; }
-keep class kotlin.jvm.internal.Intrinsics { *; }
