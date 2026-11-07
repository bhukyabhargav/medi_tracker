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

        // Both verified: prefer record with photo attachment, else latest updatedAt
        if (localIsVerified && remoteIsVerified) {
            if (local.verifiedPhotoPath != null && remote.verifiedPhotoPath == null) return local
            if (remote.verifiedPhotoPath != null && local.verifiedPhotoPath == null) return remote
            return if (remote.updatedAt > local.updatedAt) remote else local
        }

        // Neither verified: Last-write-wins by updatedAt. On tie, preserve local.
        return if (remote.updatedAt > local.updatedAt) {
            remote
        } else {
            local
        }
    }

    /**
     * Resolves conflict between local and remote Medicine:
     * Last-write-wins by updatedAt timestamp.
     * Handles deletion tombstones (isDeleted = true): if newer or equal with deletion, deletion wins.
     * On equal timestamp, prefer deleted state if either is deleted, otherwise preserve local.
     */
    fun resolveMedicineConflict(local: Medicine, remote: Medicine): Medicine {
        // Tombstone handling: if either is deleted
        if (remote.isDeleted && remote.updatedAt >= local.updatedAt) {
            return remote
        }
        if (local.isDeleted && local.updatedAt >= remote.updatedAt) {
            return local
        }

        // Last-write-wins by updatedAt
        return if (remote.updatedAt > local.updatedAt) {
            remote
        } else {
            local
        }
    }
}
