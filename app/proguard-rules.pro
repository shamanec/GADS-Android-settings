# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Keep RemoteControlServer for app_process execution
-keep class com.gads.settings.RemoteControlServerKt { *; }
-keep class com.gads.settings.RemoteControlServer { *; }
-keep class com.gads.settings.InputInjector { *; }

# Keep ScrcpyH264Server for app_process execution
-keep class com.gads.settings.server.H264ServerKt {
    public static void main(java.lang.String[]);
}
-keep class com.gads.settings.server.H264Server { *; }
-keep class com.gads.settings.server.** { *; }

# Keep reflection targets (hidden Android APIs)
-keep class android.os.ServiceManager { *; }
-keep class android.hardware.display.DisplayManagerGlobal { *; }
-keep class android.hardware.display.IDisplayManager { *; }
-keep class android.hardware.display.IDisplayManager$Stub { *; }
-keep class android.view.SurfaceControl { *; }
-keep class android.media.projection.IMediaProjection { *; }
-keep class android.hardware.display.IVirtualDisplayCallback { *; }