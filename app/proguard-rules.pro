# ChromeClone ProGuard rules (release build uses minifyEnabled=false, kept for completeness)
-keep class co.carryai.chromeclone.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
