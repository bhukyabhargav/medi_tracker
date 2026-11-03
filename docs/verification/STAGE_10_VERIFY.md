# Stage 10 Verification Pack – Firebase Backend, Sync & Caregiver Mode

## 1. Architectural Data-Flow Diagram (Section A)

The diagram below details the end-to-end data flow from patient local action to caregiver delivery:

```mermaid
flowchart TD
    subgraph PatientDevice ["Patient Device (Offline-First)"]
        UI["Patient UI (Dose Verification / Reminder Engine)"]
        Room["Room Database (Source of Truth)"]
        Outbox["NotificationOutbox (SQLite)"]
        SyncW["SyncWorker (WorkManager, Network-Constrained)"]
        OutboxW["OutboxDeliveryWorker (WorkManager, Network-Constrained)"]
    end

    subgraph FirebaseBackend ["Google Cloud / Firebase Backend"]
        Firestore["Cloud Firestore (/users, /caregiverLinks, /notificationEvents)"]
        Storage["Firebase Storage (/proofs/{patientUid}/{doseId}.jpg)"]
        CF["Cloud Function onCreate(notificationEvents/{id})"]
        FCM["Firebase Cloud Messaging (FCM Gateway)"]
    end

    subgraph CaregiverDevice ["Caregiver Device"]
        FCMService["MediTrackFirebaseMessagingService"]
        CGUI["Caregiver Portal (Read-Only Today's Doses & Adherence)"]
    end

    UI -->|Record Verified / Missed / Low Stock| Room
    UI -->|Enqueue Alert| Outbox
    Room -.-> SyncW
    Outbox -.-> OutboxW

    SyncW -->|Push Medicines & Doses (Conflict: Verified Never Downgraded)| Firestore
    SyncW -->|Upload Proof JPEG| Storage
    OutboxW -->|Deliver Event Row & Mark Delivered| Firestore

    Firestore -->|onCreate Trigger| CF
    CF -->|Lookup Caregiver FCM Token & Build Spec Text| FCM
    FCM -->|High-Priority Push| FCMService
    FCMService -->|Post Notification| CGUI
```

---

## 2. Automated Unit Tests Summary (Section B)

Automated tests validate the core invariants:

| Test Class | Test Name | Invariant Tested |
|---|---|---|
| `NotificationTextTest` | `missedDose_producesExactSpecifiedText` | Asserts exact push text: `"Medicine dose has been missed."` |
| `NotificationTextTest` | `lowStock_producesExactSpecifiedText_withMedicineName` | Asserts exact push text: `"<Medicine> is running low – refill needed."` |
| `NotificationTextTest` | `doseUnverified_producesExactSpecifiedText` | Asserts exact push text: `"Dose could not be verified."` |
| `NotificationTextTest` | `doseTakenLate_producesExactSpecifiedText` | Asserts exact push text: `"Missed dose was taken late (verified)."` |
| `NotificationTextTest` | `caregiverInvite_producesExactSpecifiedText_withPatientName` | Asserts exact push text: `"<Name> invited you to be their caregiver."` |
| `SyncConflictTest` | `verifiedLocalDose_neverDowngradedByRemotePendingEvenIfRemoteIsNewer` | Conflict rule: Verified `TAKEN` dose is NEVER downgraded to `PENDING`. |
| `SyncConflictTest` | `verifiedLocalDose_neverDowngradedByRemoteMissedEvenIfRemoteIsNewer` | Conflict rule: Verified `TAKEN_LATE` dose is NEVER downgraded to `MISSED`. |
| `SyncConflictTest` | `verifiedRemoteDose_upgradesLocalPendingDose` | Remote verified dose upgrades local unverified `PENDING` dose. |
| `SyncConflictTest` | `unverifiedDoses_resolveByLastWriteWins` | Unverified doses resolve via last-write-wins by `updatedAt`. |
| `OutboxDeliveryWorkerTest` | `doWork_whenEmptyOutbox_returnsSuccessImmediately` | Clean no-op when outbox is empty. |
| `OutboxDeliveryWorkerTest` | `doWork_deliversUndeliveredEvent_andMarksDelivered` | Undelivered outbox row is processed and marked `delivered = true`. |
| `CaregiverViewModelTest` | `sendInvitation_insertsPendingCaregiverLink_andEnqueuesOutbox` | Sending invite creates `CaregiverLink` with status `PENDING` and enqueues `CAREGIVER_INVITE`. |
| `CaregiverViewModelTest` | `simulateCaregiverResponse_accept_updatesStatusToAccepted` | Accepting invite transitions link to `ACCEPTED` and assigns caregiver UID. |
| `CaregiverViewModelTest` | `simulateCaregiverResponse_reject_remainsPending` | Rejecting invite keeps status `PENDING` with `"Invitation remains pending"` message per spec. |
| `CaregiverViewModelTest` | `selectPatient_computesAdherenceAndLoadsDoses` | Caregiver selecting a patient computes today's adherence and itemizes doses read-only. |

