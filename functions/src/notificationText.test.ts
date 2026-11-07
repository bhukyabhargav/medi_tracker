import { getNotificationText } from './notificationText';

describe('Notification Text Builder - Stage 10 Spec Acceptance', () => {
  test('MISSED_DOSE produces exact specified text', () => {
    const text = getNotificationText({ type: 'MISSED_DOSE' });
    expect(text.body).toBe('Medicine dose has been missed.');
  });

  test('LOW_STOCK produces exact specified text with medicine name', () => {
    const text = getNotificationText({
      type: 'LOW_STOCK',
      medicineName: 'Metformin',
    });
    expect(text.body).toBe('Metformin is running low \u2013 refill needed.');
  });

  test('DOSE_UNVERIFIED produces exact specified text', () => {
    const text = getNotificationText({ type: 'DOSE_UNVERIFIED' });
    expect(text.body).toBe('Dose could not be verified.');
  });

  test('DOSE_TAKEN_LATE produces exact specified text', () => {
    const text = getNotificationText({ type: 'DOSE_TAKEN_LATE' });
    expect(text.body).toBe('Missed dose was taken late (verified).');
  });

  test('CAREGIVER_INVITE produces exact specified text with patient name', () => {
    const text = getNotificationText({
      type: 'CAREGIVER_INVITE',
      patientName: 'John',
    });
    expect(text.body).toBe('John invited you to be their caregiver.');
  });

  test('DOCTOR_INVITE_RECEIVED produces expected text', () => {
    const text = getNotificationText({
      type: 'DOCTOR_INVITE_RECEIVED',
      doctorName: 'Dr. Smith',
    });
    expect(text.title).toBe('Doctor Connection Request');
    expect(text.body).toContain('Dr. Smith');
  });

  test('PRESCRIPTION_PROPOSAL_RECEIVED produces expected text', () => {
    const text = getNotificationText({
      type: 'PRESCRIPTION_PROPOSAL_RECEIVED',
      doctorName: 'Smith',
      medicineName: 'Amoxicillin',
    });
    expect(text.title).toBe('Prescription Proposal');
    expect(text.body).toBe('Dr Smith proposed a prescription for Amoxicillin. Review and accept.');
  });

  test('PRESCRIPTION_ACCEPTED produces expected text', () => {
    const text = getNotificationText({
      type: 'PRESCRIPTION_ACCEPTED',
      patientName: 'Alice',
      medicineName: 'Amoxicillin',
    });
    expect(text.title).toBe('Prescription Accepted');
    expect(text.body).toBe('Alice accepted the prescription proposal for Amoxicillin.');
  });

  test('PRESCRIPTION_CLARIFICATION_REQUESTED produces expected text', () => {
    const text = getNotificationText({
      type: 'PRESCRIPTION_CLARIFICATION_REQUESTED',
      patientName: 'Alice',
      medicineName: 'Amoxicillin',
    });
    expect(text.title).toBe('Clarification Requested');
    expect(text.body).toBe('Alice requested clarification on Amoxicillin.');
  });

  test('PRESCRIPTION_REVISED produces expected text', () => {
    const text = getNotificationText({
      type: 'PRESCRIPTION_REVISED',
      doctorName: 'Smith',
      medicineName: 'Amoxicillin',
    });
    expect(text.title).toBe('Prescription Revised');
    expect(text.body).toBe('Dr Smith updated the prescription proposal for Amoxicillin. Review the new revision.');
  });
});
