const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const serverTimestamp = () => new Date();

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_owner_123";
const ALICE_EMAIL = "alice@business1.ng";
const BOB_UID = "bob_owner_456";
const BOB_EMAIL = "bob@business2.ng";
const ADMIN_UID = "super_admin_1803";
const ADMIN_EMAIL = "info.1803consult@gmail.com";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function dbFor(uid, email, emailVerified = true) {
  return testEnv
    .authenticatedContext(uid, { email, email_verified: emailVerified })
    .firestore();
}

function baseBusiness(ownerUid, memberUids = [ownerUid]) {
  return {
    ownerUid,
    memberUids,
    registeredName: "Zaria Ventures Ltd",
    tradingName: "Zaria Ventures",
    defaultCurrency: "NGN",
    receiptPrefix: "ZAR",
    subscriptionStatus: "AWAITING_SUBSCRIPTION",
    accountStatus: "ACTIVE",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated caller cannot read or list businesses or customers", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("businesses").get());
  await assertFails(unauthDb.collection("subscription_payments").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
});

test("Business owner can create isolated business workspace with AWAITING_SUBSCRIPTION", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL);
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );
});

test("Business owner cannot self-activate ACTIVE subscription on creation", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL);
  const forged = baseBusiness(ALICE_UID);
  forged.subscriptionStatus = "ACTIVE";
  await assertFails(aliceDb.collection("businesses").doc("biz_forged").set(forged));
});

test("Business owner can create isolated workspace with 90-day TRIALING and legacy AWAITING_SUBSCRIPTION converts once", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL);
  const now = Date.now();
  const trialDaysMs = 90 * 24 * 60 * 60 * 1000;
  const trialEnd = now + trialDaysMs;

  const trialBiz = baseBusiness(ALICE_UID);
  trialBiz.subscriptionStatus = "TRIALING";
  trialBiz.subscriptionPlanId = "plan_trial_90d";
  trialBiz.subscriptionPlanCode = "FREE_TRIAL";
  trialBiz.subscriptionActivatedMillis = now;
  trialBiz.subscriptionExpiryMillis = trialEnd;

  const createBatch = aliceDb.batch();
  createBatch.set(aliceDb.collection("businesses").doc("biz_trial"), trialBiz);
  createBatch.set(aliceDb.collection("business_subscriptions").doc("biz_trial"), {
    businessId: "biz_trial",
    ownerUid: ALICE_UID,
    memberUids: [ALICE_UID],
    planId: "plan_trial_90d",
    planCode: "FREE_TRIAL",
    planNameSnapshot: "90-Day Free Trial",
    agreedPriceMinor: 0,
    agreedCurrency: "NGN",
    agreedBillingFrequency: "CUSTOM",
    agreedIntervalDays: 90,
    status: "TRIALING",
    cycleStartMillis: now,
    cycleEndMillis: trialEnd,
    gracePeriodEndMillis: trialEnd,
    trialEndMillis: trialEnd,
    creditBalanceMinor: 0,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  await assertSucceeds(createBatch.commit());

  // Forged trial exceeding 91 days must fail
  const forgedLongTrial = baseBusiness(ALICE_UID);
  forgedLongTrial.subscriptionStatus = "TRIALING";
  forgedLongTrial.subscriptionPlanId = "plan_trial_90d";
  forgedLongTrial.subscriptionPlanCode = "FREE_TRIAL";
  forgedLongTrial.subscriptionActivatedMillis = now;
  forgedLongTrial.subscriptionExpiryMillis = now + 120 * 24 * 60 * 60 * 1000;
  await assertFails(aliceDb.collection("businesses").doc("biz_forged_long").set(forgedLongTrial));

  // Forged trial with non-trial planId must fail
  const forgedPlanTrial = baseBusiness(ALICE_UID);
  forgedPlanTrial.subscriptionStatus = "TRIALING";
  forgedPlanTrial.subscriptionPlanId = "plan_annual_18000";
  forgedPlanTrial.subscriptionPlanCode = "FREE_TRIAL";
  forgedPlanTrial.subscriptionActivatedMillis = now;
  forgedPlanTrial.subscriptionExpiryMillis = trialEnd;
  await assertFails(aliceDb.collection("businesses").doc("biz_forged_plan").set(forgedPlanTrial));

  // Legacy AWAITING_SUBSCRIPTION business converts once to 90-day TRIALING
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_legacy").set(baseBusiness(ALICE_UID))
  );
  const upgradeBatch = aliceDb.batch();
  upgradeBatch.update(aliceDb.collection("businesses").doc("biz_legacy"), {
    subscriptionStatus: "TRIALING",
    subscriptionPlanId: "plan_trial_90d",
    subscriptionPlanCode: "FREE_TRIAL",
    subscriptionActivatedMillis: now,
    subscriptionExpiryMillis: trialEnd,
    updatedAt: serverTimestamp(),
  });
  upgradeBatch.set(aliceDb.collection("business_subscriptions").doc("biz_legacy"), {
    businessId: "biz_legacy",
    ownerUid: ALICE_UID,
    memberUids: [ALICE_UID],
    planId: "plan_trial_90d",
    planCode: "FREE_TRIAL",
    planNameSnapshot: "90-Day Free Trial",
    agreedPriceMinor: 0,
    agreedCurrency: "NGN",
    agreedBillingFrequency: "CUSTOM",
    agreedIntervalDays: 90,
    status: "TRIALING",
    cycleStartMillis: now,
    cycleEndMillis: trialEnd,
    gracePeriodEndMillis: trialEnd,
    trialEndMillis: trialEnd,
    creditBalanceMinor: 0,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  await assertSucceeds(upgradeBatch.commit());

  // Second attempt to reset/extend trial expiry fails
  await assertFails(
    aliceDb.collection("businesses").doc("biz_legacy").update({
      subscriptionExpiryMillis: now + 90 * 24 * 60 * 60 * 1000 + 5000,
      updatedAt: serverTimestamp(),
    })
  );
});

test("Cross-tenant isolation: Bob cannot read or list Alice's business, customers, or receipts", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL);
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").collection("customers").doc("cust_1").set({
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      fullName: "Chinedu Okafor",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  const bobDb = dbFor(BOB_UID, BOB_EMAIL);
  await assertFails(bobDb.collection("businesses").doc("biz_alice").get());
  await assertFails(
    bobDb.collection("businesses").doc("biz_alice").collection("customers").doc("cust_1").get()
  );
  await assertFails(
    bobDb.collection("businesses").doc("biz_alice").collection("customers").get()
  );
});

