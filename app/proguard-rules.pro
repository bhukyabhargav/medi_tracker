# ProGuard rules for MediTrack
# Keep data classes and Room schemas
-keepclassmembers class * {
    @androidx.room.Entity *;
    @androidx.room.Dao *;
}
