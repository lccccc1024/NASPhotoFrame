-keep class com.hierynomus.smbj.** { *; }
-keep class com.bumptech.glide.** { *; }
-keep class com.awxkee.avif.** { *; }
-keep class com.nasframe.app.** { *; }

-dontwarn com.hierynomus.smbj.**
-dontwarn com.bumptech.glide.**
-dontwarn com.awxkee.avif.**
-dontwarn org.jetbrains.kotlin.**
-dontwarn javax.el.**
-dontwarn net.engio.mbassy.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

-keepclassmembers class * implements java.io.Closeable {
    public void close();
}