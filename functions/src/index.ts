import * as functions from 'firebase-functions';
import * as admin from 'firebase-admin';
import { getNotificationText, NotificationPayload, NotifType } from './notificationText';

admin.initializeApp();

const INVITE_CODE_PATTERN = /^[A-Z0-9]{6,12}$/;

/** Android notification channel per event type (must match NotificationChannels in the app). */
function channelFor(type: string): string {
  return type === 'MISSED_DOSE' ? 'channel_missed_doses' : 'channel_general';
}

/**
 * Callable: a caregiver redeems an invitation code created by a patient.
 * Validates the invite (exists, PENDING, not expired, not self) and creates the ACCEPTED link with the
 * Admin SDK. Clients can never create links themselves (see firestore.rules).
 */
export const acceptCaregiverInvite = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first.');
  }
  const caregiverUid = context.auth.uid;
  const code = String(data?.code ?? '').trim().toUpperCase();
  if (!INVITE_CODE_PATTERN.test(code)) {
    throw new functions.https.HttpsError('invalid-argument', 'Invalid invitation code.');
  }
  const caregiverName = String(data?.caregiverName ?? '').trim().slice(0, 80) || 'Caregiver';

  const db = admin.firestore();
  const inviteRef = db.doc(`caregiverInvites/${code}`);

  return db.runTransaction(async (tx) => {
    const invite = await tx.get(inviteRef);
    if (!invite.exists) {
      throw new functions.https.HttpsError('not-found', 'Invitation not found.');
    }
    const inv = invite.data() as {
      patientUid?: string;
      patientId?: string;
      patientName?: string;
      relationship?: string;
      status: string;
      used?: boolean;
      expiresAt: number;
    };
    const patientId = inv.patientId || inv.patientUid || '';
    if (inv.status !== 'PENDING' || inv.used === true) {
      throw new functions.https.HttpsError('failed-precondition', 'Invitation was already used or closed.');
    }
    if (inv.expiresAt <= Date.now()) {
      throw new functions.https.HttpsError('deadline-exceeded', 'Invitation has expired.');
    }
    if (patientId === caregiverUid) {
      throw new functions.https.HttpsError('invalid-argument', 'You cannot be your own caregiver.');
    }

    const linkId = `${patientId}_${caregiverUid}`;
    const now = Date.now();
    tx.set(db.doc(`caregiverLinks/${linkId}`), {
      id: linkId,
      patientId,
      caregiverUid,
      caregiverName,
      caregiverContact: context.auth?.token?.email ?? context.auth?.token?.phone_number ?? '',
      relationship: inv.relationship ?? '',
      status: 'ACCEPTED',
      createdAt: now,
      updatedAt: now,
    });
    tx.update(inviteRef, { status: 'USED', used: true, usedBy: caregiverUid, usedAt: now });

    return { linkId, patientId, patientName: inv.patientName ?? 'Patient' };
  });
});

/**
 * Callable A1: a patient redeems a doctor's invitation code.
 * Validates the code, ensures unexpired and single-use, validates optional patientContact match,
 * and creates doctorLinks/{patientId}_{doctorUid} with status ACCEPTED using Admin SDK.
 */
