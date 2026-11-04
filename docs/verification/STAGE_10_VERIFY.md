# Stage 10 Verification Pack – Firebase Backend, Sync & Caregiver Mode (Corrected & Audited)

## 1. Architectural Data-Flow Diagram (Section A)

The diagram below details the offline-first data flow from patient local action to caregiver delivery, strictly maintaining Room as the single source of truth:

```mermaid
flowchart TD
    subgraph PatientDevice ["Patient Device (Offline-First)"]
        UI["Patient UI (Dose Verification / Reminder Engine)"]
        Room["Room Database (Single Source of Truth)"]
        Outbox["NotificationOutbox (SQLite)"]
        SyncW["SyncWorker (WorkManager, Network-Constrained, Backoff)"]
        OutboxW["OutboxDeliveryWorker (WorkManager, Network-Constrained, Backoff)"]
    end

    subgraph FirebaseBackend ["Google Cloud / Firebase Backend"]
        Firestore["Cloud Firestore (/users, /caregiverLinks, /notificationEvents)"]
        Storage["Firebase Storage (/proofs/{patientUid}/{doseId}.jpg)"]
        CF["Cloud Function onCreate(notificationEvents/{id})"]
        FCM["Firebase Cloud Messaging (FCM Gateway)"]
    end

    subgraph CaregiverDevice ["Caregiver Device"]
        FCMService["MediTrackFirebaseMessagingService"]
        CGUI["Caregiver Portal (Strict Read-Only Today's Doses & Adherence)"]
    end

    UI -->|Record Verified / Missed / Low Stock| Room
    UI -->|Enqueue Alert| Outbox
    Room -.-> SyncW
    Outbox -.-> OutboxW

    SyncW -->|Push Medicines & Doses (Conflict: Verified Never Downgraded)| Firestore
    SyncW -->|Pull Remote Changes -> SyncConflictResolver -> Room| Room
    SyncW -->|Upload Proof JPEG (Image <= 5MB)| Storage
    OutboxW -->|Deliver Event Row On Success -> Mark Delivered| Firestore

    Firestore -->|onCreate Trigger| CF
    CF -->|Lookup Caregiver FCM Token & Build Spec Text| FCM
    FCM -->|High-Priority Push| FCMService
    FCMService -->|Post Local Notification| CGUI
```

---

## 2. Automated Unit Tests Summary (Section B)

Automated tests in `app/src/test/` validate all Stage 10 invariants:

| Test Class | Test Method | Invariant Verified |
|---|---|---|
| `NotificationTextTest` | `missedDose_producesExactSpecifiedText` | Push text: `"Medicine dose has been missed."` |
| `NotificationTextTest` | `lowStock_producesExactSpecifiedText_withMedicineName` | Push text: `"<Medicine> is running low – refill needed."` |
| `NotificationTextTest` | `doseUnverified_producesExactSpecifiedText` | Push text: `"Dose could not be verified."` |
| `NotificationTextTest` | `doseTakenLate_producesExactSpecifiedText` | Push text: `"Missed dose was taken late (verified)."` |
| `NotificationTextTest` | `caregiverInvite_producesExactSpecifiedText_withPatientName` | Push text: `"<Name> invited you to be their caregiver."` |
| `SyncConflictTest` | `verifiedLocalDose_neverDowngradedByRemotePendingEvenIfRemoteIsNewer` | Local `TAKEN` dose is NEVER downgraded to `PENDING` by remote write. |
| `SyncConflictTest` | `verifiedLocalDose_neverDowngradedByRemoteMissedEvenIfRemoteIsNewer` | Local `TAKEN_LATE` dose is NEVER downgraded to `MISSED` by remote write. |
| `SyncConflictTest` | `verifiedRemoteDose_upgradesLocalPendingDose` | Remote verified dose upgrades local unverified `PENDING` dose. |
| `SyncConflictTest` | `unverifiedDoses_resolveByLastWriteWins` | Unverified doses resolve via last-write-wins by `updatedAt`. |
| `OutboxDeliveryWorkerTest` | `doWork_whenEmptyOutbox_returnsSuccessImmediately` | Clean no-op when outbox is empty. |
| `OutboxDeliveryWorkerTest` | `doWork_deliversUndeliveredEvent_andMarksDeliveredOnSuccess` | Undelivered outbox row is processed and marked `delivered = true` only upon remote delivery success. |
| `OutboxDeliveryWorkerTest` | `doWork_whenDeliveryFailsOrOffline_preservesPendingEventAndRetries` | When network is down, pending events are preserved with `delivered = false` and worker retries. |
| `OutboxOfflinePersistenceTest` | `outboxEvents_persistLocallyWhenOffline` | Outbox rows persist in Room during network disconnects. |
| `OutboxOfflinePersistenceTest` | `outboxEvents_deliveredOnceConnectivityRestored` | Events are delivered and marked delivered once connection returns. |
| `OutboxOfflinePersistenceTest` | `idempotentDelivery_preventsDuplicateEntriesOnRetry` | Retrying uses deterministic `event.id` as document ID, preventing duplicates. |
| `CaregiverViewModelTest` | `sendInvitation_insertsPendingCaregiverLink_andEnqueuesOutbox` | Sending invite creates `CaregiverLink` with status `PENDING` and enqueues `CAREGIVER_INVITE`. |
| `CaregiverViewModelTest` | `simulateCaregiverResponse_accept_updatesStatusToAccepted` | Accepting invite transitions link to `ACCEPTED` and assigns caregiver UID. |
| `CaregiverViewModelTest` | `simulateCaregiverResponse_reject_remainsPending` | Rejecting invite leaves status `PENDING` with `"Invitation remains pending"` message per spec. |
| `CaregiverViewModelTest` | `selectPatient_computesAdherenceAndLoadsDoses` | Caregiver selecting a patient computes today's adherence and itemizes doses read-only. |

