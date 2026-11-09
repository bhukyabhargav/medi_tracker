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

    @Test
    fun mapToOriginal_mapsCropCoordinatesBackToWholeImage() {
        val crop = CropRect(left = 200, top = 100, width = 400, height = 200)
        // Whole crop -> its place in a 1000x500 image
        val full = StripCropPlanner.mapToOriginal(0f, 0f, 1f, 1f, crop, 1000, 500)
        assertEquals(0.2f, full[0], 0.0001f)
        assertEquals(0.2f, full[1], 0.0001f)
        assertEquals(0.6f, full[2], 0.0001f)
        assertEquals(0.6f, full[3], 0.0001f)
        // Centre of the crop -> centre of that region
        val mid = StripCropPlanner.mapToOriginal(0.4f, 0.4f, 0.6f, 0.6f, crop, 1000, 500)
        assertEquals(0.36f, mid[0], 0.0001f)
        assertEquals(0.44f, mid[2], 0.0001f)
    }
}
