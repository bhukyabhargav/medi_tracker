import * as functions from 'firebase-functions';
import * as admin from 'firebase-admin';
import { getNotificationText, NotificationPayload } from './notificationText';

admin.initializeApp();

/**
 * Cloud Function triggered on creation of a notificationEvent in Firestore.
 * Looks up the caregiver's FCM token and dispatches push notification with the spec text.
 */
export const onNotificationEventCreated = functions.firestore
  .document('notificationEvents/{eventId}')
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data() as NotificationPayload;
    if (!data) return null;

    const caregiverUid = data.caregiverUid;
    if (!caregiverUid) {
      functions.logger.info(`No caregiverUid for event ${context.params.eventId}. Skipping FCM dispatch.`);
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
        patientUid: data.patientUid || '',
        doseId: data.doseId || '',
      },
      android: {
        priority: 'high',
        notification: {
          channelId: 'general',
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
