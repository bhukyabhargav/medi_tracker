package com.meditrack.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StripCropPlannerTest {

    @Test
    fun smallStripInLargePhoto_cropsAroundStripWithMargin() {
        // The real-world case: the strip covers about 14% of a 4000x3000 photo.
        val crop = StripCropPlanner.plan(0.19f, 0.50f, 0.93f, 0.70f, 4000, 3000, 0.08f)
        assertNotNull(crop)
        crop!!
        // Strip is 0.74 wide -> margin 0.0592 on each side
        assertEquals((0.1308f * 4000).toInt(), crop.left)
        assertEquals((0.484f * 3000).toInt(), crop.top)
        assertTrue(crop.width > (0.74f * 4000).toInt())
        assertTrue(crop.height > (0.20f * 3000).toInt())
        assertTrue(crop.left + crop.width <= 4000)
        assertTrue(crop.top + crop.height <= 3000)
    }

    @Test
    fun cropNeverLeavesTheImage() {
        val crop = StripCropPlanner.plan(0.0f, 0.0f, 1.0f, 1.0f, 640, 480, 0.2f)!!
        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(640, crop.width)
        assertEquals(480, crop.height)
    }

    @Test
    fun invalidOrTinyBoxes_returnNull() {
        assertNull(StripCropPlanner.plan(0.5f, 0.5f, 0.5f, 0.6f, 1000, 1000, 0.08f)) // zero width
        assertNull(StripCropPlanner.plan(0.6f, 0.5f, 0.4f, 0.6f, 1000, 1000, 0.08f)) // inverted
        assertNull(StripCropPlanner.plan(0.1f, 0.1f, 0.12f, 0.12f, 1000, 1000, 0.0f)) // 20 px, too small
        assertNull(StripCropPlanner.plan(0.1f, 0.1f, 0.9f, 0.9f, 0, 0, 0.08f)) // no image
    }

    @Test
    fun areaFraction_isProductOfSides() {
        assertEquals(0.14f, StripCropPlanner.areaFraction(0.19f, 0.50f, 0.93f, 0.69f), 0.01f)
        assertEquals(0f, StripCropPlanner.areaFraction(0.5f, 0.5f, 0.4f, 0.6f), 0.0001f)
    }
}
