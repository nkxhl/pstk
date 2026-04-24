# SmartQuiz ProGuard Rules
-keepattributes Signature
-keepattributes *Annotation*

# Gson
-keepattributes Signature
-keep class com.smartquiz.model.** { *; }
-keep class com.google.gson.** { *; }

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