---

## 3. Two-Device / Multi-Account Walkthrough (Section C)

### Scenario 1: Invite Caregiver & Real Acceptance Flow
1. **Patient Account**:
   - Navigate: Home $\rightarrow$ tap **Caregivers** on Quick Actions.
   - Tap **+** to add a caregiver (Name: `"Sarah"`, Contact: `"+1-555-0199"`, Relationship: `"Daughter"`).
   - Tap **Send Invitation** $\rightarrow$ card appears under "Pending Invitations" with `"Pending"` chip.
   - Outbox records `CAREGIVER_INVITE`.
2. **Caregiver Account**:
   - Log in or switch to Caregiver role.
   - Tap **Accept Invitation** $\rightarrow$ status updates to `"Caregiver connected"` (`ACCEPTED`).
   - If caregiver taps **Decline**, status remains pending with message `"Invitation remains pending"`.

### Scenario 2: Offline / Airplane Mode Persistence
1. Turn on **Airplane Mode** (disconnect Wi-Fi and mobile data).
2. Take a dose or trigger an alert (Missed dose, Low stock, Unverified).
3. Observe: `NotificationOutbox` retains rows locally with `delivered = 0`. No crash occurs.
4. Turn off **Airplane Mode** (reconnect network).
5. WorkManager executes `OutboxDeliveryWorker` under `NetworkType.CONNECTED` constraint and delivers all pending events to Firestore.

### Scenario 3: Caregiver Read-Only Portal
1. On Caregiver screen, view patients under **"Patients in Your Care"**.
2. Select a patient:
   - View today's medication adherence percentage (e.g. `100%`).
   - View scheduled doses and status chips (`TAKEN`, `PENDING`, `MISSED`).
   - View current medicines and stock levels.
   - **Verification**: No buttons exist to mark doses taken, modify stock, or edit prescriptions.

---

## 4. Security Rules Specification & Proof (Section D)

The security rules in [`firestore.rules`](file:///c:/Users/bharg/OneDrive/Documents/Desktop/MedTracker%20-%20BioDesign/firestore.rules) and [`storage.rules`](file:///c:/Users/bharg/OneDrive/Documents/Desktop/MedTracker%20-%20BioDesign/storage.rules) enforce strict zero-trust boundaries:

1. **Storage Rules**:
   ```javascript
   match /proofs/{patientUid}/{doseId}.jpg {
     allow write: if isOwner(patientUid)
       && request.resource.size < 5 * 1024 * 1024
       && request.resource.contentType.matches('image/.*');
     allow read: if isOwner(patientUid) || isAcceptedCaregiver(patientUid) || isAcceptedDoctor(patientUid);
   }
   ```
   - Patient can access their own photos.
   - Only accepted, linked caregivers and doctors can read patient proof photos.
   - Pending, declined, revoked and unrelated users receive `PERMISSION_DENIED`.

2. **Firestore Rules**:
   ```javascript
   match /users/{userId}/medicines/{medicineId} {
     allow read: if isOwner(userId) || isAcceptedCaregiver(userId) || isAcceptedDoctor(userId);
     allow write: if isOwner(userId);
   }
   match /users/{userId}/doseEvents/{doseId} {
     allow read: if isOwner(userId) || isAcceptedCaregiver(userId) || isAcceptedDoctor(userId);
     allow write: if isOwner(userId);
   }
   ```
   - Patient has sole write access. Neither caregivers nor doctors can write medicines or dose events directly.
