# Drops only library code nothing refers to, so a Dalvik unit has far less to optimize on its first
# start. Names and behaviour stay as compiled. R8's outliner is turned off in gradle.properties, since
# its shared synthetic classes can land outside the main dex that MultiDex itself runs from.
-dontobfuscate
-dontoptimize
-keepattributes *
-keep class com.shilapi.** { *; }
-keep class com.thundersoft.** { *; }
# Looked up by name: native code, security providers, mDNS and its logging, main-process startup.
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.jce.** { *; }
-keep class org.bouncycastle.jcajce.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
-keep class org.bouncycastle.util.** { *; }
-keep class org.bouncycastle.x509.** { *; }
-keep class javax.jmdns.** { *; }
-keep class org.slf4j.** { *; }
-keep class androidx.multidex.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Optional parts of libraries that were never on this classpath.
-dontwarn **
