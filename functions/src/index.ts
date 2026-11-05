import * as functions from 'firebase-functions';
import * as admin from 'firebase-admin';
import { getNotificationText, NotificationPayload } from './notificationText';

admin.initializeApp();

/**
 * Cloud Function triggered on creation of a notificationEvent in Firestore.
 * Verifies that an accepted caregiver link exists, then looks up the caregiver's
 * FCM token and dispatches push notification on channel_general.
 */
export const onNotificationEventCreated = functions.firestore
  .document('notificationEvents/{eventId}')
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data() as NotificationPayload;
    if (!data) return null;

    const patientUid = data.patientUid;
    const caregiverUid = data.caregiverUid;
    if (!patientUid || !caregiverUid) {
      functions.logger.info(`Missing patientUid or caregiverUid for event ${context.params.eventId}. Skipping FCM dispatch.`);
      return null;
    }

    // Verify accepted caregiver link in Firestore
    const linkDoc = await admin.firestore().doc(`caregiverLinks/${patientUid}_${caregiverUid}`).get();
    if (!linkDoc.exists || linkDoc.data()?.status !== 'ACCEPTED') {
      functions.logger.warn(`No accepted caregiver link found between patient ${patientUid} and caregiver ${caregiverUid}. Dropping push.`);
      return null;
    }

    // Lookup caregiver's FCM token from /users/{caregiverUid}
    const caregiverDoc = await admin.firestore().doc(`users/${caregiverUid}`).get();
    const fcmToken = caregiverDoc.data()?.fcmToken;

    if (!fcmToken) {
      functions.logger.warn(`No FCM token found for caregiver ${caregiverUid}.`);
      return null;
    }

    const { title, body } = getNotificationText(data);

    const message: admin.messaging.Message = {
      token: fcmToken,
      notification: {
        title,
        body,
      },
      data: {
        eventId: context.params.eventId,
        type: data.type,
        patientUid: patientUid,
        doseId: data.doseId || '',
      },
      android: {
        priority: 'high',
        notification: {
          channelId: 'channel_general',
          priority: 'high',
        },
      },
    };

    try {
      const response = await admin.messaging().send(message);
      functions.logger.info(`Successfully delivered push to caregiver ${caregiverUid}: ${response}`);
      return response;
    } catch (error) {
      functions.logger.error(`Error sending push notification to caregiver ${caregiverUid}:`, error);
      return null;
    }
  });
