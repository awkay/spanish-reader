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
