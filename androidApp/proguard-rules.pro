# Stage 5 hardening item 4 (contracts/stage5-retention-hardening.md). Verification is a
# minified live-RPC smoke on device, not compile-time (a stripped $$serializer fails at
# runtime -- the Stage-2 "conflict envelopes could never decode" class).

# kotlinx-serialization: keep generated serializers for all @Serializable types.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$$serializer INSTANCE;
    static **$Companion Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** Companion;
}
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room 3 generated implementations + bundled SQLite driver JNI.
-keep class **_Impl { *; }
-keep class androidx.sqlite.driver.bundled.** { *; }

# Ktor/OkHttp service loaders and engines.
-keep class io.ktor.client.engine.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.slf4j.**

# Keep enough metadata for supabase-kt reflection-free operation; silence its optional deps.
-dontwarn io.github.jan.supabase.**
