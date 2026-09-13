# Keep Media3 Transformer's reflective codec/effect lookups intact.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# sherpa-onnx resolves its config and result classes from JNI by name, and its AAR ships an
# empty consumer proguard file. Without this the release build loads the model and then dies.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