test("Email spoofing attack fails: unverified 1803consult email is NOT Super Admin", async () => {
  const spoofDb = dbFor("spoof_uid", ADMIN_EMAIL, false);
  await assertFails(
    spoofDb.collection("platform_settings").doc("plans").set({
      settingKey: "plans",
      monthlyPriceNgn: 1500,
      annualPriceNgn: 18000,
      updatedAt: serverTimestamp(),
    })
  );
});

test("Verified 1803 CONSULT Super Admin can configure platform plans and announcements", async () => {
  const adminDb = dbFor(ADMIN_UID, ADMIN_EMAIL, true);
  await assertSucceeds(
    adminDb.collection("platform_settings").doc("plans").set({
      settingKey: "plans",
      monthlyPriceNgn: 1500,
      annualPriceNgn: 18000,
      storageLimitMb: 500,
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    adminDb.collection("platform_announcements").doc("ann_1").set({
      title: "Welcome to 1803Receipts",
      message: "Platform live for Nigerian businesses.",
      status: "PUBLISHED",
      createdByUid: ADMIN_UID,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
});

test("Non-admin cannot write platform settings or announcements", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL, true);
  await assertFails(
    aliceDb.collection("platform_settings").doc("plans").set({
      settingKey: "plans",
      monthlyPriceNgn: 0,
      updatedAt: serverTimestamp(),
    })
  );
});

test("Receipt immutability: amountPaid and receiptNumber cannot be altered after issuance", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL);
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").collection("receipts").doc("rct_1").set({
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      receiptNumber: "ZAR/RCT/2026/000001",
      verificationCode: "1803R-VRF-ABC123",
      customerName: "Amina Bello",
      paymentPurpose: "Consulting Fee",
      amountPaid: 50000,
      currency: "NGN",
      verificationStatus: "PENDING_VERIFICATION",
      issuedByUid: ALICE_UID,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Attempting to tamper with amountPaid must fail
  await assertFails(
    aliceDb.collection("businesses").doc("biz_alice").collection("receipts").doc("rct_1").update({
      amountPaid: 90000,
      updatedAt: serverTimestamp(),
    })
  );

  // Updating verificationStatus cleanly succeeds
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").collection("receipts").doc("rct_1").update({
      verificationStatus: "VERIFIED",
      updatedAt: serverTimestamp(),
    })
  );
});

