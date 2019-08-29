-dontwarn android.test.**
-dontwarn androidx.test.**
-dontnote junit.framework.**
-dontnote junit.runner.**

# Uncomment this if you use Mockito
#-dontwarn org.mockito.**

-keep class org.hamcrest.** { *; }
-dontwarn org.hamcrest.**

-keep class org.junit.** { *; }
-dontwarn org.junit.**

-keep class junit.** { *; }
-dontwarn junit.**
