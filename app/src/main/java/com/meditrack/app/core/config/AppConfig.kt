package com.meditrack.app.core.config

object AppConfig {
    // Dose window
    const val DOSE_WINDOW_MINUTES = 60            // time after scheduled time before a dose becomes MISSED
    var userDoseWindowMinutes: Int? = null        // User customizable setting from Settings (Stage 12)
    var debugDoseWindowMinutes: Int? = null       // Fast-testing override for human/emulator verification
    val effectiveDoseWindowMinutes: Int
        get() = debugDoseWindowMinutes ?: userDoseWindowMinutes ?: DOSE_WINDOW_MINUTES
    val SNOOZE_PRESETS_MINUTES = listOf(15, 30)   // plus custom (list cannot be const)
    const val MAX_SNOOZES_PER_DOSE = 3
    const val MAX_SNOOZE_MINUTES = 240            // custom snooze upper bound
    const val EARLY_VERIFY_TOLERANCE_MINUTES = 30 // a dose may be verified this long before its scheduled time

    // Alarms: Android allows max ~500 alarms per app, so only the near future is armed;
    // AlarmMaintenanceWorker tops the horizon up regularly.
    const val ALARM_HORIZON_HOURS = 48
    const val ALARM_MAINTENANCE_INTERVAL_HOURS = 6L

    // Verification
    const val MAX_VERIFICATION_ATTEMPTS = 3
    const val LATE_DOSE_MAX_HOURS = 24            // late entry allowed until next scheduled dose OR this many hours, whichever is EARLIER

    // Image quality gate (tunable)
    const val QUALITY_ANALYSIS_MAX_SIDE_PX = 640
    const val BLUR_LAPLACIAN_VARIANCE_MIN = 100.0
    const val BRIGHTNESS_MEAN_MIN = 40.0          // 0..255
    const val BRIGHTNESS_MEAN_MAX = 220.0
    const val GLARE_PIXEL_FRACTION_MAX = 0.08     // fraction of near-white pixels

    // Detector
    const val DETECTOR_MIN_CONFIDENCE = 0.5f

    // Duplicate photo detection
    const val PHASH_MAX_HAMMING_DISTANCE = 5

    // Stock
    const val LOW_STOCK_DAYS_THRESHOLD = 3        // low when remaining pills < pillsPerDay * this
}
