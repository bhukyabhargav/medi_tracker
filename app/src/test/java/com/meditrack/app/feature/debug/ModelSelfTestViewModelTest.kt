package com.meditrack.app.feature.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.feature.verification.pipeline.Cell
import com.meditrack.app.feature.verification.pipeline.CellState
import com.meditrack.app.feature.verification.pipeline.Detection
import com.meditrack.app.feature.verification.pipeline.TfliteStripDetector
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelSelfTestViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private val detector = mockk<TfliteStripDetector>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun runSelfTest_whenNoAssetImages_displaysInformativePromptExplainingUnprovenAccuracy() = runTest(testDispatcher) {
        val viewModel = ModelSelfTestViewModel(context, detector)
        viewModel.defaultDispatcher = testDispatcher
        advanceUntilIdle()

        viewModel.runSelfTest()
        advanceUntilIdle()

        val state = viewModel.uiState.first { it.hasRun }
        assertFalse("Should finish running", state.isRunning)
        assertTrue("Should mark as hasRun", state.hasRun)
        assertTrue(
            "Summary must explain that real-world accuracy is unproven",
            state.summaryText.contains("Real-world blister accuracy is unproven")
        )
    }

    @Test
    fun runSyntheticSelfTest_executesDetectorAndProducesValidResult() = runTest(testDispatcher) {
        coEvery { detector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.FULL, confidence = 0.92f),
                Cell(state = CellState.FULL, confidence = 0.88f),
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.91f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.96f
        )

        val viewModel = ModelSelfTestViewModel(context, detector)
        viewModel.defaultDispatcher = testDispatcher
        advanceUntilIdle()

        viewModel.runSyntheticSelfTest()
        advanceUntilIdle()

        val state = viewModel.uiState.first { it.hasRun }
        assertFalse("Should finish running", state.isRunning)
        assertTrue("Should mark as hasRun", state.hasRun)
        assertEquals("Should produce 1 result", 1, state.results.size)

        val result = state.results.first()
        assertTrue("Result should be marked synthetic", result.isSynthetic)
        assertTrue("Strip should be detected", result.stripDetected)
        assertEquals("Total cells should match", 4, result.totalCells)
        assertEquals("Full cells count", 2, result.fullCells)
        assertEquals("Empty cells count", 2, result.emptyCells)
        assertEquals("Partial cells count", 0, result.partialCells)
        assertTrue("Latency should be non-negative", result.latencyMs >= 0)
    }
}
