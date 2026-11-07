package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class SyncConflictTest {

    private val baseDose = DoseEvent(
        id = "dose_conflict_1",
        medicineId = "med_1",
        patientId = "patient_1",
        scheduledAt = 10000L,
        windowEndsAt = 13600L,
        status = DoseStatus.PENDING,
        updatedAt = 1000L
    )

    private val baseMedicine = Medicine(
        id = "med_1",
        patientId = "patient_1",
        name = "Aspirin",
        dosageText = "100mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = 1000L,
        initialStock = 30,
        currentStock = 30,
        createdAt = 1000L,
        updatedAt = 1000L,
        isDeleted = false
    )

    @Test
    fun verifiedLocalDose_neverDowngradedByRemotePendingEvenIfRemoteIsNewer() {
        // Local dose was verified on-time at t=1500
        val localTaken = baseDose.copy(status = DoseStatus.TAKEN, takenAt = 1200L, updatedAt = 1500L)

        // Remote stale or concurrent update still marked PENDING at t=2000
        val remotePending = baseDose.copy(status = DoseStatus.PENDING, updatedAt = 2000L)

        val resolved = SyncConflictResolver.resolveDoseConflict(local = localTaken, remote = remotePending)

        assertEquals("Verified TAKEN dose must never be downgraded to PENDING", DoseStatus.TAKEN, resolved.status)
        assertEquals(1200L, resolved.takenAt)
    }

    @Test
    fun verifiedLocalDose_neverDowngradedByRemoteMissedEvenIfRemoteIsNewer() {
        // Local dose was verified late at t=1800
        val localTakenLate = baseDose.copy(status = DoseStatus.TAKEN_LATE, takenAt = 1700L, updatedAt = 1800L)

        // Remote cloud worker marked it MISSED at t=2500
        val remoteMissed = baseDose.copy(status = DoseStatus.MISSED, updatedAt = 2500L)

        val resolved = SyncConflictResolver.resolveDoseConflict(local = localTakenLate, remote = remoteMissed)

        assertEquals("Verified TAKEN_LATE dose must never be downgraded to MISSED", DoseStatus.TAKEN_LATE, resolved.status)
    }

    @Test
    fun verifiedRemoteDose_upgradesLocalPendingDose() {
        val localPending = baseDose.copy(status = DoseStatus.PENDING, updatedAt = 1000L)
        val remoteTaken = baseDose.copy(status = DoseStatus.TAKEN, takenAt = 1200L, updatedAt = 1500L)

        val resolved = SyncConflictResolver.resolveDoseConflict(local = localPending, remote = remoteTaken)

        assertEquals("Remote verified TAKEN dose upgrades local PENDING dose", DoseStatus.TAKEN, resolved.status)
    }

    @Test
    fun unverifiedDoses_resolveByLastWriteWins() {
        val localPending = baseDose.copy(status = DoseStatus.PENDING, updatedAt = 1000L)
        val remoteMissed = baseDose.copy(status = DoseStatus.MISSED, updatedAt = 2000L)

        val resolvedNewerRemote = SyncConflictResolver.resolveDoseConflict(local = localPending, remote = remoteMissed)
        assertEquals("Newer remote unverified update wins", DoseStatus.MISSED, resolvedNewerRemote.status)

        val localNewerPending = baseDose.copy(status = DoseStatus.PENDING, updatedAt = 3000L)
        val resolvedNewerLocal = SyncConflictResolver.resolveDoseConflict(local = localNewerPending, remote = remoteMissed)
        assertEquals("Newer local unverified update wins", DoseStatus.PENDING, resolvedNewerLocal.status)
    }

    @Test
    fun unverifiedDoses_sameTimestamp_preservesLocalDeterministically() {
        val local = baseDose.copy(status = DoseStatus.PENDING, updatedAt = 2000L)
        val remote = baseDose.copy(status = DoseStatus.MISSED, updatedAt = 2000L)

        val resolved = SyncConflictResolver.resolveDoseConflict(local = local, remote = remote)
        assertEquals("On tie timestamp, preserve local", DoseStatus.PENDING, resolved.status)
    }

    @Test
    fun medicineConflict_newerRemoteVersion_wins() {
        val local = baseMedicine.copy(currentStock = 28, updatedAt = 1500L)
        val remote = baseMedicine.copy(currentStock = 25, updatedAt = 2000L)

        val resolved = SyncConflictResolver.resolveMedicineConflict(local = local, remote = remote)
        assertEquals(25, resolved.currentStock)
    }

    @Test
    fun medicineConflict_newerLocalVersion_wins() {
        val local = baseMedicine.copy(currentStock = 22, updatedAt = 3000L)
        val remote = baseMedicine.copy(currentStock = 25, updatedAt = 2000L)

        val resolved = SyncConflictResolver.resolveMedicineConflict(local = local, remote = remote)
        assertEquals(22, resolved.currentStock)
    }

    @Test
    fun medicineConflict_deletionTombstone_remoteDeletedWins() {
        val local = baseMedicine.copy(currentStock = 20, updatedAt = 2000L)
        val remoteDeleted = baseMedicine.copy(isDeleted = true, updatedAt = 2500L)

        val resolved = SyncConflictResolver.resolveMedicineConflict(local = local, remote = remoteDeleted)
        assertTrue("Deletion tombstone must be preserved", resolved.isDeleted)
    }

    @Test
    fun medicineConflict_deletionTombstone_localDeletedWinsIfNewer() {
        val localDeleted = baseMedicine.copy(isDeleted = true, updatedAt = 3000L)
        val remote = baseMedicine.copy(currentStock = 18, updatedAt = 2500L)

        val resolved = SyncConflictResolver.resolveMedicineConflict(local = localDeleted, remote = remote)
        assertTrue("Local deletion tombstone must be preserved", resolved.isDeleted)
    }

    @Test
    fun medicineConflict_duplicateSync_isIdempotent() {
        val local = baseMedicine.copy(currentStock = 15, updatedAt = 2000L)
        val remote = baseMedicine.copy(currentStock = 15, updatedAt = 2000L)

        val resolved1 = SyncConflictResolver.resolveMedicineConflict(local = local, remote = remote)
        val resolved2 = SyncConflictResolver.resolveMedicineConflict(local = resolved1, remote = remote)

        assertEquals(resolved1, resolved2)
    }
}
