export type NotifType =
  | 'MISSED_DOSE'
  | 'LOW_STOCK'
  | 'DOSE_UNVERIFIED'
  | 'DOSE_TAKEN_LATE'
  | 'CAREGIVER_INVITE';

export interface NotificationPayload {
  type: NotifType;
  patientUid?: string;
  patientName?: string;
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
        body: `${name} invited you to be their caregiver.`,
      };
    default:
      return {
        title: 'MediTrack Alert',
        body: 'You have a new MediTrack notification.',
      };
  }
}
