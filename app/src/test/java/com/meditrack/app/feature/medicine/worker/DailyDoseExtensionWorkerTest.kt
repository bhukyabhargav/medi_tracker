package com.meditrack.app.feature.medicine.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyDoseExtensionWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)

    private lateinit var worker: DailyDoseExtensionWorker
    private val zoneId = ZoneId.of("UTC")

    @Before
    fun setup() {
        worker = DailyDoseExtensionWorker(
            appContext = context,
            workerParams = workerParams,
            medicineRepository = medicineRepository,
            doseRepository = doseRepository,
            alarmScheduler = io.mockk.mockk(relaxed = true)
        )
    }

    @Test
    fun doWork_extends_doses_for_active_medicines_and_returns_success() = runTest {
        val now = System.currentTimeMillis()
        val activeMed = Medicine(
            id = "med_active_1",
            name = "Metformin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = now,
            endDate = now + (3 * 24 * 60 * 60 * 1000L),
            foodRelation = FoodRelation.AFTER,
            initialStock = 30,
            currentStock = 30
        )

        every { medicineRepository.observeActive() } returns flowOf(listOf(activeMed))
        every { doseRepository.observeByMedicineId("med_active_1") } returns flowOf(emptyList())
        coEvery { doseRepository.insertAll(any()) } returns Unit

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { doseRepository.insertAll(match { it.isNotEmpty() }) }
    }
}
