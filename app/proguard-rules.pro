# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.vyvegroup.searchengine.** { *; }
-keepclassmembers class com.vyvegroup.searchengine.** { *; }
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }
