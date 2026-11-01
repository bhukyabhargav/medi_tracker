package com.meditrack.app

import com.meditrack.app.core.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun appConfig_doseWindowAndSnoozeDefaults_areCorrect() {
        assertEquals(60, AppConfig.DOSE_WINDOW_MINUTES)
        assertEquals(listOf(15, 30), AppConfig.SNOOZE_PRESETS_MINUTES)
        assertEquals(3, AppConfig.MAX_SNOOZES_PER_DOSE)
    }

    @Test
    fun appConfig_verificationLimits_areCorrect() {
        assertEquals(3, AppConfig.MAX_VERIFICATION_ATTEMPTS)
        assertEquals(24, AppConfig.LATE_DOSE_MAX_HOURS)
    }

    @Test
    fun appConfig_qualityGateAndDetector_areCorrect() {
        assertEquals(640, AppConfig.QUALITY_ANALYSIS_MAX_SIDE_PX)
        assertEquals(100.0, AppConfig.BLUR_LAPLACIAN_VARIANCE_MIN, 0.001)
        assertEquals(40.0, AppConfig.BRIGHTNESS_MEAN_MIN, 0.001)
        assertEquals(220.0, AppConfig.BRIGHTNESS_MEAN_MAX, 0.001)
        assertEquals(0.08, AppConfig.GLARE_PIXEL_FRACTION_MAX, 0.001)
        assertEquals(0.5f, AppConfig.DETECTOR_MIN_CONFIDENCE, 0.001f)
        assertEquals(5, AppConfig.PHASH_MAX_HAMMING_DISTANCE)
        assertEquals(3, AppConfig.LOW_STOCK_DAYS_THRESHOLD)
    }
}
