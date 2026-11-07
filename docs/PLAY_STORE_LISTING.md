# Google Play Store Listing & Compliance Declarations

## 1. Store Listing Details

- **App Name**: MediTrack — Visual Medication Tracker
- **Short Description (<= 80 chars)**:
  Track pill intake with camera verification, smart alarms, and doctor links.
- **Full Description**:
  MediTrack helps you stay on track with your medication regimens using intelligent on-device computer vision verification. 

  **Key Features**:
  • **Visual Pill Verification**: Snap a photo of your blister pack to verify dose intake before recording it.
  • **Smart Rolling Reminders**: Alarms scheduled with exact timing and customizable dose windows.
  • **Prescription Scanning (OCR)**: Scan pharmacy labels and prescription printouts to prefill medication schedules without manual typing.
  • **Caregiver Support**: Link family members to receive alerts on missed doses or running low on stock.
  • **Doctor Mode Governance**: Connect your physician so they can suggest regimen adjustments that you review and approve.
  • **Privacy-First & Offline Ready**: All photos and medical data remain stored securely in app-private storage. Works offline without continuous internet access.

---

## 2. Mandatory Clinical Disclaimers

> **IMPORTANT CLINICAL & REGULATORY NOTICE**:
> MediTrack is **not** a certified medical device and does **not** provide medical advice, diagnosis, or clinical treatment plans. 
> Visual intake verification records only that a pill has been removed from a blister strip; it cannot confirm ingestion. 
> MediTrack does not verify medical licenses, registration numbers, or credentials of physicians. Always consult a qualified healthcare provider regarding your health and medication regimens.

---

## 3. Data Safety Form Responses

- **Data Collected**:
  - Health & Fitness (Medication schedules, pill counts, dose timestamps): Stored locally; synced to cloud only when authenticated.
  - Photos & Videos (Blister strip verification and prescription scans): Stored in app-private sandboxed directory (`MODE_PRIVATE`).
  - Contact Info (Name, email, phone number): Used for account authentication and invitation links.
- **Data Sharing**:
  - No data shared with third parties or advertising networks.
- **Security Practices**:
  - Data encrypted in transit using TLS.
  - Data encrypted at rest using hardware-backed Android Keystore AES-256 GCM.
  - Right to deletion and complete data export honored under DPDP Act 2023.

---

## 4. Policy Declarations

### A. SCHEDULE_EXACT_ALARM Declaration
- **Use Case**: Critical Health Management (Medication Reminders).
- **Justification**: Timely medication adherence requires precise scheduling at exact wall-clock minutes. Inexact alarms risk missed doses and non-compliance for critical pharmaceuticals.

### B. Health App Declaration
- **Category**: Medication Tracking & Adherence Management.
- **Medical Advice Exclusion**: Declared as an adherence log and reminder aid; does not prescribe or provide medical consultations.
