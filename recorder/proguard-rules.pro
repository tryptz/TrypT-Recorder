# Tryptify Recorder — R8 rules.
#
# Narrow on purpose (see docs/agent-playbook.md, "R8/ProGuard analysis"): the
# only reflective entry points this app owns are kotlinx.serialization's
# generated serializers for the editor's project model. Media3 ships its own
# consumer rules inside its AARs, so nothing here keeps it whole.

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class tf.monochrome.recorder.**$$serializer { *; }
-keepclassmembers class tf.monochrome.recorder.** {
    *** Companion;
}
-keepclasseswithmembers class tf.monochrome.recorder.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclasseswithmembernames class tf.monochrome.recorder.editor.export.NativeRecorderMixer {
    native <methods>;
}

# The liquid glass's live lens (ui/glass/LiveGlassLens.kt) reads Haze's
# internal "source is recording" flag by name, so a lens inside its own source
# skips it instead of looping (HazeContentDrawing). Same rule as Tryptify's.
-keepclassmembers class dev.chrisbanes.haze.HazeArea {
    boolean getContentDrawing$haze_release();
}
