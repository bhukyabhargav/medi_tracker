import * as functions from 'firebase-functions';
import * as admin from 'firebase-admin';
import { getNotificationText, NotificationPayload } from './notificationText';

admin.initializeApp();

/**
 * Cloud Function triggered on creation of a notificationEvent in Firestore.
 * Performs server-side fanout to all accepted caregiver FCM tokens with appropriate channel routing.
 */
export const onNotificationEventCreated = functions.firestore
  .document('notificationEvents/{eventId}')
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data() as NotificationPayload;
    if (!data) return null;

    const patientUid = data.patientUid;
    if (!patientUid) {
      functions.logger.info(`Missing patientUid for event ${context.params.eventId}. Skipping FCM dispatch.`);
      return null;
    }

    // Determine target caregivers (specific caregiver if specified, otherwise fanout to all accepted caregivers)
    let targetCaregiverUids: string[] = [];
    if (data.caregiverUid) {
      const linkDoc = await admin.firestore().doc(`caregiverLinks/${patientUid}_${data.caregiverUid}`).get();
      if (linkDoc.exists && linkDoc.data()?.status === 'ACCEPTED') {
        targetCaregiverUids.push(data.caregiverUid);
      } else {
        functions.logger.warn(`No accepted caregiver link between ${patientUid} and ${data.caregiverUid}.`);
      }
    } else {
      const linksSnap = await admin.firestore()
        .collection('caregiverLinks')
        .where('patientId', '==', patientUid)
        .where('status', '==', 'ACCEPTED')
        .get();
      targetCaregiverUids = linksSnap.docs.map(doc => doc.data().caregiverUid).filter(Boolean);
    }

    if (targetCaregiverUids.length === 0) {
      functions.logger.info(`No accepted caregivers found to notify for patient ${patientUid}.`);
      return null;
    }

    const { title, body } = getNotificationText(data);
    const channelId = data.type === 'MISSED_DOSE' ? 'channel_missed_doses' : 'channel_general';

    const dispatchPromises = targetCaregiverUids.map(async (caregiverUid) => {
      const caregiverDoc = await admin.firestore().doc(`users/${caregiverUid}`).get();
      const fcmToken = caregiverDoc.data()?.fcmToken;
      if (!fcmToken) {
        functions.logger.warn(`No FCM token found for caregiver ${caregiverUid}.`);
        return null;
      }

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
            channelId,
            priority: 'high',
          },
        },
      };

      try {
        const response = await admin.messaging().send(message);
        functions.logger.info(`Delivered push to caregiver ${caregiverUid}: ${response}`);
        return response;
      } catch (error) {
        functions.logger.error(`Error sending push notification to caregiver ${caregiverUid}:`, error);
        return null;
      }
    });

    return Promise.all(dispatchPromises);
  });

/**
 * Callable Cloud Function to accept a caregiver invitation code.
 * Validates code, 72-hour expiration, and creates accepted link in caregiverLinks/{patientId}_{caregiverUid}.
 */
export const acceptCaregiverInvite = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'User must be signed in to accept an invitation.');
  }

  const caregiverUid = context.auth.uid;
  const code = (data?.code || '').trim().toUpperCase();

  if (!code || code.length < 6) {
    throw new functions.https.HttpsError('invalid-argument', 'Invalid invitation code format.');
  }

  const inviteRef = admin.firestore().doc(`caregiverInvites/${code}`);
  const inviteDoc = await inviteRef.get();

  if (!inviteDoc.exists) {
    throw new functions.https.HttpsError('not-found', 'Invitation code not found.');
  }

  const inviteData = inviteDoc.data();
  if (!inviteData) {
    throw new functions.https.HttpsError('not-found', 'Empty invitation data.');
  }

  const now = Date.now();
  if (inviteData.expiresAt && now > inviteData.expiresAt) {
    throw new functions.https.HttpsError('failed-precondition', 'Invitation code has expired.');
  }

  if (inviteData.status !== 'PENDING') {
    throw new functions.https.HttpsError('failed-precondition', 'Invitation code has already been used.');
  }

  const patientUid = inviteData.patientUid;
  if (!patientUid || patientUid === caregiverUid) {
    throw new functions.https.HttpsError('invalid-argument', 'Cannot accept your own invitation.');
  }

  // Lookup caregiver details
  const caregiverUserDoc = await admin.firestore().doc(`users/${caregiverUid}`).get();
  const caregiverName = caregiverUserDoc.data()?.name || inviteData.caregiverName || 'Caregiver';
  const caregiverContact = caregiverUserDoc.data()?.phone || caregiverUserDoc.data()?.email || inviteData.caregiverContact || '';

  const linkId = `${patientUid}_${caregiverUid}`;
  const linkRef = admin.firestore().doc(`caregiverLinks/${linkId}`);

  const batch = admin.firestore().batch();
  batch.set(linkRef, {
    id: linkId,
    patientId: patientUid,
    caregiverUid: caregiverUid,
    caregiverName,
    caregiverContact,
    relationship: inviteData.relationship || 'Caregiver',
    status: 'ACCEPTED',
    createdAt: now,
  });

  batch.update(inviteRef, {
    status: 'ACCEPTED',
    acceptedBy: caregiverUid,
    acceptedAt: now,
  });

  await batch.commit();

  functions.logger.info(`Caregiver ${caregiverUid} accepted invite ${code} for patient ${patientUid}. Link: ${linkId}`);

  return {
    success: true,
    linkId,
    patientId: patientUid,
  };
});