export const acceptDoctorInvite = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first.');
  }
  const patientId = context.auth.uid;
  const code = String(data?.code ?? '').trim().toUpperCase();
  if (!INVITE_CODE_PATTERN.test(code)) {
    throw new functions.https.HttpsError('invalid-argument', 'Invalid doctor invitation code format.');
  }

  const db = admin.firestore();
  const inviteRef = db.doc(`doctorInvitations/${code}`);

  return db.runTransaction(async (tx) => {
    const invite = await tx.get(inviteRef);
    if (!invite.exists) {
      throw new functions.https.HttpsError('not-found', 'Doctor invitation not found.');
    }
    const inv = invite.data() as {
      doctorUid: string;
      doctorName?: string;
      doctorContact?: string;
      patientContact?: string;
      status: string;
      used?: boolean;
      expiresAt: number;
    };

    if (inv.status !== 'PENDING' || inv.used === true) {
      throw new functions.https.HttpsError('failed-precondition', 'Invitation is no longer valid or already used.');
    }
    if (inv.expiresAt <= Date.now()) {
      throw new functions.https.HttpsError('deadline-exceeded', 'Doctor invitation has expired.');
    }
    if (inv.doctorUid === patientId) {
      throw new functions.https.HttpsError('invalid-argument', 'Doctor cannot accept their own invitation.');
    }

    // Verify contact matching if the doctor specified an intended patient email or phone
    if (inv.patientContact && inv.patientContact.trim().length > 0) {
      const expectedContact = inv.patientContact.trim().toLowerCase();
      const callerEmail = String(context.auth?.token?.email ?? '').trim().toLowerCase();
      const callerPhone = String(context.auth?.token?.phone_number ?? '').trim();
      const matchesEmail = callerEmail.length > 0 && callerEmail === expectedContact;
      const matchesPhone = callerPhone.length > 0 && callerPhone === inv.patientContact.trim();
      if (!matchesEmail && !matchesPhone) {
        throw new functions.https.HttpsError('permission-denied', 'Invitation is restricted to a different patient contact.');
      }
    }

    const linkId = `${patientId}_${inv.doctorUid}`;
    const now = Date.now();

    tx.set(db.doc(`doctorLinks/${linkId}`), {
      id: linkId,
      patientId,
      doctorUid: inv.doctorUid,
      doctorName: inv.doctorName ?? 'Doctor',
      doctorContact: inv.doctorContact ?? '',
      status: 'ACCEPTED',
      invitationCode: code,
      createdAt: now,
      updatedAt: now,
    });

    tx.update(inviteRef, {
      status: 'ACCEPTED',
      used: true,
      patientId,
      acceptedAt: now,
      updatedAt: now,
    });

    return {
      linkId,
      doctorUid: inv.doctorUid,
      doctorName: inv.doctorName ?? 'Doctor',
    };
  });
});

/**
 * Callable A2: a patient declines a doctor invitation code.
 * Closes the invitation without establishing any doctor link.
 */
export const declineDoctorInvite = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first.');
  }
  const patientId = context.auth.uid;
  const code = String(data?.code ?? '').trim().toUpperCase();
  if (!INVITE_CODE_PATTERN.test(code)) {
    throw new functions.https.HttpsError('invalid-argument', 'Invalid doctor invitation code format.');
  }

  const db = admin.firestore();
  const inviteRef = db.doc(`doctorInvitations/${code}`);

  return db.runTransaction(async (tx) => {
    const invite = await tx.get(inviteRef);
    if (!invite.exists) {
      throw new functions.https.HttpsError('not-found', 'Doctor invitation not found.');
    }
    const inv = invite.data() as {
      doctorUid: string;
      patientContact?: string;
      status: string;
      used?: boolean;
      expiresAt: number;
    };

    if (inv.status !== 'PENDING' || inv.used === true) {
      throw new functions.https.HttpsError('failed-precondition', 'Invitation is already closed or used.');
    }
    if (inv.expiresAt <= Date.now()) {
      throw new functions.https.HttpsError('deadline-exceeded', 'Doctor invitation has expired.');
    }

    // Verify contact matching if specified
    if (inv.patientContact && inv.patientContact.trim().length > 0) {
      const expectedContact = inv.patientContact.trim().toLowerCase();
      const callerEmail = String(context.auth?.token?.email ?? '').trim().toLowerCase();
      const callerPhone = String(context.auth?.token?.phone_number ?? '').trim();
      const matchesEmail = callerEmail.length > 0 && callerEmail === expectedContact;
      const matchesPhone = callerPhone.length > 0 && callerPhone === inv.patientContact.trim();
      if (!matchesEmail && !matchesPhone) {
        throw new functions.https.HttpsError('permission-denied', 'Invitation is restricted to a different patient contact.');
      }
    }

    const now = Date.now();
    tx.update(inviteRef, {
      status: 'DECLINED',
      used: true,
      patientId,
      declinedAt: now,
      updatedAt: now,
    });

    return { success: true };
  });
});

/**
 * Triggered on creation of a notificationEvent.
 * A3: Client payload is NEVER blindly trusted. Validates server-side relationship before sending push.
 * Server-side names for doctor and patient are loaded from users/{uid}.
 */
