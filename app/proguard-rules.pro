# R8 rules for the release build. Libraries (Room, WorkManager, Media3, OkHttp, kotlinx.serialization) ship their
# own consumer rules; these cover what is specific to this app.

# kotlinx.serialization: keep generated serializers of our @Serializable classes (core gloss/backup models).
-keepclassmembers @kotlinx.serialization.Serializable class net.awkay.spanishreader.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.awkay.spanishreader.**$$serializer { *; }

# Readable stack traces in bug reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# NewPipe Extractor (YouTube audio download), following the rules NewPipe's own release build uses.
# Rhino runs YouTube's player JavaScript (signature / n-parameter deobfuscation) and reaches its classes reflectively.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
# Date phrases ("hace 3 días") are loaded by class name per language.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# NewPipe ships its app unobfuscated; keep the extractor's names too (shrinking still applies).
-keepnames class org.schabi.newpipe.extractor.** { *; }
# protobuf-lite (YouTube's request/response messages) reads message fields reflectively.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.re2j.**
