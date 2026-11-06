package com.meditrack.app.feature.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScanGallerySecurityTest {

    @Test
    fun verification_feature_never_imports_or_references_photo_picker_or_gallery() {
        val verificationDir = File("src/main/java/com/meditrack/app/feature/verification")
        assertTrue("Verification feature directory must exist", verificationDir.exists() && verificationDir.isDirectory)

        val forbiddenKeywords = listOf(
            "PickVisualMedia",
            "ACTION_PICK",
            "ACTION_GET_CONTENT"
        )

        val verificationFiles = verificationDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue("Verification files must be present", verificationFiles.isNotEmpty())

        for (file in verificationFiles) {
            val content = file.readText()
            for (keyword in forbiddenKeywords) {
                assertFalse(
                    "Security violation: ${file.name} contains forbidden photo picking keyword '$keyword'. Dose verification strictly forbids gallery picking!",
                    content.contains(keyword)
                )
            }
        }
    }

    @Test
    fun gallery_photo_picking_is_present_in_scan_prescription_feature() {
        val scanScreenFile = File("src/main/java/com/meditrack/app/feature/scan/ScanPrescriptionScreen.kt")
        assertTrue("ScanPrescriptionScreen must exist", scanScreenFile.exists())

        val content = scanScreenFile.readText()
        assertTrue(
            "ScanPrescriptionScreen must support PickVisualMedia for prescription scanning",
            content.contains("ActivityResultContracts.PickVisualMedia")
        )
        assertTrue(
            "ScanPrescriptionScreen must allow image picking via PhotoLibrary icon",
            content.contains("PhotoLibrary")
        )
    }
}
