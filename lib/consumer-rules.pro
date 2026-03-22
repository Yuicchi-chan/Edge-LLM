-keep class com.athera.higgins.ai.* { *; }
-keep class com.athera.higgins.ai.gguf.* { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class kotlin.Metadata { *; }
