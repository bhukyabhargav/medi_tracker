# ProGuard rules for MediTrack

# Keep data classes and Room schemas
-keepclassmembers class * {
    @androidx.room.Entity *;
    @androidx.room.Dao *;
}

# Keep TensorFlow Lite native and java classes
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }

# Keep ML Kit text recognition
-keep class com.google.mlkit.** { *; }

# Keep Firebase Auth, Firestore, Storage, Functions, Messaging
-keep class com.google.firebase.** { *; }

# Keep domain entities and enums
-keep class com.meditrack.app.domain.model.** { *; }
-keep enum com.meditrack.app.domain.model.** { *; }
-keep class com.meditrack.app.feature.verification.pipeline.Cell* { *; }
-keep class com.meditrack.app.feature.verification.pipeline.Detection { *; }
