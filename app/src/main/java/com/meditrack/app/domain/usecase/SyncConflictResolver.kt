package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine

object SyncConflictResolver {

    /**
     * Resolves conflict between local and remote DoseEvent:
     * Conflict rule: Last-write-wins by updatedAt, EXCEPT a verified dose (TAKEN or TAKEN_LATE)
     * is NEVER downgraded (e.g. to PENDING, MISSED, or UNVERIFIED).
     */
    fun resolveDoseConflict(local: DoseEvent, remote: DoseEvent): DoseEvent {
        val localIsVerified = local.status == DoseStatus.TAKEN || local.status == DoseStatus.TAKEN_LATE
        val remoteIsVerified = remote.status == DoseStatus.TAKEN || remote.status == DoseStatus.TAKEN_LATE

        // Non-downgrade invariant: If local is verified and remote is unverified, never downgrade
        if (localIsVerified && !remoteIsVerified) {
            return local
        }

        // If remote is verified and local is not verified, accept verified intake
        if (remoteIsVerified && !localIsVerified) {
            return remote
        }

        // Both verified or neither verified: Last-write-wins by updatedAt
        return if (remote.updatedAt > local.updatedAt) {
            remote
        } else {
            local
        }
    }

    /**
     * Resolves conflict between local and remote Medicine:
     * Last-write-wins by createdAt / updatedAt timestamp.
     */
    fun resolveMedicineConflict(local: Medicine, remote: Medicine): Medicine {
        return if (remote.createdAt > local.createdAt) remote else local
    }
}
