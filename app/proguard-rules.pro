# ProGuard rules for MediTrack

# Keep data classes and Room schemas
-keepclassmembers class * {
    @androidx.room.Entity *;
    @androidx.room.Dao *;
}
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# Keep TensorFlow Lite native and java classes
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Keep ML Kit text recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep Firebase Auth, Firestore, Storage, Functions, Messaging
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep Hilt / Dagger
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Keep domain entities, enums and verification pipeline models
-keep class com.meditrack.app.domain.model.** { *; }
-keep enum com.meditrack.app.domain.model.** { *; }
-keep class com.meditrack.app.feature.verification.pipeline.Cell* { *; }
-keep class com.meditrack.app.feature.verification.pipeline.Detection { *; }
-keep enum com.meditrack.app.feature.verification.pipeline.CellState { *; }
-keep enum com.meditrack.app.feature.verification.pipeline.RejectReason { *; }
-keep class com.meditrack.app.data.service.CloudDeletionResult { *; }