export const onNotificationEventCreated = functions.firestore
  .document('notificationEvents/{eventId}')
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data() as NotificationPayload & { patientId?: string; doctorUid?: string };
    if (!data) return null;

    const patientId = data.patientId || data.patientUid;
    const caregiverUid = data.caregiverUid;
    const doctorUid = data.doctorUid;
    const type: NotifType = data.type;
    const db = admin.firestore();

    let targetUid: string | null = null;

    // 1. Doctor-related events
    if (type.startsWith('DOCTOR_INVITE_') || type.startsWith('PRESCRIPTION_')) {
      if (!patientId || !doctorUid) {
        functions.logger.warn(`Doctor event ${type} missing patientId or doctorUid. Dropping.`);
        return null;
      }

      // Events targeted at Patient from Doctor
      if (type === 'DOCTOR_INVITE_RECEIVED') {
        const inviteQuery = await db.collection('doctorInvitations')
          .where('doctorUid', '==', doctorUid)
          .where('used', '==', false)
          .limit(1)
          .get();
        if (inviteQuery.empty || (inviteQuery.docs[0].data()?.expiresAt ?? 0) <= Date.now()) {
          functions.logger.warn(`No valid pending invite found for doctor ${doctorUid}. Dropping push.`);
          return null;
        }
        targetUid = patientId;
      } else if (type === 'PRESCRIPTION_PROPOSAL_RECEIVED' || type === 'PRESCRIPTION_REVISED') {
        const linkDoc = await db.doc(`doctorLinks/${patientId}_${doctorUid}`).get();
        if (!linkDoc.exists || linkDoc.data()?.status !== 'ACCEPTED') {
          functions.logger.warn(`No accepted link between patient ${patientId} and doctor ${doctorUid}. Dropping proposal push.`);
          return null;
        }
        targetUid = patientId;
      } else {
        // Events targeted at Doctor from Patient (acceptance, decline, clarification, revocation)
        const linkDoc = await db.doc(`doctorLinks/${patientId}_${doctorUid}`).get();
        if (type === 'DOCTOR_INVITE_DECLINED') {
          // Link may not exist for declined invite; check doctor invitation
          const invQuery = await db.collection('doctorInvitations')
            .where('doctorUid', '==', doctorUid)
            .where('patientId', '==', patientId)
            .limit(1)
            .get();
          if (invQuery.empty && !linkDoc.exists) {
            functions.logger.warn(`No relation found for decline notification to doctor ${doctorUid}. Dropping.`);
            return null;
          }
        } else {
          if (!linkDoc.exists) {
            functions.logger.warn(`No doctor link found between patient ${patientId} and doctor ${doctorUid}. Dropping.`);
            return null;
          }
        }
        targetUid = doctorUid;
      }
    } else {
      // 2. Caregiver events
      if (!patientId || !caregiverUid) {
        functions.logger.info(`Missing patientId or caregiverUid for event ${context.params.eventId}. Skipping.`);
        return null;
      }
      const linkDoc = await db.doc(`caregiverLinks/${patientId}_${caregiverUid}`).get();
      if (!linkDoc.exists || linkDoc.data()?.status !== 'ACCEPTED') {
        functions.logger.warn(`No accepted caregiver link between patient ${patientId} and caregiver ${caregiverUid}. Dropping push.`);
        return null;
      }
      targetUid = caregiverUid;
    }

    if (!targetUid) return null;

    // Load server-side names and recipient token
    const targetUserDoc = await db.doc(`users/${targetUid}`).get();
    const fcmToken = targetUserDoc.data()?.fcmToken;
    if (!fcmToken) {
      functions.logger.warn(`No FCM token found for user ${targetUid}.`);
      return null;
    }

    let serverPatientName: string | undefined = undefined;
    if (patientId) {
      const pDoc = await db.doc(`users/${patientId}`).get();
      serverPatientName = (pDoc.data()?.name as string | undefined) ?? undefined;
    }

    let serverDoctorName: string | undefined = undefined;
    if (doctorUid) {
      const dDoc = await db.doc(`users/${doctorUid}`).get();
      serverDoctorName = (dDoc.data()?.name as string | undefined) ?? undefined;
    }

    const { title, body } = getNotificationText({
      ...data,
      patientName: serverPatientName ?? data.patientName,
      doctorName: serverDoctorName ?? data.doctorName,
    });

    const message: admin.messaging.Message = {
      token: fcmToken,
      notification: { title, body },
      data: {
        eventId: context.params.eventId,
        type: data.type,
        patientUid: patientId || '',
        doseId: data.doseId || '',
      },
      android: {
        priority: 'high',
        notification: { channelId: channelFor(data.type), priority: 'high' },
      },
    };

    try {
      const response = await admin.messaging().send(message);
      functions.logger.info(`Delivered push notification to user ${targetUid}: ${response}`);
      return response;
    } catch (error: any) {
      const code = error?.code as string | undefined;
      if (code === 'messaging/registration-token-not-registered' || code === 'messaging/invalid-registration-token') {
        await db.doc(`users/${targetUid}`).update({ fcmToken: admin.firestore.FieldValue.delete() });
        functions.logger.warn(`Removed stale FCM token for user ${targetUid}.`);
        return null;
      }
      functions.logger.error(`Error sending push notification to user ${targetUid}:`, error);
      return null;
    }
  });
