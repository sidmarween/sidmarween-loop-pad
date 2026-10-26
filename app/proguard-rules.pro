# Marv PAD – R8 darf optimieren, aber nichts aus der App entfernen oder umbenennen
# (lesbare Absturzberichte, keine Überraschungen durch Reflexion).
-dontobfuscate
-keep,allowoptimization class de.marween.looppad.** { *; }
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn org.jetbrains.annotations.**

# ONNX Runtime (JNI)
-keep class ai.onnxruntime.** { *; }
