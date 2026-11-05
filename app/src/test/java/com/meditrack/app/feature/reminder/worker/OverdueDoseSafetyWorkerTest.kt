package com.meditrack.app.feature.reminder.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.ProcessMissedDoseUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverdueDoseSafetyWorkerTest {

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val processMissedDoseUseCase: ProcessMissedDoseUseCase = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private lateinit var context: Context
    private lateinit var worker: OverdueDoseSafetyWorker

    private val sampleMed = Medicine(
        id = "med_safety",
        name = "Atenolol",
        dosageText = "50mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = 1000L,
        foodRelation = FoodRelation.AFTER,
        initialStock = 30,
        currentStock = 20
    )

    private val overdueDose = DoseEvent(
        id = "dose_overdue_1",
        medicineId = "med_safety",
        scheduledAt = 1000L,
        windowEndsAt = 4600L,
        status = DoseStatus.PENDING
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        worker = OverdueDoseSafetyWorker(
            context,
            workerParams,
            doseRepository,
            medicineRepository,
            processMissedDoseUseCase
        )
    }

    @Test
    fun doWork_marksOverdueDosesAsMissed() = runTest {
        coEvery { doseRepository.getOverduePendingDoses(any()) } returns listOf(overdueDose)
        coEvery { processMissedDoseUseCase("dose_overdue_1", any()) } returns Result.success(true)
        coEvery { medicineRepository.getById("med_safety") } returns sampleMed

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { processMissedDoseUseCase("dose_overdue_1", any()) }
    }
}
