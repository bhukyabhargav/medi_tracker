package com.meditrack.app.domain.usecase

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.meditrack.app.MainActivity
import com.meditrack.app.R
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.feature.reminder.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class StockDecrementResult(
    val medicine: Medicine,
    val newStock: Int,
    val isLow: Boolean,
    val transitionedToLow: Boolean,
    val outboxEnqueued: Boolean,
    val promptCaregiver: Boolean
)

@Singleton
class ProcessStockDecrementUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val medicineRepository: MedicineRepository,
    private val caregiverRepository: CaregiverRepository,
    private val outboxRepository: OutboxRepository,
    private val userPreferences: UserPreferences
) {
    internal var notifier: (context: Context, medName: String, remaining: Int, promptCaregiver: Boolean) -> Unit = { ctx, name, remaining, promptCaregiver ->
        showLocalLowStockNotification(ctx, name, remaining, promptCaregiver)
    }

    suspend operator fun invoke(
        medicineId: String,
        pillsToDecrement: Int,
        now: Long = System.currentTimeMillis()
    ): StockDecrementResult? {
        val medicine = medicineRepository.getById(medicineId) ?: return null

        val wasLow = StockCalculator.isLow(medicine) || medicine.lowStockNotified
        val newStock = maxOf(0, medicine.currentStock - pillsToDecrement)
        val tempUpdated = medicine.copy(currentStock = newStock)
        val isNowLow = StockCalculator.isLow(tempUpdated)

        val transitionedToLow = !wasLow && isNowLow

        var outboxEnqueued = false
        var promptCaregiver = false

        if (transitionedToLow) {
            val currentUserId = userPreferences.currentUserId.first()
            val hasCaregiver = currentUserId != null && caregiverRepository.hasConnectedCaregiver(currentUserId)

            if (hasCaregiver) {
                // Enqueue a LOW_STOCK outbox event: "<Medicine> is running low – refill needed."
                val outboxEvent = NotificationOutbox(
                    id = "outbox_${UUID.randomUUID()}",
                    type = NotifType.LOW_STOCK,
                    payloadJson = """{"medicineId":"${medicine.id}","name":"${medicine.name}","remaining":$newStock}""",
                    createdAt = now,
                    delivered = false
                )
                outboxRepository.enqueue(outboxEvent)
                outboxEnqueued = true
            } else {
                // If no caregiver is connected: prompt to add caregiver
                promptCaregiver = true
            }

            // Show local alert to the patient: "<Medicine> is running low."
            try {
                notifier(context, medicine.name, newStock, promptCaregiver)
            } catch (_: Exception) {}
        }

        val finalMedicine = medicine.copy(
            currentStock = newStock,
            lowStockNotified = if (isNowLow) true else false
        )
        medicineRepository.update(finalMedicine)

        return StockDecrementResult(
            medicine = finalMedicine,
            newStock = newStock,
            isLow = isNowLow,
            transitionedToLow = transitionedToLow,
            outboxEnqueued = outboxEnqueued,
            promptCaregiver = promptCaregiver
        )
    }

    companion object {
        fun showLocalLowStockNotification(
            context: Context,
            medicineName: String,
            remainingPills: Int,
            promptCaregiver: Boolean = false
        ) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                NotificationChannels.createChannels(context)

                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    medicineName.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val message = if (promptCaregiver) {
                    "$medicineName is running low ($remainingPills pills remaining). Add a caregiver so they can be notified."
                } else {
                    "$medicineName is running low ($remainingPills pills remaining). Refill needed."
                }

                val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_GENERAL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("$medicineName is running low")
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                notificationManager.notify(medicineName.hashCode(), notification)
            } catch (_: Exception) {}
        }
    }
}
