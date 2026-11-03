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
});
