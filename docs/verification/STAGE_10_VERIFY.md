# Stage 10 Verification Pack – Firebase Backend, Sync & Caregiver Mode

## 1. Architectural Data-Flow Diagram (Section A)

The diagram below details the end-to-end data flow from patient local action to caregiver delivery:

```mermaid
flowchart TD
    subgraph PatientDevice ["Patient Device (Offline-First)"]
        UI["Patient UI (Dose Verification / Reminder Engine)"]
        Room["Room Database (Source of Truth)"]
        Outbox["NotificationOutbox (SQLite)"]
        SyncW["SyncWorker (WorkManager, Pull-then-Push)"]
        OutboxW["OutboxDeliveryWorker (Exponential Backoff)"]
    end

    subgraph FirebaseCloud ["Google Cloud & Firebase Backend"]
        FS_Users["Firestore /users/{patientId}/doseEvents"]
        FS_Outbox["Firestore /notificationEvents"]
        FStorage["Cloud Storage /proofs/{patientId}/{doseId}.jpg"]
        CF_Notify["Cloud Function: onNotificationEventCreated"]
        FCM["Firebase Cloud Messaging (FCM Topic/Token)"]
    end

    subgraph CaregiverDevice ["Caregiver Device"]
        C_App["MediTrack Caregiver Dashboard"]
        C_Push["FCM Notification Alert (Missed / Low Stock)"]
    end

    UI -->|Record Intake / Missed Dose| Room
    UI -->|Enqueue Alert Event| Outbox
    SyncW -->|1. Pull Remote & Conflict Resolve| Room
    SyncW -->|2. Push Local Dose / Medicine Delta| FS_Users
    SyncW -->|Upload Proof Photo JPEG < 5MB| FStorage
    OutboxW -->|Deliver Event (Idempotent)| FS_Outbox
    FS_Outbox -->|Trigger| CF_Notify
    CF_Notify -->|Send Alert Payload| FCM
    FCM -->|Deliver Push| C_Push
    C_App -->|Read-Only Query (Accepted Link)| FS_Users
    C_App -->|Download Permitted Photo| FStorage
```

---

## 2. Security Rules Enforcement (Section B)

### 2.1 Firestore Rules Audit
1. **User Profile & Subcollections**:
   - `users/{userId}/medicines/{medicineId}`: Patient write only (`allow write: if isOwner(userId)`). Doctors and caregivers strictly denied direct write.
   - `users/{userId}/doseEvents/{doseId}`: Patient write only. Caregivers and doctors can NEVER mark doses taken or edit history.
2. **Caregiver Links**:
   - Immutable client creation denied (`allow create: if false`). Links are established via verified invitation redeem.
   - Revocation: Either party can transition `ACCEPTED -> REVOKED`. Revoked link cannot be reactivated via direct update.
   - Read: strictly denied for unrelated users.
3. **Caregiver Invites**:
   - Single-use, time-limited (< 7 days), owner validated.

### 2.2 Storage Rules Audit
1. `proofs/{patientUid}/{doseId}.jpg`:
   - Patient upload only, validated size (< 5MB) and contentType (`image/*`).
   - Separate create/update rule from delete rule. Delete rule does NOT access `request.resource`.
   - Read allowed only for patient, accepted caregiver, or accepted doctor. Pending/declined/revoked denied.

---

## 3. Synchronization & Outbox Edge Cases Handled

1. **Conflict Resolution (`SyncConflictResolver.kt`)**:
   - Last-write-wins by `updatedAt` (not `createdAt`).
   - Non-downgrade invariant: Verified `TAKEN` or `TAKEN_LATE` doses are NEVER downgraded by remote pending or missed records.
   - Deletion tombstones (`isDeleted = true`) preserved when newer or equal timestamp.
2. **Outbox Delivery (`OutboxDeliveryWorker.kt`)**:
   - Retains pending events if Firebase or network is unavailable.
   - Filters out revoked caregivers so private health events are not delivered to unlinked parties.
   - Idempotent event writes with stable IDs prevent duplicate notifications.

---

## 4. Verification & Testing Status

### 4.1 Implemented
- Full offline-first synchronization engine (`SyncWorker.kt`, `SyncConflictResolver.kt`).
- Reliable outbox delivery worker with exponential backoff (`OutboxDeliveryWorker.kt`).
- Caregiver linking, invite creation, and read-only patient dashboard (`CaregiverViewModel.kt`).
- Patient caregiver revocation with immediate cloud link termination.
- Firestore security rules and Firebase Storage security rules.

### 4.2 Automated Tests Passed
- **Test Command**: `.\gradlew.bat testDebugUnitTest`
- **Result**: PASSED
- Unit tests covering:
  - `SyncConflictTest` (non-downgrade invariant, LWW by updatedAt, tombstone deletion, tie deterministic resolution).
  - `OutboxDeliveryWorkerTest` (empty outbox, offline/Firebase unavailable pending retention, revoked caregiver filtering).
  - `OutboxOfflinePersistenceTest`.

### 4.3 Firebase Emulator Tests
- **Status**: **NOT RUN** (Firebase CLI / Firebase Emulator Suite is not installed on the local host machine). Test specification prepared in `scripts/firebase_security_tests.js`.

### 4.4 Manual Emulator Checks Completed
- None performed in this automated verification session.

### 4.5 Not Tested / Remaining Limitations
- Live cloud FCM push delivery on physical Google Play Services devices.
- Network loss during large batch photo uploads (handled via exponential backoff WorkManager retry).
