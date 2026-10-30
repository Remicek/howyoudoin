# R8 rules for the release build (the libraries' own rules come with them).

# gomobile: Go's native code (libgojni) calls these Java classes and methods by name
-keep class go.** { *; }
-keep class hydbridge.** { *; }

# WebRTC (calls): its native library calls back into the Java classes by name
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }

# Optional logging and JVM-only classes some libraries mention but Android doesn't have
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
