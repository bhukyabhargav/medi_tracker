export type NotifType =
  | 'MISSED_DOSE'
  | 'LOW_STOCK'
  | 'DOSE_UNVERIFIED'
  | 'DOSE_TAKEN_LATE'
  | 'CAREGIVER_INVITE'
  | 'DOCTOR_INVITE_RECEIVED'
  | 'DOCTOR_INVITE_ACCEPTED'
  | 'DOCTOR_INVITE_DECLINED'
  | 'DOCTOR_INVITE_REVOKED'
  | 'PRESCRIPTION_PROPOSAL_RECEIVED'
  | 'PRESCRIPTION_ACCEPTED'
  | 'PRESCRIPTION_DECLINED'
  | 'PRESCRIPTION_CLARIFICATION_REQUESTED'
  | 'PRESCRIPTION_REVISED'
  | 'DOSE_SKIPPED';

export interface NotificationPayload {
  type: NotifType;
  patientUid?: string;
  patientName?: string;
  doctorName?: string;
  caregiverUid?: string;
  medicineName?: string;
  doseId?: string;
  createdAt?: number;
}

/**
 * Builds the push notification text matching the exact Stage 10 specification:
 * - MISSED_DOSE: "Medicine dose has been missed."
 * - LOW_STOCK: "<Medicine> is running low – refill needed."
 * - DOSE_UNVERIFIED: "Dose could not be verified."
 * - DOSE_TAKEN_LATE: "Missed dose was taken late (verified)."
 * - CAREGIVER_INVITE: "<Name> invited you to be their caregiver."
 */
export function getNotificationText(payload: NotificationPayload): { title: string; body: string } {
  const medicine = payload.medicineName || 'Medicine';
  const name = payload.patientName || 'A patient';
  const doctor = payload.doctorName || 'Doctor';

  switch (payload.type) {
    case 'MISSED_DOSE':
      return {
        title: 'Missed Dose',
        body: 'Medicine dose has been missed.',
      };
    case 'DOSE_SKIPPED':
      return {
        title: 'Dose Skipped',
        body: 'Medicine dose has been skipped.',
      };
    case 'LOW_STOCK':
      return {
        title: 'Low Stock Alert',
        body: `${medicine} is running low \u2013 refill needed.`,
      };
    case 'DOSE_UNVERIFIED':
      return {
        title: 'Dose Unverified',
        body: 'Dose could not be verified.',
      };
    case 'DOSE_TAKEN_LATE':
      return {
        title: 'Late Dose Taken',
        body: 'Missed dose was taken late (verified).',
      };
    case 'CAREGIVER_INVITE':
      return {
        title: 'Caregiver Invitation',
        body: `${name} invited you to be their caregiver.`,
      };
    case 'DOCTOR_INVITE_RECEIVED':
      return { title: 'Doctor Connection Request', body: `${doctor} wants to connect as your doctor on MediTrack. Review the request.` };
    case 'DOCTOR_INVITE_ACCEPTED':
      return { title: 'Doctor Connection Accepted', body: `${name} accepted your doctor connection.` };
    case 'DOCTOR_INVITE_DECLINED':
      return { title: 'Doctor Connection Declined', body: `${name} declined your doctor connection.` };
    case 'DOCTOR_INVITE_REVOKED':
      return { title: 'Doctor Connection Revoked', body: 'Doctor access has been revoked.' };
    case 'PRESCRIPTION_PROPOSAL_RECEIVED':
      return { title: 'Prescription Proposal', body: `Dr ${doctor} proposed a prescription for ${medicine}. Review and accept.` };
    case 'PRESCRIPTION_ACCEPTED':
      return { title: 'Prescription Accepted', body: `${name} accepted the prescription proposal for ${medicine}.` };
    case 'PRESCRIPTION_DECLINED':
      return { title: 'Prescription Declined', body: `${name} declined the prescription proposal for ${medicine}.` };
    case 'PRESCRIPTION_CLARIFICATION_REQUESTED':
      return { title: 'Clarification Requested', body: `${name} requested clarification on ${medicine}.` };
    case 'PRESCRIPTION_REVISED':
      return { title: 'Prescription Revised', body: `Dr ${doctor} updated the prescription proposal for ${medicine}. Review the new revision.` };
    default:
      return {
        title: 'MediTrack Alert',
        body: 'You have a new MediTrack notification.',
      };
  }
}
