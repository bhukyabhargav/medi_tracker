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
  | 'PRESCRIPTION_REVISED';

export interface NotificationPayload {
  type: NotifType;
  patientUid?: string;
  patientName?: string;
  caregiverUid?: string;
  doctorUid?: string;
  doctorName?: string;
  medicineName?: string;
  doseId?: string;
  proposalId?: string;
  note?: string;
  createdAt?: number;
}

/**
 * Builds the push notification text matching the exact specification:
 * - MISSED_DOSE: "Medicine dose has been missed."
 * - LOW_STOCK: "<Medicine> is running low – refill needed."
 * - DOSE_UNVERIFIED: "Dose could not be verified."
 * - DOSE_TAKEN_LATE: "Missed dose was taken late (verified)."
 * - CAREGIVER_INVITE: "<Name> invited you to be their caregiver."
 * - DOCTOR_INVITE_RECEIVED: "<name> wants to connect as your doctor on MediTrack. Review the request."
 * - DOCTOR_INVITE_ACCEPTED: "<patientName> accepted your doctor connection."
 * - DOCTOR_INVITE_DECLINED: "<patientName> declined your doctor connection."
 * - DOCTOR_INVITE_REVOKED: "Doctor access has been revoked."
 * - PRESCRIPTION_PROPOSAL_RECEIVED: "Dr <name> proposed a prescription for <Medicine>. Review and accept."
 * - PRESCRIPTION_ACCEPTED: "<patientName> accepted the prescription proposal for <Medicine>."
 * - PRESCRIPTION_DECLINED: "<patientName> declined the prescription proposal for <Medicine>."
 * - PRESCRIPTION_CLARIFICATION_REQUESTED: "<patientName> requested clarification on <Medicine>."
 * - PRESCRIPTION_REVISED: "Dr <name> updated the prescription proposal for <Medicine>. Review the new revision."
 */
export function getNotificationText(payload: NotificationPayload): { title: string; body: string } {
  const medicine = payload.medicineName || 'Medicine';
  const patientName = payload.patientName || 'A patient';
  const doctorName = payload.doctorName || 'Doctor';

  switch (payload.type) {
    case 'MISSED_DOSE':
      return {
        title: 'Missed Dose',
        body: 'Medicine dose has been missed.',
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
        body: `${patientName} invited you to be their caregiver.`,
      };
    case 'DOCTOR_INVITE_RECEIVED':
      return {
        title: 'Doctor Connection Request',
        body: `${doctorName} wants to connect as your doctor on MediTrack. Review the request.`,
      };
    case 'DOCTOR_INVITE_ACCEPTED':
      return {
        title: 'Doctor Connection Accepted',
        body: `${patientName} accepted your doctor connection.`,
      };
    case 'DOCTOR_INVITE_DECLINED':
      return {
        title: 'Doctor Connection Declined',
        body: `${patientName} declined your doctor connection.`,
      };
    case 'DOCTOR_INVITE_REVOKED':
      return {
        title: 'Doctor Access Revoked',
        body: 'Doctor access has been revoked.',
      };
    case 'PRESCRIPTION_PROPOSAL_RECEIVED':
      return {
        title: 'New Prescription Proposal',
        body: `Dr ${doctorName} proposed a prescription for ${medicine}. Review and accept.`,
      };
    case 'PRESCRIPTION_ACCEPTED':
      return {
        title: 'Prescription Accepted',
        body: `${patientName} accepted the prescription proposal for ${medicine}.`,
      };
    case 'PRESCRIPTION_DECLINED':
      return {
        title: 'Prescription Declined',
        body: `${patientName} declined the prescription proposal for ${medicine}.`,
      };
    case 'PRESCRIPTION_CLARIFICATION_REQUESTED':
      return {
        title: 'Prescription Clarification Requested',
        body: `${patientName} requested clarification on ${medicine}.`,
      };
    case 'PRESCRIPTION_REVISED':
      return {
        title: 'Prescription Proposal Updated',
        body: `Dr ${doctorName} updated the prescription proposal for ${medicine}. Review the new revision.`,
      };
    default:
      return {
        title: 'MediTrack Alert',
        body: 'You have a new MediTrack notification.',
      };
  }
}
