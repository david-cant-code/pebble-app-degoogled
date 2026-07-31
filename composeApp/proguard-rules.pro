# Keep all classes in androidx.sqlite and their members
-keep class androidx.sqlite.** { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class io.rebble.libpebblecommon.locker.AppType { *; }

# Keep native methods and the classes that contain them, including their names and signatures
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Fork: health-kmp's Google Fit backend (com.viktormykhailiv.kmp.health.legacy)
# references GMS classes from play-services-auth/-fitness, which this fork
# excludes from the build (the app pins useGoogleFit=false, so the Fit path
# is unreachable; see PlatformHealthManager). R8 must tolerate the dangling
# references in that dead code. Scoped to exactly the packages the excluded
# artifacts provided, so any other GMS reference still fails the build loudly.
-dontwarn com.google.android.gms.auth.api.signin.**
-dontwarn com.google.android.gms.common.api.**
-dontwarn com.google.android.gms.fitness.**
-dontwarn com.google.android.gms.tasks.**
