# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class it.apexweather.**$$serializer { *; }
-keepclassmembers class it.apexweather.** { *** Companion; }
-keepclasseswithmembers class it.apexweather.** { kotlinx.serialization.KSerializer serializer(...); }
# Retrofit
-keepattributes Signature, Exceptions
# Line numbers in release stack traces. The crash files in filesDir/diagnostics are retraced
# against the mapping.txt each GitHub release carries; without these a retraced frame names the
# method and not the line. The source file name is collapsed to one string to keep it small.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# jhdf: its LZ4 filter names a library excluded in build.gradle.kts, reached only for a file
# compressed with it, which GeoSphere's are not. slf4j has no binding and logs nothing.
-dontwarn net.jpountz.**
# JhdfInfo reads its own package's version in a static initialiser; repackaged by R8 it has no
# package, the initialiser throws, and every file fails to open — silently, as a map with no forecast.
-keep class io.jhdf.JhdfInfo { *; }
-dontwarn org.slf4j.**
