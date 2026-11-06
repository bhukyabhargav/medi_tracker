import * as functions from 'firebase-functions';
import * as admin from 'firebase-admin';
import { getNotificationText, NotificationPayload } from './notificationText';

admin.initializeApp();

/**
 * Cloud Function triggered on creation of a notificationEvent in Firestore.
 * Performs server-side fanout to all accepted caregiver or doctor FCM tokens with appropriate channel routing.
 */
export const onNotificationEventCreated = functions.firestore
  .document('notificationEvents/{eventId}')
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data() as NotificationPayload;
    if (!data) return null;

    const patientUid = data.patientUid;
    const { title, body } = getNotificationText(data);
    const channelId = data.type === 'MISSED_DOSE' ? 'channel_missed_doses' : 'channel_general';

    // Handle Doctor Notification Events
    if (data.type.startsWith('DOCTOR_INVITE_') || data.type.startsWith('PRESCRIPTION_')) {
      const targetUid = (data.type === 'DOCTOR_INVITE_RECEIVED' || data.type === 'PRESCRIPTION_PROPOSAL_RECEIVED' || data.type === 'PRESCRIPTION_REVISED')
        ? patientUid
        : (data.doctorUid || null);

      if (!targetUid) {
        functions.logger.info(`No recipient UID for doctor event ${context.params.eventId}.`);
        return null;
      }

      // Security check: If prescription event, strictly verify an active ACCEPTED doctor link
      if (data.type.startsWith('PRESCRIPTION_')) {
        if (!patientUid || !data.doctorUid) {
          functions.logger.warn(`Missing patient or doctor UID for prescription event ${context.params.eventId}.`);
          return null;
        }
        const linkDoc = await admin.firestore().doc(`doctorLinks/${patientUid}_${data.doctorUid}`).get();
        if (!linkDoc.exists || linkDoc.data()?.status !== 'ACCEPTED') {
          functions.logger.warn(`Rejected unverified prescription event ${context.params.eventId}: No active accepted link between patient ${patientUid} and doctor ${data.doctorUid}.`);
          return null;
        }
      }

      // Security check: Fetch trusted doctor and patient names from users collection to prevent phishing
      let trustedDoctorName = 'Doctor';
      if (data.doctorUid) {
        const docUser = await admin.firestore().doc(`users/${data.doctorUid}`).get();
        trustedDoctorName = docUser.data()?.name || 'Doctor';
      }

      let trustedPatientName = 'A patient';
      if (patientUid) {
        const patUser = await admin.firestore().doc(`users/${patientUid}`).get();
        trustedPatientName = patUser.data()?.name || 'A patient';
      }

      const sanitizedPayload: NotificationPayload = {
        ...data,
        doctorName: trustedDoctorName,
        patientName: trustedPatientName,
      };

      const { title, body } = getNotificationText(sanitizedPayload);

      const targetDoc = await admin.firestore().doc(`users/${targetUid}`).get();
      const fcmToken = targetDoc.data()?.fcmToken;
      if (!fcmToken) {
        functions.logger.warn(`No FCM token found for user ${targetUid}.`);
        return null;
      }

      const message: admin.messaging.Message = {
        token: fcmToken,
        notification: { title, body },
        data: {
          eventId: context.params.eventId,
          type: data.type,
          patientUid: patientUid || '',
          proposalId: data.proposalId || '',
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
        functions.logger.info(`Delivered push to ${targetUid}: ${response}`);
        return response;
      } catch (error) {
        functions.logger.error(`Error sending push notification to ${targetUid}:`, error);
        return null;
      }
    }

    // Handle Caregiver Notification Events
    if (!patientUid) {
      functions.logger.info(`Missing patientUid for event ${context.params.eventId}. Skipping FCM dispatch.`);
      return null;
    }

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

    const dispatchPromises = targetCaregiverUids.map(async (caregiverUid) => {
      const caregiverDoc = await admin.firestore().doc(`users/${caregiverUid}`).get();
      const fcmToken = caregiverDoc.data()?.fcmToken;
      if (!fcmToken) {
        functions.logger.warn(`No FCM token found for caregiver ${caregiverUid}.`);
        return null;
      }

      const message: admin.messaging.Message = {
        token: fcmToken,
        notification: { title, body },
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

/**
 * Callable Cloud Function to accept a doctor invitation code (Stage 10B).
 */
export const acceptDoctorInvite = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'User must be signed in to accept an invitation.');
  }

  const patientUid = context.auth.uid;
  const code = (data?.code || '').trim().toUpperCase();

  if (!code || code.length < 6) {
    throw new functions.https.HttpsError('invalid-argument', 'Invalid doctor invitation code.');
  }

  // Find invitation in doctorInvitations or doctorLinks
  const inviteQuery = await admin.firestore()
    .collection('doctorInvitations')
    .where('inviteCode', '==', code)
    .limit(1)
    .get();

  let inviteData: any = null;
  let inviteDocRef: admin.firestore.DocumentReference | null = null;

  if (!inviteQuery.empty) {
    inviteDocRef = inviteQuery.docs[0].ref;
    inviteData = inviteQuery.docs[0].data();
  } else {
    // Check doctorLinks by invitationCode
    const linkQuery = await admin.firestore()
      .collection('doctorLinks')
      .where('invitationCode', '==', code)
      .limit(1)
      .get();
    if (!linkQuery.empty) {
      inviteDocRef = linkQuery.docs[0].ref;
      inviteData = linkQuery.docs[0].data();
    }
  }

  if (!inviteData) {
    throw new functions.https.HttpsError('not-found', 'Doctor invitation code not found.');
  }

  const now = Date.now();
  if (inviteData.expiresAt && now > inviteData.expiresAt) {
    throw new functions.https.HttpsError('failed-precondition', 'Doctor invitation code has expired.');
  }

  const doctorUid = inviteData.doctorUid;
  if (!doctorUid || doctorUid === patientUid) {
    throw new functions.https.HttpsError('invalid-argument', 'Invalid doctor invitation reference.');
  }

  const linkId = `${patientUid}_${doctorUid}`;
  const linkRef = admin.firestore().doc(`doctorLinks/${linkId}`);

  const batch = admin.firestore().batch();
  batch.set(linkRef, {
    id: linkId,
    patientId: patientUid,
    doctorUid: doctorUid,
    doctorName: inviteData.doctorName || 'Doctor',
    doctorContact: inviteData.doctorContact || '',
    status: 'ACCEPTED',
    createdAt: inviteData.createdAt || now,
    updatedAt: now,
  });

  if (inviteDocRef) {
    batch.update(inviteDocRef, {
      status: 'ACCEPTED',
      patientId: patientUid,
      updatedAt: now,
    });
  }

  await batch.commit();

  functions.logger.info(`Patient ${patientUid} accepted doctor ${doctorUid} invitation. Link: ${linkId}`);

  return {
    success: true,
    linkId,
    doctorUid,
  };
});
