# ProGuard rules for MediTrack

# Keep Room schemas, DAOs, entities, and database classes
-keepclassmembers class * {
    @androidx.room.Entity *;
    @androidx.room.Dao *;
}
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# SQLCipher (Zetetic package) - Fix A5
-keep class net.zetetic.database.sqlcipher.** { *; }
-keepclassmembers class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }
-keepclassmembers class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**

# SQLCipher JNI callbacks
-keepclasseswithmembernames class * {
    native <methods>;
}

# TensorFlow Lite native and java classes
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ML Kit text recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Firebase Auth, Firestore, Storage, Functions, Messaging
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keepclassmembers class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Domain entities, enums and verification pipeline models
-keep class com.meditrack.app.domain.model.** { *; }
-keep enum com.meditrack.app.domain.model.** { *; }
-keep class com.meditrack.app.feature.verification.pipeline.Cell* { *; }
-keep class com.meditrack.app.feature.verification.pipeline.Detection { *; }
-keep enum com.meditrack.app.feature.verification.pipeline.CellState { *; }
-keep enum com.meditrack.app.feature.verification.pipeline.RejectReason { *; }
-keep class com.meditrack.app.data.service.CloudDeletionResult { *; }
