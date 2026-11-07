# Stage 10B Verification Pack – Doctor Mode

## 1. Architectural Architecture & Permissions (Section A)

### 1.1 Prescription Proposal Flow Diagram

```mermaid
flowchart TD
    subgraph DoctorApp ["Doctor App / Portal"]
        DocAction["Doctor Submits Proposal (ADD / EDIT / STOP)"]
        DocInvite["Generate 8-char Code (SecureRandom, no 0/O/1/I)"]
        DocMonitor["Query Patient Data (users/{patientId}/medicines & doseEvents)"]
    end

    subgraph FirebaseBackend ["Google Cloud / Firebase Backend"]
        FSInvites["Firestore /doctorInvitations/{code}"]
        CFInvite["Cloud Function: acceptDoctorInvite / declineDoctorInvite"]
        FSLinks["Firestore /doctorLinks/{linkId}"]
        FSProposals["Firestore /medicineProposals/{proposalId}"]
        CFTrigger["Cloud Function: onNotificationEventCreated"]
        FCMNotif["FCM Push Notification"]
        RulesSecurity["Firestore Security Rules (Role & Status Validation)"]
    end

    subgraph PatientDevice ["Patient Device (Offline-First)"]
        PatRedeem["Redeem Invite Code via Callable CF"]
        PatReview["Patient Review Screen (Accept / Decline / Clarify)"]
        PatAccept["AcceptProposalUseCase (Validate Payload & Boundaries)"]
        RoomDB["Room Database (Single Source of Truth)"]
        AlarmMgr["AlarmScheduler (Exact Alarms Armed / Canceled)"]
    end

    DocInvite -->|Write Invite| FSInvites
    PatRedeem -->|Call acceptDoctorInvite| CFInvite
    CFInvite -->|Validate & Create ACCEPTED Link (Admin SDK)| FSLinks
    FSLinks --> RulesSecurity

    DocAction -->|Submit PENDING Proposal| FSProposals
    FSProposals --> RulesSecurity
    FSProposals -->|Trigger Event| CFTrigger
    CFTrigger -->|Verify Relationship & Send Push| FCMNotif
    FCMNotif -->|Deliver Alert| PatReview

    PatReview -->|Decline / Request Clarification| FSProposals
    PatReview -->|Accept Proposal| PatAccept
    PatAccept -->|Verify Payload & Ownership Boundaries| RoomDB
    RoomDB -->|Update Future Doses & Active Medicines| AlarmMgr
    PatAccept -->|Mark ACCEPTED| FSProposals

    DocMonitor -->|Read-only 7-Day Adherence Query| RoomDB
```

---

## 2. Doctor Link & Proposal Authorization (Section B)

1. **Doctor Link Authorization (`doctorLinks/{linkId}`)**:
   - Clients have `allow create: if false;`. Only the `acceptDoctorInvite` Cloud Function with the Admin SDK can create an accepted doctor link.
   - Doctor can never forge patient consent.
   - Intended patient must authenticate and explicitly redeem or accept the connection.
   - Revoked links cannot be reactivated via direct update (`status == 'REVOKED'` immutable; can only transition from `ACCEPTED` to `REVOKED` by participants updating only `status` and `updatedAt`).
   - Pending, declined, or revoked links grant zero patient data access.
   - Standardized on `patientId` field across all collections and Kotlin entities.

2. **Doctor Invitations (`doctorInvitations/{code}`)**:
   - Only the issuing doctor can read or delete their invitation (`resource.data.doctorUid == request.auth.uid`).
   - Patients never read or query `doctorInvitations` directly; they redeem codes through the `acceptDoctorInvite` callable Cloud Function.
   - Updates are disabled for clients (`allow update: if false;`).

3. **Prescription Proposals (`medicineProposals/{proposalId}`)**:
   - Doctor can only create proposals if an accepted link exists (`isAcceptedDoctor(patientId)`), with initial `status == 'PENDING'` and `revision == 1`.
   - Doctor cannot alter patient decisions or accepted status directly.
   - Once a proposal is `ACCEPTED`, `DECLINED`, or `REJECTED`, no further updates are permitted.
   - Doctor revisions (`CLARIFICATION_REQUESTED -> PENDING`) increment `revision` (`revision + 1`) and require fresh patient review.
   - Patient can accept, decline, or request clarification, but cannot modify prescription contents, doctor identity, or patient identity.
   - Patient clinical records (medicines and dose events in `users/{userId}/...`) are strictly writeable by the patient only. Doctors have read-only access for monitoring.

---

## 3. Proposal State Machine Verification

Supported transitions verified:
- `PENDING` -> `ACCEPTED` (patient accepts)
- `PENDING` -> `DECLINED` (patient declines)
- `PENDING` -> `CLARIFICATION_REQUESTED` (patient asks question)
- `CLARIFICATION_REQUESTED` -> `PENDING` (doctor answers & revises)
- `CLARIFICATION_REQUESTED` -> `DECLINED` (patient declines revision)

---

## 4. Verification & Testing Status

### 4.1 Implemented & Hardened
- Complete Doctor UI (`DoctorScreen.kt`, `DoctorViewModel.kt`) with cloud fallback.
- `DoctorCloud.kt`: Cloud Functions calls (`acceptDoctorInvite`, `declineDoctorInvite`), Firestore proposal management, and read-only 7-day adherence monitoring.
- Code generation: 8-character unguessable alphanumeric code (`SecureRandom`, alphabet excluding `0`, `O`, `1`, `I`).
- `AcceptProposalUseCase`:
  - Payload validation with `MedicineValidator` before inserting/updating medicine.
  - Proposal idempotency: returns `true` immediately if already accepted.
  - Deterministic medicine ID: `proposal.medicineId ?: "med_${proposal.id}"`.
  - Boundary checks: patient identity matching (`proposal.patientId == currentUserId`) and doctor ownership on EDIT/STOP (`prescribedByDoctorUid == proposal.doctorUid`).
- Scheduled dose and alarm cancellation on doctor `STOP` proposals.

### 4.2 Automated Tests Passed
- **Test Command**: `.\gradlew.bat testDebugUnitTest`
- **Result**: **BUILD SUCCESSFUL** (306 unit tests passing across 46 test files, 0 failures, 0 skipped).
- Unit tests covering Doctor Mode:
  - `ProposalStateMachineTest` (valid transitions, invalid transitions rejected).
  - `DoctorAccessGatingTest` (unlinked, pending, declined, revoked access denial).
  - `DoctorPrescriptionBypassTest` (proposals must not bypass patient consent).
  - `PrescriptionProposalWorkflowTest` (full proposal lifecycle: add, edit, stop, invalid payload rejection, idempotency, patient boundary mismatch, doctor boundary mismatch, clarification, revision).
  - `DoctorViewModelTest` (8-char invite code generation without ambiguous characters, connection requests, outbox events, patient adherence calculation).

### 4.3 Firebase Emulator Tests
- **Status**: **NOT RUN** (Firebase CLI / Emulator Suite not installed on local host).
- Security rule coverage scripted in `scripts/firebase_security_tests.js`.

### 4.4 Manual Emulator Checks Completed
- None performed in this automated verification session.

### 4.5 Remaining Limitations
- MediTrack does not verify doctor medical license numbers, certificates, or professional registrations.
- Doctor-patient communication occurs strictly through proposal states and structured clarification notes (no free-form real-time chat).
