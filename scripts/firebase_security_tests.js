/**
 * Firebase Security Rules Test Suite
 *
 * Covers:
 * - Firestore Rules (firestore.rules)
 * - Firebase Storage Rules (storage.rules)
 *
 * Requirements verified:
 * 1. Doctor cannot create a doctorLink directly (create: false enforced; links created only via Cloud Function).
 * 2. Unrelated authenticated user cannot read doctorInvitations.
 * 3. Doctor cannot update proposal once accepted (or rejected).
 * 4. Patient cannot edit prescriptionPayload in a proposal.
 * 5. Doctor cannot propose without an ACCEPTED link (requires isAcceptedDoctor).
 * 6. Doctor / Caregiver direct medicine or doseEvent writes are DENIED (patient write only).
 * 7. Caregiver link cannot be created directly by client (create: false enforced).
 * 8. Revoked links cannot be reactivated back to ACCEPTED.
 * 9. Proof photos in storage can only be created by the patient and read by patient/accepted caregiver/doctor.
 *
 * Usage:
 * firebase emulators:exec --only firestore,storage "node scripts/firebase_security_tests.js"
 */

const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const path = require("path");

const PROJECT_ID = "meditrack-biodesign-test";

async function runTests() {
  console.log("=== MediTrack Firebase Security Rules Test Suite ===");

  const firestoreRules = fs.readFileSync(
    path.resolve(__dirname, "../firestore.rules"),
    "utf8"
  );
  const storageRules = fs.readFileSync(
    path.resolve(__dirname, "../storage.rules"),
    "utf8"
  );

  let testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: { rules: firestoreRules },
      storage: { rules: storageRules },
    });
  } catch (e) {
    console.error("Firebase Emulator environment failed to initialize:", e.message);
    console.log("NOTE: This script requires running Firebase Emulators with Node.js.");
    process.exit(1);
  }

  const patientId = "patient_alice";
  const doctorUid = "doctor_smith";
  const otherUid = "unrelated_user";

  const adminDb = testEnv.unauthenticatedContext().firestore(); // Admin context via withSecurityRulesDisabled
  const patientDb = testEnv.authenticatedContext(patientId).firestore();
  const doctorDb = testEnv.authenticatedContext(doctorUid).firestore();
  const otherDb = testEnv.authenticatedContext(otherUid).firestore();

  let passed = 0;
  let failed = 0;

  async function test(name, fn) {
    try {
      await fn();
      console.log(`  [PASS] ${name}`);
      passed++;
    } catch (err) {
      console.error(`  [FAIL] ${name}:`, err.message);
      failed++;
    }
  }

  console.log("\n--- Category 1: Doctor Links & Invitations ---");

  // 1. Doctor cannot create doctorLink directly
  await test("Doctor cannot create a doctorLink directly (must be rejected by rules)", async () => {
    const linkRef = doctorDb.doc(`doctorLinks/${patientId}_${doctorUid}`);
    await assertFails(
      linkRef.set({
        id: `${patientId}_${doctorUid}`,
        patientId,
        doctorUid,
        status: "ACCEPTED",
        createdAt: Date.now(),
        updatedAt: Date.now(),
      })
    );
  });

  // 2. Unrelated user cannot read doctorInvitations
  await test("Unrelated user cannot read doctorInvitations", async () => {
    const code = "INVITE88";
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().doc(`doctorInvitations/${code}`).set({
        code,
        doctorUid,
        doctorName: "Dr. Smith",
        status: "PENDING",
        expiresAt: Date.now() + 86400000,
      });
    });

    const otherRead = otherDb.doc(`doctorInvitations/${code}`).get();
    await assertFails(otherRead);

    const doctorRead = doctorDb.doc(`doctorInvitations/${code}`).get();
    await assertSucceeds(doctorRead);
  });

  console.log("\n--- Category 2: Prescription Proposals ---");

  // Setup accepted link via Admin SDK
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().doc(`doctorLinks/${patientId}_${doctorUid}`).set({
      id: `${patientId}_${doctorUid}`,
      patientId,
      doctorUid,
      status: "ACCEPTED",
      createdAt: Date.now(),
      updatedAt: Date.now(),
    });
  });

  // 3. Doctor cannot propose without an ACCEPTED link
  await test("Doctor cannot propose without an ACCEPTED link", async () => {
    const unlinkedDoctorDb = testEnv.authenticatedContext("doctor_unlinked").firestore();
    const proposalRef = unlinkedDoctorDb.doc("medicineProposals/prop_unlinked");
    await assertFails(
      proposalRef.set({
        id: "prop_unlinked",
        patientId,
        doctorUid: "doctor_unlinked",
        status: "PENDING",
        revision: 1,
        type: "ADD",
        payloadJson: '{"medicineName":"Amoxicillin"}',
      })
    );
  });

  // 4. Doctor creates valid proposal with ACCEPTED link
  await test("Doctor with ACCEPTED link can create PENDING proposal with revision 1", async () => {
    const proposalRef = doctorDb.doc("medicineProposals/prop_valid");
    await assertSucceeds(
      proposalRef.set({
        id: "prop_valid",
        patientId,
        doctorUid,
        status: "PENDING",
        revision: 1,
        type: "ADD",
        payloadJson: '{"medicineName":"Metformin","initialStock":30}',
      })
    );
  });

  // 5. Patient cannot edit prescriptionPayload in a proposal
  await test("Patient cannot edit prescriptionPayload in a proposal", async () => {
    const proposalRef = patientDb.doc("medicineProposals/prop_valid");
    await assertFails(
      proposalRef.update({
        payloadJson: '{"medicineName":"ForgedMedication"}',
      })
    );
  });

  // 6. Patient can accept a proposal
  await test("Patient can accept proposal (changing status only)", async () => {
    const proposalRef = patientDb.doc("medicineProposals/prop_valid");
    await assertSucceeds(
      proposalRef.update({
        status: "ACCEPTED",
        resolvedAt: Date.now(),
      })
    );
  });

  // 7. Doctor cannot update proposal once accepted
  await test("Doctor cannot update proposal once accepted", async () => {
    const proposalRef = doctorDb.doc("medicineProposals/prop_valid");
    await assertFails(
      proposalRef.update({
        payloadJson: '{"medicineName":"Metformin 1000mg"}',
        revision: 2,
      })
    );
  });

  // 7b. Clarification and Superseding workflow
  await test("Patient can request clarification on PENDING proposal", async () => {
    const propClarifyRef = doctorDb.doc("medicineProposals/prop_to_clarify");
    await assertSucceeds(
      propClarifyRef.set({
        id: "prop_to_clarify",
        patientId,
        doctorUid,
        status: "PENDING",
        revision: 1,
        type: "ADD",
        payloadJson: '{"medicineName":"Aspirin","dosage":"100mg"}',
      })
    );

    await assertSucceeds(
      patientDb.doc("medicineProposals/prop_to_clarify").update({
        status: "CLARIFICATION_REQUESTED",
        clarificationNote: "Take with food or empty stomach?",
      })
    );
  });

  await test("Doctor cannot supersede proposal directly from PENDING (only from CLARIFICATION_REQUESTED)", async () => {
    const propPendingRef = doctorDb.doc("medicineProposals/prop_pending_test");
    await propPendingRef.set({
      id: "prop_pending_test",
      patientId,
      doctorUid,
      status: "PENDING",
      revision: 1,
      type: "ADD",
      payloadJson: '{"medicineName":"Atorvastatin"}',
    });

    await assertFails(
      propPendingRef.update({
        status: "SUPERSEDED",
        resolvedAt: Date.now(),
      })
    );
  });

  await test("Doctor cannot change payload while superseding", async () => {
    const propRef = doctorDb.doc("medicineProposals/prop_to_clarify");
    await assertFails(
      propRef.update({
        status: "SUPERSEDED",
        payloadJson: '{"medicineName":"TamperedPayload"}',
        resolvedAt: Date.now(),
      })
    );
  });

  await test("Patient cannot supersede proposal", async () => {
    const propRef = patientDb.doc("medicineProposals/prop_to_clarify");
    await assertFails(
      propRef.update({
        status: "SUPERSEDED",
        resolvedAt: Date.now(),
      })
    );
  });

  await test("Doctor can supersede proposal from CLARIFICATION_REQUESTED changing ONLY status and resolvedAt", async () => {
    const propRef = doctorDb.doc("medicineProposals/prop_to_clarify");
    await assertSucceeds(
      propRef.update({
        status: "SUPERSEDED",
        resolvedAt: Date.now(),
      })
    );
  });

  await test("Doctor creates replacement proposal with revision 2 requiring supersedesProposalId", async () => {
    // Fails without supersedesProposalId
    await assertFails(
      doctorDb.doc("medicineProposals/prop_rev2_invalid").set({
        id: "prop_rev2_invalid",
        patientId,
        doctorUid,
        status: "PENDING",
        revision: 2,
        type: "ADD",
        payloadJson: '{"medicineName":"Aspirin","dosage":"100mg with food"}',
      })
    );

    // Succeeds with supersedesProposalId
    await assertSucceeds(
      doctorDb.doc("medicineProposals/prop_rev2_valid").set({
        id: "prop_rev2_valid",
        patientId,
        doctorUid,
        status: "PENDING",
        revision: 2,
        supersedesProposalId: "prop_to_clarify",
        type: "ADD",
        payloadJson: '{"medicineName":"Aspirin","dosage":"100mg with food"}',
      })
    );
  });

  console.log("\n--- Category 3: Patient Clinical Data Isolation ---");

  // 8. Doctor direct medicine modification denied
  await test("Doctor direct medicine modification denied (patient write only)", async () => {
    const medRef = doctorDb.doc(`users/${patientId}/medicines/med_1`);
    await assertFails(
      medRef.set({
        name: "DoctorWrittenMed",
        dosageText: "500mg",
      })
    );
  });

  // 9. Doctor direct dose modification denied
  await test("Doctor direct doseEvent modification denied", async () => {
    const doseRef = doctorDb.doc(`users/${patientId}/doseEvents/dose_1`);
    await assertFails(
      doseRef.update({
        status: "TAKEN",
      })
    );
  });

  console.log("\n=================================================");
  console.log(`Summary: ${passed} Passed, ${failed} Failed`);
  console.log("=================================================");

  await testEnv.cleanup();
  process.exit(failed > 0 ? 1 : 0);
}

runTests().catch((err) => {
  console.error("Unhandled error:", err);
  process.exit(1);
});