test("Audit logs are append-only: update and delete fail", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL);
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );
  const logRef = aliceDb.collection("businesses").doc("biz_alice").collection("audit_logs").doc("log_1");
  await assertSucceeds(
    logRef.set({
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      actorUid: ALICE_UID,
      action: "RECEIPT_ISSUED",
      createdAt: serverTimestamp(),
    })
  );
  await assertFails(logRef.update({ action: "TAMPERED" }));
  await assertFails(logRef.delete());
});

test("Public QR verification allows unauthenticated get only when enabled, and denies unauthenticated list", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL);
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );

  await assertSucceeds(
    aliceDb.collection("public_verifications").doc("1803R-VRF-PUB1").set({
      verificationCode: "1803R-VRF-PUB1",
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      businessName: "Zaria Ventures",
      receiptNumber: "ZAR/RCT/2026/000001",
      amountFormatted: "NGN 50,000.00",
      verificationStatus: "VERIFIED",
      publicVerificationEnabled: true,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("public_verifications").doc("1803R-VRF-PRIV1").set({
      verificationCode: "1803R-VRF-PRIV1",
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      businessName: "Zaria Ventures",
      receiptNumber: "ZAR/RCT/2026/000002",
      amountFormatted: "NGN 10,000.00",
      verificationStatus: "VERIFIED",
      publicVerificationEnabled: false,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertSucceeds(unauthDb.collection("public_verifications").doc("1803R-VRF-PUB1").get());
  await assertFails(unauthDb.collection("public_verifications").doc("1803R-VRF-PRIV1").get());
  await assertFails(unauthDb.collection("public_verifications").get());
});

test("Bank transfer receipt upload queues notification mail and only Super Admin can click Verified to activate subscription", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL, true);
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );

  // Business owner submits transfer payment receipt to KUDA 1803 CONSULT 3001823009
  await assertSucceeds(
    aliceDb.collection("subscription_payments").doc("pay_1").set({
      businessId: "biz_alice",
      businessName: "Zaria Ventures",
      ownerUid: ALICE_UID,
      ownerName: "Alice",
      ownerEmail: ALICE_EMAIL,
      planCode: "MONTHLY",
      amountNgn: 1500,
      currency: "NGN",
      txRef: "1803R_TRF_MONTHLY_1234",
      paymentMethod: "BANK_TRANSFER",
      bankAccountName: "1803 CONSULT",
      bankAccountNumber: "3001823009",
      bankName: "KUDA",
      senderName: "Alice Ventures",
      receiptImageBase64: "ZmFrZV9qcGVnX2Jhc2U2NA==",
      paymentStatus: "PENDING",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Queues email notification to info.1803consult@gmail.com
  await assertSucceeds(
    aliceDb.collection("mail_notifications").doc("pay_1").set({
      paymentId: "pay_1",
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      toEmail: ADMIN_EMAIL,
      subject: "New 1803Receipts MONTHLY Transfer Payment - Zaria Ventures",
      body: "Business owner Alice paid NGN 1,500.00.",
      deliveryStatus: "QUEUED",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Owner CANNOT self-verify or self-activate
  await assertFails(
    aliceDb.collection("subscription_payments").doc("pay_1").update({
      paymentStatus: "VERIFIED",
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("businesses").doc("biz_alice").update({
      subscriptionStatus: "ACTIVE",
      updatedAt: serverTimestamp(),
    })
  );

  // Verified 1803 CONSULT Super Admin verifies payment and activates subscription
  const adminDb = dbFor(ADMIN_UID, ADMIN_EMAIL, true);
  await assertSucceeds(
    adminDb.collection("subscription_payments").doc("pay_1").update({
      paymentStatus: "VERIFIED",
      verifiedByUid: ADMIN_UID,
      verifiedByEmail: ADMIN_EMAIL,
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    adminDb.collection("businesses").doc("biz_alice").update({
      subscriptionStatus: "ACTIVE",
      subscriptionPlanCode: "MONTHLY",
      updatedAt: serverTimestamp(),
    })
  );
});

test("Business owner Paystack confirmed batch atomically activates subscription while standalone/unverified activation still fails", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL, true);
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_paystack").set(baseBusiness(ALICE_UID))
  );

  // Unverified payment attempt cannot activate business standalone
  await assertFails(
    aliceDb.collection("businesses").doc("biz_paystack").update({
      subscriptionStatus: "ACTIVE",
      subscriptionPlanId: "plan_monthly_1803",
      subscriptionPlanCode: "MONTHLY",
      subscriptionExpiryMillis: Date.now() + 30 * 86400000,
      updatedAt: serverTimestamp(),
    })
  );

  // Atomic Paystack batch (payment + subscription + business + invoice + quota + storage + history) succeeds
  const cycleEnd = Date.now() + 30 * 86400000;
  const batch = aliceDb.batch();
  batch.set(aliceDb.collection("subscription_payments").doc("pay_psk_1"), {
    businessId: "biz_paystack",
    businessName: "Zaria Ventures",
    ownerUid: ALICE_UID,
    ownerName: "Alice",
    ownerEmail: ALICE_EMAIL,
    planId: "plan_monthly_1803",
    planCode: "MONTHLY",
    planName: "Monthly",
    amountNgn: 1500,
    amountMinor: 150000,
    currency: "NGN",
    txRef: "PSK_REF_99887766",
    paymentMethod: "PAYSTACK",
    paystackReference: "PSK_REF_99887766",
    paystackChannel: "card",
    paystackPaymentUrl: "https://paystack.shop/pay/1803reciepts",
    verificationSource: "CHECKOUT_CONFIRMED",
    paymentStatus: "VERIFIED",
    verifiedByUid: ALICE_UID,
    verifiedAtMillis: Date.now(),
    cycleStartMillis: Date.now(),
    cycleEndMillis: cycleEnd,
    invoiceId: "inv_psk_1",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.set(aliceDb.collection("business_subscriptions").doc("biz_paystack"), {
    businessId: "biz_paystack",
    ownerUid: ALICE_UID,
    memberUids: [ALICE_UID],
    planId: "plan_monthly_1803",
    planCode: "MONTHLY",
    planNameSnapshot: "Monthly",
    agreedPriceMinor: 150000,
    agreedCurrency: "NGN",
    agreedBillingFrequency: "MONTHLY",
    agreedIntervalDays: 30,
    status: "ACTIVE",
    cycleStartMillis: Date.now(),
    cycleEndMillis: cycleEnd,
    gracePeriodEndMillis: cycleEnd + 3 * 86400000,
    creditBalanceMinor: 0,
    lastPaymentId: "pay_psk_1",
    lastInvoiceId: "inv_psk_1",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.update(aliceDb.collection("businesses").doc("biz_paystack"), {
    subscriptionStatus: "ACTIVE",
    subscriptionPlanId: "plan_monthly_1803",
    subscriptionPlanCode: "MONTHLY",
    subscriptionActivatedMillis: Date.now(),
    subscriptionExpiryMillis: cycleEnd,
    storageLimitBytes: 524288000,
    updatedAt: serverTimestamp(),
  });
  batch.set(aliceDb.collection("subscription_invoices").doc("inv_psk_1"), {
    invoiceNumber: "1803R-INV-2026-PSK1",
    businessId: "biz_paystack",
    ownerUid: ALICE_UID,
    paymentId: "pay_psk_1",
    planId: "plan_monthly_1803",
    planCode: "MONTHLY",
    totalMinor: 150000,
    currency: "NGN",
    status: "PAID",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.set(aliceDb.collection("usage_quotas").doc("biz_paystack_current"), {
    businessId: "biz_paystack",
    ownerUid: ALICE_UID,
    memberUids: [ALICE_UID],
    cycleKey: `cycle_${cycleEnd}`,
    receiptsAllocated: 300,
    receiptsUsed: 0,
    rolloverReceipts: 0,
    overageReceiptsUsed: 0,
    ocrAllocated: 300,
    ocrUsed: 0,
    updatedAt: serverTimestamp(),
  });
  batch.set(aliceDb.collection("storage_usage").doc("biz_paystack"), {
    businessId: "biz_paystack",
    ownerUid: ALICE_UID,
    memberUids: [ALICE_UID],
    allocatedBytes: 524288000,
    usedBytes: 0,
    updatedAt: serverTimestamp(),
  });
  batch.set(aliceDb.collection("plan_change_history").doc("chg_psk_1"), {
    businessId: "biz_paystack",
    ownerUid: ALICE_UID,
    toPlanId: "plan_monthly_1803",
    changeType: "NEW",
    actorUid: ALICE_UID,
    createdAt: serverTimestamp(),
  });
  await assertSucceeds(batch.commit());
});

test("Active BUSINESS_MANAGER can update branding settings but cannot self-activate subscription or change memberUids", async () => {
  const STAFF_UID = "manager_staff_789";
  const STAFF_EMAIL = "manager@business1.ng";
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL);
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID, [ALICE_UID, STAFF_UID]))
  );
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").collection("staff").doc(STAFF_UID).set({
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID, STAFF_UID],
      staffUid: STAFF_UID,
      email: STAFF_EMAIL,
      role: "BUSINESS_MANAGER",
      active: true,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  const managerDb = dbFor(STAFF_UID, STAFF_EMAIL);
  // Branding update succeeds
  await assertSucceeds(
    managerDb.collection("businesses").doc("biz_alice").update({
      tradingName: "Zaria Ventures Rebranded",
      primaryColorHex: "#123456",
      updatedAt: serverTimestamp(),
    })
  );
  // Escalation to ACTIVE subscription must fail
  await assertFails(
    managerDb.collection("businesses").doc("biz_alice").update({
      subscriptionStatus: "ACTIVE",
      updatedAt: serverTimestamp(),
    })
  );

  // Deactivating the manager revokes branding update privilege
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").collection("staff").doc(STAFF_UID).update({
      active: false,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    managerDb.collection("businesses").doc("biz_alice").update({
      tradingName: "Blocked Update",
      updatedAt: serverTimestamp(),
    })
  );
});

test("Invited staff can read and initialize counters and read workspace receipts", async () => {
  const STAFF_UID = "officer_staff_321";
  const STAFF_EMAIL = "officer@business1.ng";
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL);
  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID, [ALICE_UID, STAFF_UID]))
  );

  const staffDb = dbFor(STAFF_UID, STAFF_EMAIL);
  // Reading non-existent counter succeeds for business member
  await assertSucceeds(
    staffDb.collection("businesses").doc("biz_alice").collection("counters").doc("receipt_2026").get()
  );
  // Initializing counter succeeds
  await assertSucceeds(
    staffDb.collection("businesses").doc("biz_alice").collection("counters").doc("receipt_2026").set({
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID, STAFF_UID],
      year: 2026,
      lastSequence: 1,
      updatedAt: serverTimestamp(),
    })
  );
  // Reading workspace receipts succeeds for invited staff
  await assertSucceeds(
    staffDb.collection("businesses").doc("biz_alice").collection("receipts").get()
  );

  // Outsider Bob is denied
  const bobDb = dbFor(BOB_UID, BOB_EMAIL);
  await assertFails(
    bobDb.collection("businesses").doc("biz_alice").collection("counters").doc("receipt_2026").get()
  );
});

test("Subscription plans, features, and entitlements: signed-in users read, only Super Admin writes", async () => {
  const adminDb = dbFor(ADMIN_UID, ADMIN_EMAIL, true);
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL, true);

  // Admin creates Monthly (NGN 1,500 = 150000 kobo) and Annual (NGN 18,000 = 1800000 kobo)
  await assertSucceeds(
    adminDb.collection("subscription_plans").doc("plan_monthly_1500").set({
      planId: "plan_monthly_1500",
      planCode: "MONTHLY",
      name: "Monthly Plan",
      description: "₦1,500 monthly billing",
      priceMinor: 150000,
      currency: "NGN",
      billingFrequency: "MONTHLY",
      intervalDays: 30,
      trialDays: 0,
      visible: true,
      status: "ACTIVE",
      sortOrder: 1,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    adminDb.collection("plan_entitlements").doc("plan_monthly_1500").set({
      planId: "plan_monthly_1500",
      maxReceiptsPerCycle: 300,
      rolloverEnabled: false,
      overageReceiptPriceMinor: 0,
      storageLimitBytes: 524288000,
      storageOverLimitAction: "BLOCK_NEW_UPLOADS",
      overageStoragePricePerGbMinor: 0,
      maxStaffAccounts: 5,
      maxOcrPerCycle: 300,
      maxCustomers: 1000,
      allowedTemplates: ["STANDARD", "PREMIUM_CORPORATE", "COMPACT"],
      analyticsEnabled: true,
      reportsExportEnabled: true,
      qrVerificationEnabled: true,
      whatsappSharingEnabled: true,
      emailSharingEnabled: true,
      prioritySupport: false,
      warningThresholds: [50, 75, 90, 100],
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    adminDb.collection("plan_features").doc("feat_m_1").set({
      planId: "plan_monthly_1500",
      featureKey: "receipts",
      label: "300 receipts / month",
      valueText: "300",
      included: true,
      sortOrder: 1,
      updatedAt: serverTimestamp(),
    })
  );

  // Signed-in subscriber can read plans, features, and entitlements
  await assertSucceeds(aliceDb.collection("subscription_plans").get());
  await assertSucceeds(aliceDb.collection("plan_entitlements").get());
  await assertSucceeds(aliceDb.collection("plan_features").get());

  // Non-admin CANNOT create, update, or delete plans or entitlements
  await assertFails(
    aliceDb.collection("subscription_plans").doc("plan_hack").set({
      planId: "plan_hack",
      planCode: "FREE",
      name: "Free Hack",
      priceMinor: 0,
      currency: "NGN",
      billingFrequency: "MONTHLY",
      intervalDays: 30,
      visible: true,
      status: "ACTIVE",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("plan_entitlements").doc("plan_monthly_1500").update({
      maxReceiptsPerCycle: 999999,
      updatedAt: serverTimestamp(),
    })
  );
});

test("Business subscription snapshots & immutability: owner cannot tamper with price, cycleEndMillis, or entitlements", async () => {
  const adminDb = dbFor(ADMIN_UID, ADMIN_EMAIL, true);
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL, true);

  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );

  const subRef = adminDb.collection("business_subscriptions").doc("biz_alice");
  await assertSucceeds(
    subRef.set({
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      planId: "plan_monthly_1500",
      planCode: "MONTHLY",
      planNameSnapshot: "Monthly Plan",
      agreedPriceMinor: 150000,
      agreedCurrency: "NGN",
      agreedBillingFrequency: "MONTHLY",
      agreedIntervalDays: 30,
      agreedEntitlementsSnapshot: {
        maxReceiptsPerCycle: 300,
        storageLimitBytes: 524288000,
      },
      status: "ACTIVE",
      cycleStartMillis: 1700000000000,
      cycleEndMillis: 1702592000000,
      gracePeriodEndMillis: 1702851200000,
      trialEndMillis: 0,
      autoRenew: true,
      cancelAtPeriodEnd: false,
      cancelledAtMillis: 0,
      pendingUpgradePlanId: "",
      creditBalanceMinor: 0,
      lastPaymentId: "pay_1",
      lastInvoiceId: "inv_1",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Owner can read and toggle cancelAtPeriodEnd / autoRenew safely
  const aliceSubRef = aliceDb.collection("business_subscriptions").doc("biz_alice");
  await assertSucceeds(aliceSubRef.get());
  await assertSucceeds(
    aliceSubRef.update({
      autoRenew: false,
      cancelAtPeriodEnd: true,
      cancelledAtMillis: 1700500000000,
      updatedAt: serverTimestamp(),
    })
  );

  // Owner CANNOT tamper with agreedPriceMinor, cycleEndMillis, or agreedEntitlementsSnapshot
  await assertFails(
    aliceSubRef.update({
      agreedPriceMinor: 0,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    aliceSubRef.update({
      cycleEndMillis: 9999999999999,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    aliceSubRef.update({
      agreedEntitlementsSnapshot: { maxReceiptsPerCycle: 999999, storageLimitBytes: 999999999999 },
      updatedAt: serverTimestamp(),
    })
  );

  // Bob cannot read Alice's subscription
  const bobDb = dbFor(BOB_UID, BOB_EMAIL, true);
  await assertFails(bobDb.collection("business_subscriptions").doc("biz_alice").get());
});

test("Usage records, usage quotas, and storage usage enforce idempotency and monotonic boundaries", async () => {
  const adminDb = dbFor(ADMIN_UID, ADMIN_EMAIL, true);
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL, true);
  const bobDb = dbFor(BOB_UID, BOB_EMAIL, true);

  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );

  // Super Admin provisions initial quota and storage allowance
  const quotaRef = adminDb.collection("usage_quotas").doc("biz_alice_current");
  await assertSucceeds(
    quotaRef.set({
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      cycleKey: "cycle_1700000000000",
      cycleStartMillis: 1700000000000,
      cycleEndMillis: 1702592000000,
      receiptsAllocated: 300,
      receiptsUsed: 0,
      rolloverReceipts: 0,
      overageReceiptsUsed: 0,
      ocrAllocated: 300,
      ocrUsed: 0,
      warningsSentPct: [],
      updatedAt: serverTimestamp(),
    })
  );

  const storageRef = adminDb.collection("storage_usage").doc("biz_alice");
  await assertSucceeds(
    storageRef.set({
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      allocatedBytes: 524288000,
      usedBytes: 0,
      bankReceiptsBytes: 0,
      generatedReceiptsBytes: 0,
      brandingBytes: 0,
      otherDocsBytes: 0,
      limitBehavior: "BLOCK_NEW_UPLOADS",
      warningThresholdsSent: [],
      updatedAt: serverTimestamp(),
    })
  );

  // Business member can read non-existent idempotency usage_records before transaction write; outsider Bob cannot
  await assertSucceeds(aliceDb.collection("usage_records").doc("biz_alice_rct_idem_new").get());
  await assertSucceeds(aliceDb.collection("usage_records").doc("biz_alice_ocr_idem_new").get());
  await assertFails(bobDb.collection("usage_records").doc("biz_alice_rct_idem_new").get());

  // Member creates immutable usage_record with idempotency key
  const recRef = aliceDb.collection("usage_records").doc("biz_alice_idem_1");
  await assertSucceeds(
    recRef.set({
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      usageType: "RECEIPT_GENERATED",
      quantity: 1,
      bytesDelta: 45000,
      idempotencyKey: "idem_1",
      resourceId: "rct_1",
      cycleKey: "cycle_1700000000000",
      actorUid: ALICE_UID,
      createdAt: serverTimestamp(),
    })
  );
  // Cannot mutate or delete usage_record
  await assertFails(recRef.update({ quantity: 0 }));
  await assertFails(recRef.delete());

  // Member can increment receiptsUsed and usedBytes monotonically
  await assertSucceeds(
    aliceDb.collection("usage_quotas").doc("biz_alice_current").update({
      receiptsUsed: 1,
      warningsSentPct: [],
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    aliceDb.collection("storage_usage").doc("biz_alice").update({
      usedBytes: 45000,
      generatedReceiptsBytes: 45000,
      updatedAt: serverTimestamp(),
    })
  );

  // Member CANNOT decrement receiptsUsed or inflate receiptsAllocated / allocatedBytes
  await assertFails(
    aliceDb.collection("usage_quotas").doc("biz_alice_current").update({
      receiptsUsed: 0,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("usage_quotas").doc("biz_alice_current").update({
      receiptsAllocated: 9999,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("storage_usage").doc("biz_alice").update({
      allocatedBytes: 999999999999,
      updatedAt: serverTimestamp(),
    })
  );

  // Outsider Bob cannot read or mutate
  await assertFails(bobDb.collection("usage_quotas").doc("biz_alice_current").get());
  await assertFails(bobDb.collection("storage_usage").doc("biz_alice").get());
});

test("Subscription invoices, events, plan change history, and custom overrides enforce admin boundaries", async () => {
  const adminDb = dbFor(ADMIN_UID, ADMIN_EMAIL, true);
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL, true);

  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );

  // Non-admin CANNOT forge an invoice or custom entitlement override
  await assertFails(
    aliceDb.collection("subscription_invoices").doc("inv_fake").set({
      invoiceNumber: "INV-2026-999",
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      planId: "plan_monthly_1500",
      totalMinor: 150000,
      currency: "NGN",
      status: "PAID",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("custom_business_entitlements").doc("biz_alice").set({
      businessId: "biz_alice",
      overrideReceiptsPerCycle: 9999,
      updatedByUid: ALICE_UID,
      updatedAt: serverTimestamp(),
    })
  );

  // Super Admin creates invoice, custom entitlement, plan change history, and audit event
  await assertSucceeds(
    adminDb.collection("subscription_invoices").doc("inv_1").set({
      invoiceNumber: "1803R-INV-2026-0001",
      businessId: "biz_alice",
      businessName: "Zaria Ventures",
      ownerUid: ALICE_UID,
      ownerEmail: ALICE_EMAIL,
      subscriptionId: "biz_alice",
      paymentId: "pay_1",
      planId: "plan_monthly_1500",
      planCode: "MONTHLY",
      planNameSnapshot: "Monthly Plan",
      billingFrequencySnapshot: "MONTHLY",
      subtotalMinor: 150000,
      prorationCreditMinor: 0,
      taxMinor: 0,
      totalMinor: 150000,
      currency: "NGN",
      status: "PAID",
      cycleStartMillis: 1700000000000,
      cycleEndMillis: 1702592000000,
      issuedAtMillis: 1700000000000,
      paidAtMillis: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    adminDb.collection("custom_business_entitlements").doc("biz_alice").set({
      businessId: "biz_alice",
      overrideReceiptsPerCycle: 500,
      overrideStorageLimitBytes: 1073741824,
      overrideMaxStaff: 10,
      overrideMaxOcr: 500,
      overrideMaxCustomers: 2500,
      overrideRolloverEnabled: true,
      overrideNotes: "Enterprise promotional bump",
      updatedByUid: ADMIN_UID,
      updatedByEmail: ADMIN_EMAIL,
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    adminDb.collection("plan_change_history").doc("chg_1").set({
      businessId: "biz_alice",
      businessName: "Zaria Ventures",
      ownerUid: ALICE_UID,
      fromPlanId: "",
      fromPlanName: "",
      toPlanId: "plan_monthly_1500",
      toPlanName: "Monthly Plan",
      changeType: "NEW",
      prorationCreditMinor: 0,
      chargedMinor: 150000,
      currency: "NGN",
      actorUid: ADMIN_UID,
      actorEmail: ADMIN_EMAIL,
      notes: "Initial launch monthly subscription verified",
      createdAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    adminDb.collection("subscription_events").doc("evt_1").set({
      actorUid: ADMIN_UID,
      actorEmail: ADMIN_EMAIL,
      actorRole: "SUPER_ADMIN",
      businessId: "biz_alice",
      entityType: "custom_business_entitlements",
      entityId: "biz_alice",
      action: "OVERRIDE_SAVED",
      previousValue: "{}",
      newValue: "{\"overrideReceiptsPerCycle\":500}",
      createdAt: serverTimestamp(),
    })
  );

  // Business owner can read their own invoice, override, and plan change history
  await assertSucceeds(aliceDb.collection("subscription_invoices").doc("inv_1").get());
  await assertSucceeds(aliceDb.collection("custom_business_entitlements").doc("biz_alice").get());
  await assertSucceeds(aliceDb.collection("plan_change_history").doc("chg_1").get());

  // Audit logs and plan changes are immutable even to Super Admin
  await assertFails(adminDb.collection("subscription_events").doc("evt_1").update({ action: "TAMPERED" }));
  await assertFails(adminDb.collection("subscription_events").doc("evt_1").delete());
  await assertFails(adminDb.collection("plan_change_history").doc("chg_1").delete());
  await assertFails(adminDb.collection("subscription_invoices").doc("inv_1").delete());
});

test("Customer complaints from AI Chatbot or Help Desk queue Super Admin email notifications with tenant isolation", async () => {
  const aliceDb = dbFor(ALICE_UID, ALICE_EMAIL, true);
  const bobDb = dbFor(BOB_UID, BOB_EMAIL, true);
  const adminDb = dbFor(ADMIN_UID, ADMIN_EMAIL, true);

  await assertSucceeds(
    aliceDb.collection("businesses").doc("biz_alice").set(baseBusiness(ALICE_UID))
  );

  await assertSucceeds(
    aliceDb.collection("support_tickets").doc("tkt_1").set({
      businessId: "biz_alice",
      businessName: "Zaria Ventures",
      ownerUid: ALICE_UID,
      ownerName: "Alice",
      ownerEmail: ALICE_EMAIL,
      subject: "WhatsApp receipt share difficulty",
      category: "WHATSAPP_SHARE",
      source: "AI_CHATBOT",
      description: "Getting share error on WhatsApp.",
      status: "OPEN",
      adminReply: "",
      emailNotificationStatus: "QUEUED",
      emailNotificationError: "",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("mail_notifications").doc("tkt_1").set({
      paymentId: "tkt_1",
      businessId: "biz_alice",
      ownerUid: ALICE_UID,
      toEmail: ADMIN_EMAIL,
      subject: "[1803Receipts Complaint][WHATSAPP_SHARE] WhatsApp receipt share difficulty",
      body: "Customer reported WhatsApp share issue.",
      deliveryStatus: "QUEUED",
      lastError: "",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Client relay status update on own ticket succeeds, but cannot resolve own ticket or tamper with mail_notifications
  await assertSucceeds(
    aliceDb.collection("support_tickets").doc("tkt_1").update({
      emailNotificationStatus: "SENT",
      emailNotificationError: "",
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("support_tickets").doc("tkt_1").update({
      status: "RESOLVED",
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(bobDb.collection("support_tickets").doc("tkt_1").get());
  await assertFails(aliceDb.collection("mail_notifications").doc("tkt_1").get());

  // Super Admin can read mail queue and resolve ticket
  await assertSucceeds(adminDb.collection("mail_notifications").doc("tkt_1").get());
  await assertSucceeds(
    adminDb.collection("support_tickets").doc("tkt_1").update({
      status: "RESOLVED",
      adminReply: "Fixed and email confirmed.",
      updatedAt: serverTimestamp(),
    })
  );
});



