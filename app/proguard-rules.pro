# Sherpa-ONNX Proguard Rules
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
-keepclasseswithmembernames class * {
    native <methods>;
}
