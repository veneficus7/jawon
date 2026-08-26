# R8 rules for the release build.
#
# Most of this file exists for one reason: Filament is a native renderer with a thin Java
# veneer, and its JNI layer resolves Java fields and methods BY NAME at runtime. R8 renames
# by default. The failure mode is not a crash at startup — it is settings that silently stop
# applying, or a native abort deep into a frame, which is about the worst kind of bug to ship.

# ---------------------------------------------------------------------------
# Filament
# ---------------------------------------------------------------------------
# The option structs are the sharp edge. `View.setDepthOfFieldOptions` and friends hand their
# fields to native `nSet…Options` calls, and other paths read them back through JNI field IDs
# looked up by name. Rename `cocScale` and the depth of field quietly runs on garbage.
#
# Keeping the whole package is deliberate rather than lazy: the boundary between "reached
# from Kotlin" and "reached from C++" is not visible to R8, and getting it wrong costs a
# native crash rather than a compile error.
-keep class com.google.android.filament.** { *; }
-keep interface com.google.android.filament.** { *; }
-keepclassmembers class com.google.android.filament.** { *; }

# gltfio parses the .glb files and builds materials reflectively.
-keep class com.google.android.filament.gltfio.** { *; }
-keep class com.google.android.filament.utils.** { *; }

# Native methods, everywhere. R8 will not find the C++ side to rename it to match.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# SceneView
# ---------------------------------------------------------------------------
# Sits directly on Filament and hands it callbacks; several of its node types are
# instantiated through Filament's own machinery rather than from Kotlin.
-keep class io.github.sceneview.** { *; }
-dontwarn io.github.sceneview.**

# ---------------------------------------------------------------------------
# Media3 / ExoPlayer
# ---------------------------------------------------------------------------
# Decoders, renderers and extractors are chosen by reflection from the media type, so the
# ones this app never names in Kotlin still have to survive.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# The playback service is resolved by name from the manifest and by MediaController.
-keep class com.jawon.app.playback.** { *; }

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
# Entities are mapped column-by-column by generated code, but the generated code is itself
# looked up by name, and @Entity fields are matched to columns by name.
-keep class com.jawon.app.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Hilt / Dagger
# ---------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# ---------------------------------------------------------------------------
# Kotlin
# ---------------------------------------------------------------------------
# Coroutines' debug agent and the ServiceLoader entries it looks up.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Keep the metadata Kotlin reflection needs for data classes crossing serialisation-ish paths.
-keep class kotlin.Metadata { *; }

# ---------------------------------------------------------------------------
# Diagnostics
# ---------------------------------------------------------------------------
# Line numbers in release stack traces, without leaking original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Annotations R8 would otherwise strip that the above rules and the frameworks rely on.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
