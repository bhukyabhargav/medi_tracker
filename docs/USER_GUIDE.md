# MediTrack End-User Guide (Patients, Caregivers & Doctors)

## 1. Patient Guide

### A. Setting Up Medications
1. Tap **+ Add Medicine** from the Home dashboard or **Scan Rx** to photograph your prescription.
2. If scanning, review the extracted name, dosage, and frequency. Confirm review reasons (e.g., fractional doses or weekly days).
3. Set your initial stock and tap **Save**. MediTrack automatically schedules reminders for a rolling 7-day window.

### B. Verifying a Dose
1. When your alarm rings, tap the notification to open **Dose Verification**.
2. Align your blister strip within the camera framing guide and snap a clear photo.
3. MediTrack's on-device computer vision analyzes the strip:
   - Confirms the strip is fully in frame without glare or blur.
   - Detects the empty cavity count before and after extraction.
4. Once verified, the dose is recorded as **TAKEN** and your stock decrements automatically.
5. If you missed a dose, use **I took this late** within the permissible late window to record it as **Taken Late** with photo proof.

### C. Privacy, Export & Deletion
- Access **Profile & Settings** from the top right icon on Home.
- Tap **Export My Data** to download your complete encrypted archive (JSON + photos).
- Tap **Delete My Account & Data** to permanently erase all local and cloud records.

---

## 2. Caregiver Guide

### Linking with a Patient
1. Ask the patient for their 8-character invitation code.
2. In **Caregiver Mode**, enter the code and tap **Redeem Code**.
3. Once connected, you can:
   - Monitor today's dose statuses in real time.
   - Receive push notifications if a patient has 3 failed verification attempts or misses a dose.
   - Receive low stock alerts when remaining pills drop below 3 days of supply.

---

## 3. Doctor Guide

### Proposing Regimen Adjustments
1. Generate an invitation code from **Doctor Mode** and share it with your patient.
2. Once the patient accepts, view their active medications and 7-day adherence report.
3. To adjust treatment, submit a **Prescription Proposal** (`ADD`, `EDIT`, or `STOP`).
4. **Clinical Governance**: The proposal remains in **PENDING** status until the patient reviews and taps **Accept** on their device.
5. Once accepted, the patient's schedule updates automatically and creates an auditable record in their history.
