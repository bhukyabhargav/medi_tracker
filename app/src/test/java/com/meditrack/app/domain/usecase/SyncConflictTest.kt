package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncConflictTest {

    private val baseDose = DoseEvent(
        id = "dose_conflict_1",
        medicineId = "med_1",
        scheduledAt = 10000L,
        windowEndsAt = 13600L,
        status = DoseStatus.PENDING,
        updatedAt = 1000L
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
}
