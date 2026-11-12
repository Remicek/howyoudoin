# R8 rules for the release build (the libraries' own rules come with them).

# gomobile: Go's native code (libgojni) calls these Java classes and methods by name
-keep class go.** { *; }
-keep class com.devvalor.howyoudoing.bridge.hydbridge.** { *; }

# WebRTC (calls): its native library calls back into the Java classes by name
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }

# Room databases are created by reflection through their no-argument constructor (WorkManager's WorkDatabase_Impl
# on every launch). Room 2.6's own rule keeps the classes but not their constructors, so R8 removed it and the
# release build crashed on start (NoSuchMethodException: WorkDatabase_Impl.<init>)
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Optional logging and JVM-only classes some libraries mention but Android doesn't have
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# Release builds print nothing to logcat: every println (HTTP errors, the on-device clients' state lines) goes
-assumenosideeffects class kotlin.io.ConsoleKt {
    public static void println(...);
    public static void print(...);
}