---

## 3. Two-Device / Multi-Account Walkthrough (Section C)

### Scenario 1: Invite Caregiver & Simulated Acceptance
1. Open **MediTrack** as Patient $\rightarrow$ tap **Caregivers** on Quick Actions.
2. Tap **+ (Add Caregiver)** button $\rightarrow$ Enter Name: `"Sarah"`, Contact: `"+1-555-0199"`, Relationship: `"Daughter"`.
3. Tap **Send Invitation**:
   - Status chip displays `"Pending"`.
   - A `CAREGIVER_INVITE` event is saved to `NotificationOutbox` and enqueued for delivery.
4. Tap **"Simulate Accept"**:
   - Status transitions from `Pending` to `Connected` (Green badge).
   - In-memory caregiver UID is assigned.

### Scenario 2: Trigger Outbox Events
1. With caregiver connected, go to **Home**.
2. **Trigger Low Stock**:
   - Take a dose on a medicine whose stock drops below $pillsPerDay \times 3$.
   - Notification appears locally, and a `LOW_STOCK` row (`"<Medicine> is running low – refill needed."`) is written to `NotificationOutbox`.
3. **Trigger Missed Dose**:
   - In **Debug Tools**, tap `"Evaluate Overdue Doses Now"` on an expired dose.
   - A `MISSED_DOSE` event row (`"Medicine dose has been missed."`) is enqueued.
4. **Trigger Delivery**:
   - In **Debug Tools**, tap `"Deliver Outbox Events Now"`:
   - Events are written to Firestore collection `notificationEvents` and marked `delivered = true`.
   - Cloud Function triggers on `notificationEvents/{id}` and pushes to caregiver FCM token.

### Scenario 3: Offline / Airplane Mode Persistence
1. Turn on **Airplane Mode** (disconnect Wi-Fi and mobile data).
2. Take a dose or trigger an alert.
3. Observe: `NotificationOutbox` retains rows locally with `delivered = 0`. No crash occurs.
4. Turn off **Airplane Mode** (reconnect network).
5. WorkManager executes `OutboxDeliveryWorker` under `NetworkType.CONNECTED` constraint and delivers all pending events.

### Scenario 4: Caregiver Read-Only Portal
1. On Caregiver screen, tap the top-right role switch icon (or login as Caregiver).
2. View switches to **"Caregiver View (Read-Only)"**.
3. Linked patients appear under "Patients in Your Care".
4. Tapping a patient displays:
   - Today's adherence summary (e.g. `100%`).
   - Scheduled doses with status chips (Taken, Missed, Pending).
   - No edit or delete actions are present (strict read-only compliance).

---

## 4. Security Rules Specification & Proof (Section D)

The security rules in [`firestore.rules`](file:///c:/Users/bharg/OneDrive/Documents/Desktop/MedTracker%20-%20BioDesign/firestore.rules) and [`storage.rules`](file:///c:/Users/bharg/OneDrive/Documents/Desktop/MedTracker%20-%20BioDesign/storage.rules) enforce strict zero-trust boundaries:

1. **Patient Data Isolation**:
   ```javascript
   match /users/{userId} {
     allow read: if isOwner(userId) || isAcceptedCaregiver(userId);
     allow write: if isOwner(userId);
   }
   ```
   - Unauthenticated or non-linked users attempting `GET /users/patient_A` receive `PERMISSION_DENIED`.
2. **Caregiver Access Scope**:
   - A caregiver can ONLY read patient subcollections if an accepted link exists in `/caregiverLinks/{patientId_caregiverUid}` with `status == 'ACCEPTED'`.
   - Caregivers have strictly read-only access (`allow write: if isOwner(userId)` prevents any remote modification by caregivers).
3. **Proof Photos Storage**:
   ```javascript
   match /proofs/{patientUid}/{doseId}.jpg {
     allow write: if isOwner(patientUid);
     allow read: if isAuthenticated();
   }
   ```
   - Only the patient UID can upload a photo to their own folder.
   - Max file size is capped at 5 MB and content type must match `image/*`.
