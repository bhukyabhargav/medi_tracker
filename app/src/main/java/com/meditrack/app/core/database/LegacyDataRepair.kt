package com.meditrack.app.core.database

import android.util.Log
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyDataRepair @Inject constructor(
    private val medicineDao: MedicineDao,
    private val doseEventDao: DoseEventDao
) {
    suspend fun repairLegacyData(userId: String): Int {
        if (userId.isBlank()) return 0
        return try {
            val claimedMeds = medicineDao.claimLegacyMedicines(userId)
            val claimedDoses = doseEventDao.claimLegacyDoseEvents(userId)
            if (claimedMeds > 0 || claimedDoses > 0) {
                Log.i("LegacyDataRepair", "Repaired $claimedMeds medicines and $claimedDoses dose events to user $userId")
            }
            claimedMeds + claimedDoses
        } catch (e: Exception) {
            Log.e("LegacyDataRepair", "Failed to repair legacy data for user $userId", e)
            0
        }
    }
}
