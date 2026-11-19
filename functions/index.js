const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const nodemailer = require("nodemailer");

admin.initializeApp();
const DATABASE_ID = "ai-studio-android-1803rece-28abc08c-3d25-4b25-a94f-ec5c1f083b93";
const db = getFirestore(DATABASE_ID);

// Set this secret once via:
// firebase functions:secrets:set GMAIL_APP_PASSWORD --project gen-lang-client-0777428447
const GMAIL_APP_PASSWORD = defineSecret("GMAIL_APP_PASSWORD");
const ADMIN_NOTIFY_EMAIL = "info.1803consult@gmail.com";

function normalizeWebhookUrl(raw) {
  let url = String(raw || "").trim();
  if (!url) return "";
  if (url.toLowerCase().includes("script.google.com")) {
    url = url.replace(/\/macros\/u\/\d+\/s\//i, "/macros/s/");
  }
  return url;
}

function parseJsonRequestBody(req) {
  if (req.body && typeof req.body === "object" && !Buffer.isBuffer(req.body)) {
    return req.body;
  }
  const raw = Buffer.isBuffer(req.body)
    ? req.body.toString("utf8")
    : typeof req.body === "string"
      ? req.body
      : req.rawBody
        ? req.rawBody.toString("utf8")
        : "";
  if (!raw || !raw.trim()) return {};
  return JSON.parse(raw);
}

async function resolveTargetSettings() {
  try {
    const snap = await db.collection("platform_settings").doc("global").get();
    const data = snap.exists ? snap.data() || {} : {};
    return {
      toEmail: String(data.notificationEmail || ADMIN_NOTIFY_EMAIL).trim() || ADMIN_NOTIFY_EMAIL,
      webhookUrl: normalizeWebhookUrl(data.emailNotificationWebhookUrl || ""),
    };
  } catch (_) {
    return { toEmail: ADMIN_NOTIFY_EMAIL, webhookUrl: "" };
  }
}

const crypto = require("crypto");
const FLW_SECRET_HASH = defineSecret("FLW_SECRET_HASH");
const FLW_SECRET_KEY = defineSecret("FLW_SECRET_KEY");

function buildEmailPayload(pay, paymentId, toEmail) {
  const planDisplay = pay.planName || pay.planCode || "SUBSCRIPTION";
  const subject = `New 1803Receipts ${planDisplay} Transfer Payment - ${pay.businessName || pay.businessId || "Subscriber"}`;
  const textBody = [
    "A business owner has submitted a bank transfer receipt for verification.",
    "",
    `Business Name: ${pay.businessName || "-"}`,
    `Business ID: ${pay.businessId || "-"}`,
    `Owner Name: ${pay.ownerName || "-"}`,
    `Owner Email: ${pay.ownerEmail || "-"}`,
    `Owner Phone: ${pay.ownerPhone || "-"}`,
    `Plan: ${planDisplay} (${pay.planCode || "MONTHLY"})`,
    `Amount Paid: NGN ${Number(pay.amountNgn || (Number(pay.amountMinor || 0) / 100)).toLocaleString("en-NG", { minimumFractionDigits: 2 })}`,
    `Paid Into: ${pay.bankAccountName || "1803 CONSULT"} (${pay.bankAccountNumber || "3001823009"} - ${pay.bankName || "KUDA"})`,
    `Transfer Reference: ${pay.txRef || "-"}`,
    `Sender Name: ${pay.senderName || "-"}`,
    `Sender Bank: ${pay.senderBank || "-"}`,
    `Note: ${pay.transferNote || "-"}`,
    "",
    "Open the 1803 Super Admin console (Payments tab) to review the uploaded receipt and click 'Verified' to activate the business subscription.",
  ].join("\n");

  const attachments = [];
  const rawBase64 = String(pay.receiptImageBase64 || "").replace(/^data:[^;]+;base64,/i, "").trim();
  if (rawBase64) {
    const mime = pay.receiptMimeType || "image/jpeg";
    let filename = String(pay.receiptFileName || `receipt_${paymentId || "upload"}.jpg`).trim();
    if (mime === "image/jpeg" && !/\.(jpe?g)$/i.test(filename)) {
      filename = `${filename.replace(/\.[^.]+$/, "")}.jpg`;
    }
    attachments.push({
      filename,
      content: Buffer.from(rawBase64, "base64"),
      contentType: mime,
    });
  }

  return { toEmail, subject, textBody, attachments, rawBase64 };
}

async function sendViaGmailSmtp(appPass, toEmail, replyTo, subject, textBody, attachments) {
  const transporter = nodemailer.createTransport({
    service: "gmail",
    auth: {
      user: ADMIN_NOTIFY_EMAIL,
      pass: appPass,
    },
  });

  await transporter.sendMail({
    from: `"1803Receipts Billing" <${ADMIN_NOTIFY_EMAIL}>`,
    to: toEmail,
    replyTo: replyTo || ADMIN_NOTIFY_EMAIL,
    subject,
    text: textBody,
    attachments,
  });
}

exports.onSubscriptionPaymentUploaded = onDocumentCreated(
  {
    document: "subscription_payments/{paymentId}",
    database: DATABASE_ID,
    secrets: [GMAIL_APP_PASSWORD],
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const paymentId = event.params.paymentId;
    const pay = snap.data() || {};

    const settings = await resolveTargetSettings();
    const { toEmail, subject, textBody, attachments } = buildEmailPayload(pay, paymentId, settings.toEmail);
    const appPass = (GMAIL_APP_PASSWORD.value() || "").replace(/\s+/g, "").trim();
    const mailRef = db.collection("mail_notifications").doc(paymentId);

    if (!appPass) {
      let webhookError = "";
      if (settings.webhookUrl) {
        try {
          const resp = await fetch(settings.webhookUrl, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              paymentId,
              toEmail,
              subject,
              body: textBody,
              businessId: pay.businessId || "",
              businessName: pay.businessName || "",
              ownerName: pay.ownerName || "",
              ownerEmail: pay.ownerEmail || "",
              ownerPhone: pay.ownerPhone || "",
              planCode: pay.planCode || "",
              planName: pay.planName || "",
              amountNgn: pay.amountNgn || 0,
              amountMinor: pay.amountMinor || 0,
              txRef: pay.txRef || "",
              senderName: pay.senderName || "",
              senderBank: pay.senderBank || "",
              transferNote: pay.transferNote || "",
              receiptImageBase64: pay.receiptImageBase64 || "",
              receiptFileName: pay.receiptFileName || `receipt_${paymentId}.jpg`,
              receiptMimeType: pay.receiptMimeType || "image/jpeg",
            }),
          });
          const respText = await resp.text().catch(() => "");
          let jsonOk = true;
          let jsonErr = "";
          if (respText.trim().startsWith("{")) {
            try {
              const parsed = JSON.parse(respText);
              if (parsed && parsed.ok === false) jsonOk = false;
              if (parsed && parsed.error) jsonErr = String(parsed.error);
            } catch (_) {}
          }
          if (resp.ok && jsonOk && !jsonErr) {
            await Promise.allSettled([
              snap.ref.update({ emailNotificationStatus: "SENT", updatedAt: FieldValue.serverTimestamp() }),
              mailRef.set({ deliveryStatus: "SENT", lastError: "", updatedAt: FieldValue.serverTimestamp() }, { merge: true }),
            ]);
            return;
          }
          webhookError = jsonErr || `Webhook relay returned HTTP ${resp.status}`;
        } catch (webhookErr) {
          webhookError = String(webhookErr?.message || webhookErr);
          console.error("Webhook relay failed:", webhookErr);
        }
      }

      await Promise.allSettled([
        snap.ref.update({ emailNotificationStatus: webhookError ? "FAILED" : "PENDING_SMTP_CONFIG", updatedAt: FieldValue.serverTimestamp() }),
        mailRef.set({
          deliveryStatus: webhookError ? "FAILED" : "PENDING_SMTP_CONFIG",
          lastError: webhookError || "Set GMAIL_APP_PASSWORD secret or Email Relay Webhook URL.",
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true }),
      ]);
      return;
    }

    try {
      await sendViaGmailSmtp(appPass, toEmail, pay.ownerEmail, subject, textBody, attachments);
      await Promise.allSettled([
        snap.ref.update({ emailNotificationStatus: "SENT", updatedAt: FieldValue.serverTimestamp() }),
        mailRef.set({ deliveryStatus: "SENT", lastError: "", updatedAt: FieldValue.serverTimestamp() }, { merge: true }),
      ]);
    } catch (err) {
      console.error("Failed sending subscription notification mail:", err);
      await Promise.allSettled([
        snap.ref.update({ emailNotificationStatus: "FAILED", updatedAt: FieldValue.serverTimestamp() }),
        mailRef.set({ deliveryStatus: "FAILED", lastError: String(err?.message || err), updatedAt: FieldValue.serverTimestamp() }, { merge: true }),
      ]);
    }
  }
);

exports.onSupportTicketCreated = onDocumentCreated(
  {
    document: "support_tickets/{ticketId}",
    database: DATABASE_ID,
    secrets: [GMAIL_APP_PASSWORD],
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const ticketId = event.params.ticketId;
    const ticket = snap.data() || {};

    const settings = await resolveTargetSettings();
    const toEmail = settings.toEmail;
    const category = ticket.category || "TECHNICAL";
    const source = ticket.source || "HELP_DESK";
    const bizName = ticket.businessName || ticket.businessId || "Business Workspace";
    const subject = `[1803Receipts Complaint][${category}] ${ticket.subject || "Customer Issue"} - ${bizName}`;
    const textBody = [
      "A business owner / user has reported a complaint in 1803Receipts.",
      "",
      `Ticket ID: ${ticketId}`,
      `Source: ${source}`,
      `Category: ${category}`,
      `Business: ${bizName} (${ticket.businessId || "-"})`,
      `Reporter: ${ticket.ownerName || "-"} (${ticket.ownerEmail || "-"})`,
      `Subject: ${ticket.subject || "-"}`,
      "",
      "Complaint Details:",
      ticket.description || "-",
      "",
      "Open the 1803 Super Admin console (Tickets tab) or reply to this email to assist the customer.",
    ].join("\n");

    const appPass = (GMAIL_APP_PASSWORD.value() || "").replace(/\s+/g, "").trim();
    const mailRef = db.collection("mail_notifications").doc(ticketId);

    if (!appPass) {
      let webhookError = "";
      if (settings.webhookUrl) {
        const freshSnap = await snap.ref.get().catch(() => null);
        if (freshSnap && freshSnap.data()?.emailNotificationStatus === "SENT") {
          return;
        }
        try {
          const resp = await fetch(settings.webhookUrl, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              paymentId: ticketId,
              toEmail,
              subject,
              body: textBody,
              businessId: ticket.businessId || "",
              businessName: bizName,
              ownerName: ticket.ownerName || "",
              ownerEmail: ticket.ownerEmail || "",
              planCode: category,
              amountNgn: 0,
              txRef: ticketId,
            }),
          });
          const respText = await resp.text().catch(() => "");
          let jsonOk = true;
          let jsonErr = "";
          if (respText.trim().startsWith("{")) {
            try {
              const parsed = JSON.parse(respText);
              if (parsed && parsed.ok === false) jsonOk = false;
              if (parsed && parsed.error) jsonErr = String(parsed.error);
            } catch (_) {}
          }
          if (resp.ok && jsonOk && !jsonErr) {
            await Promise.allSettled([
              snap.ref.update({ emailNotificationStatus: "SENT", emailNotificationError: "", updatedAt: FieldValue.serverTimestamp() }),
              mailRef.set({ deliveryStatus: "SENT", lastError: "", updatedAt: FieldValue.serverTimestamp() }, { merge: true }),
            ]);
            return;
          }
          webhookError = jsonErr || `Webhook relay returned HTTP ${resp.status}`;
        } catch (webhookErr) {
          webhookError = String(webhookErr?.message || webhookErr);
          console.error("Complaint webhook relay failed:", webhookErr);
        }
      }

      await Promise.allSettled([
        snap.ref.update({
          emailNotificationStatus: webhookError ? "FAILED" : "PENDING_SMTP_CONFIG",
          emailNotificationError: webhookError || "Set GMAIL_APP_PASSWORD secret or Email Relay Webhook URL.",
          updatedAt: FieldValue.serverTimestamp(),
        }),
        mailRef.set({
          deliveryStatus: webhookError ? "FAILED" : "PENDING_SMTP_CONFIG",
          lastError: webhookError || "Set GMAIL_APP_PASSWORD secret or Email Relay Webhook URL.",
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true }),
      ]);
      return;
    }

    try {
      await sendViaGmailSmtp(appPass, toEmail, ticket.ownerEmail, subject, textBody, []);
      await Promise.allSettled([
        snap.ref.update({ emailNotificationStatus: "SENT", emailNotificationError: "", updatedAt: FieldValue.serverTimestamp() }),
        mailRef.set({ deliveryStatus: "SENT", lastError: "", updatedAt: FieldValue.serverTimestamp() }, { merge: true }),
      ]);
    } catch (err) {
      const msg = String(err?.message || err);
      console.error("Failed sending complaint notification mail:", err);
      await Promise.allSettled([
        snap.ref.update({ emailNotificationStatus: "FAILED", emailNotificationError: msg, updatedAt: FieldValue.serverTimestamp() }),
        mailRef.set({ deliveryStatus: "FAILED", lastError: msg, updatedAt: FieldValue.serverTimestamp() }, { merge: true }),
      ]);
    }
  }
);

exports.sendSubscriptionNotificationWebhook = onRequest(
  { secrets: [GMAIL_APP_PASSWORD], cors: true, invoker: "public" },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ ok: false, error: "Method not allowed. Send HTTP POST with JSON payload." });
      return;
    }
    const appPass = (GMAIL_APP_PASSWORD.value() || "").replace(/\s+/g, "").trim();
    if (!appPass) {
      res.status(503).json({ ok: false, error: "GMAIL_APP_PASSWORD secret is not configured." });
      return;
    }
    let body;
    try {
      body = parseJsonRequestBody(req);
    } catch (parseErr) {
      res.status(400).json({ ok: false, error: `Invalid JSON body: ${parseErr?.message || parseErr}` });
      return;
    }
    try {
      const settings = await resolveTargetSettings();
      const targetEmail = String(body.toEmail || settings.toEmail || ADMIN_NOTIFY_EMAIL).trim();
      const built = buildEmailPayload(body, body.paymentId || "webhook", targetEmail);
      const subject = String(body.subject || built.subject);
      const textBody = String(body.body || built.textBody);
      await sendViaGmailSmtp(appPass, targetEmail, body.ownerEmail, subject, textBody, built.attachments);
      res.status(200).json({ ok: true, toEmail: targetEmail });
    } catch (err) {
      console.error("HTTP notification webhook failed:", err);
      res.status(500).json({ ok: false, error: String(err?.message || err) });
    }
  }
);

function calculateCycleEndMillisServer(startMillis, billingFrequency, intervalDays) {
  const d = new Date(startMillis);
  switch (String(billingFrequency || "MONTHLY").toUpperCase()) {
    case "ANNUAL":
      d.setFullYear(d.getFullYear() + 1);
      return d.getTime();
    case "BIANNUAL":
      d.setMonth(d.getMonth() + 6);
      return d.getTime();
    case "QUARTERLY":
      d.setMonth(d.getMonth() + 3);
      return d.getTime();
    case "MONTHLY":
      d.setMonth(d.getMonth() + 1);
      return d.getTime();
    default:
      return startMillis + Math.max(1, Number(intervalDays || 30)) * 86400000;
  }
}

exports.verifyFlutterwaveWebhook = onRequest(
  { secrets: [FLW_SECRET_HASH, FLW_SECRET_KEY], cors: false, invoker: "public" },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ ok: false, error: "Method not allowed" });
      return;
    }

    const secretHash = String(FLW_SECRET_HASH.value() || "").trim();
    const secretKey = String(FLW_SECRET_KEY.value() || "").trim();
    const signature = String(req.get("verif-hash") || "").trim();

    if (!secretHash || !secretKey) {
      res.status(503).json({ ok: false, error: "Flutterwave webhook secrets are not configured." });
      return;
    }
    if (!signature || signature.length !== secretHash.length ||
      !crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(secretHash))) {
      res.status(401).json({ ok: false, error: "Invalid Flutterwave signature" });
      return;
    }

    try {
      const payload = typeof req.body === "string" ? JSON.parse(req.body) : (req.body || {});
      const txData = payload.data || payload;
      const flwTxId = String(txData.id || "").trim();
      const txRef = String(txData.tx_ref || "").trim();

      if (!flwTxId || !txRef) {
        res.status(400).json({ ok: false, error: "Missing transaction id or tx_ref" });
        return;
      }

      // Independent server-to-server verification against Flutterwave API
      const verifyResp = await fetch(`https://api.flutterwave.com/v3/transactions/${encodeURIComponent(flwTxId)}/verify`, {
        headers: { Authorization: `Bearer ${secretKey}` },
      });
      const verifyJson = await verifyResp.json();
      if (!verifyResp.ok || verifyJson.status !== "success" || verifyJson.data?.status !== "successful") {
        res.status(400).json({ ok: false, error: "Transaction did not verify as successful" });
        return;
      }

      const verified = verifyJson.data;
      const meta = verified.meta || txData.meta || {};
      const businessId = String(meta.businessId || "").trim();
      const planId = String(meta.planId || "plan_monthly_1500").trim();
      if (!businessId) {
        res.status(400).json({ ok: false, error: "Missing businessId in Flutterwave transaction metadata" });
        return;
      }

      // Idempotency check
      const payId = `flw_${flwTxId.replace(/[^a-zA-Z0-9_-]/g, "_")}`;
      const payRef = db.collection("subscription_payments").doc(payId);
      const existingPay = await payRef.get();
      if (existingPay.exists && existingPay.data()?.paymentStatus === "VERIFIED") {
        res.status(200).json({ ok: true, idempotent: true, paymentId: payId });
        return;
      }

      const [bizSnap, planSnap, entSnap, settingSnap, subSnap, quotaSnap, storageSnap] = await Promise.all([
        db.collection("businesses").doc(businessId).get(),
        db.collection("subscription_plans").doc(planId).get(),
        db.collection("plan_entitlements").doc(planId).get(),
        db.collection("platform_settings").doc("global").get(),
        db.collection("business_subscriptions").doc(businessId).get(),
        db.collection("usage_quotas").doc(`${businessId}_current`).get(),
        db.collection("storage_usage").doc(businessId).get(),
      ]);

      if (!bizSnap.exists) {
        res.status(404).json({ ok: false, error: "Business workspace not found" });
        return;
      }

      const biz = bizSnap.data() || {};
      const plan = planSnap.exists ? planSnap.data() : {
        planId,
        planCode: "MONTHLY",
        name: "Monthly",
        priceMinor: 150000,
        currency: "NGN",
        billingFrequency: "MONTHLY",
        intervalDays: 30,
      };
      const ent = entSnap.exists ? entSnap.data() : {
        maxReceiptsPerCycle: 300,
        rolloverEnabled: false,
        storageLimitBytes: 524288000,
        storageOverLimitAction: "BLOCK_NEW_UPLOADS",
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
      };
      const settings = settingSnap.exists ? settingSnap.data() : {};
      const graceDays = Number(settings.gracePeriodDays ?? 3);

      const amountMinor = Math.round(Number(verified.amount || 0) * 100);
      if (String(verified.currency || "").toUpperCase() !== String(plan.currency || "NGN").toUpperCase() ||
        amountMinor < Number(plan.priceMinor || 0)) {
        res.status(400).json({ ok: false, error: "Verified amount or currency mismatch" });
        return;
      }

      const now = Date.now();
      const existingSub = subSnap.exists ? subSnap.data() : null;
      const cycleStart = existingSub && existingSub.planId === plan.planId && Number(existingSub.cycleEndMillis || 0) > now
        ? Number(existingSub.cycleEndMillis)
        : now;
      const cycleEnd = calculateCycleEndMillisServer(cycleStart, plan.billingFrequency, plan.intervalDays);
      const graceEnd = cycleEnd + Math.max(0, graceDays) * 86400000;
      const invoiceId = `inv_${flwTxId}`;
      const invoiceNumber = `1803R-INV-${new Date(now).getFullYear()}-${String(flwTxId).slice(-6).toUpperCase()}`;

      const prevQuota = quotaSnap.exists ? quotaSnap.data() : null;
      const rolloverReceipts = Boolean(ent.rolloverEnabled) && prevQuota && Number(prevQuota.receiptsAllocated || 0) >= 0
        ? Math.max(0, Number(prevQuota.receiptsAllocated || 0) + Number(prevQuota.rolloverReceipts || 0) - Number(prevQuota.receiptsUsed || 0))
        : 0;

      const batch = db.batch();
      batch.set(payRef, {
        businessId,
        businessName: biz.tradingName || biz.registeredName || "",
        ownerUid: biz.ownerUid || "",
        ownerName: biz.ownerName || "",
        ownerEmail: biz.ownerEmail || "",
        ownerPhone: biz.ownerPhone || biz.phone || "",
        planId: plan.planId,
        planCode: plan.planCode,
        planName: plan.name,
        amountNgn: Number(verified.amount || 0),
        amountMinor,
        prorationCreditMinor: 0,
        currency: verified.currency || "NGN",
        txRef,
        idempotencyKey: `flw_${flwTxId}`,
        flwTransactionId: flwTxId,
        paymentMethod: "FLUTTERWAVE_WEBHOOK",
        paymentStatus: "VERIFIED",
        verifiedByUid: "system_flutterwave_webhook",
        verifiedByEmail: "webhook@flutterwave",
        verifiedAtMillis: now,
        cycleStartMillis: cycleStart,
        cycleEndMillis: cycleEnd,
        invoiceId,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });

      batch.update(db.collection("businesses").doc(businessId), {
        subscriptionStatus: "ACTIVE",
        subscriptionPlanCode: plan.planCode,
        subscriptionPlanId: plan.planId,
        subscriptionActivatedMillis: now,
        subscriptionExpiryMillis: cycleEnd,
        storageLimitBytes: ent.storageLimitBytes,
        updatedAt: FieldValue.serverTimestamp(),
      });

      batch.set(db.collection("business_subscriptions").doc(businessId), {
        businessId,
        ownerUid: biz.ownerUid || "",
        memberUids: biz.memberUids || [biz.ownerUid || ""],
        planId: plan.planId,
        planCode: plan.planCode,
        planNameSnapshot: plan.name,
        agreedPriceMinor: plan.priceMinor,
        agreedCurrency: plan.currency,
        agreedBillingFrequency: plan.billingFrequency,
        agreedIntervalDays: plan.intervalDays,
        agreedEntitlementsSnapshot: ent,
        status: "ACTIVE",
        cycleStartMillis: cycleStart,
        cycleEndMillis: cycleEnd,
        gracePeriodEndMillis: graceEnd,
        trialEndMillis: 0,
        autoRenew: true,
        cancelAtPeriodEnd: false,
        cancelledAtMillis: 0,
        pendingUpgradePlanId: "",
        creditBalanceMinor: 0,
        lastPaymentId: payId,
        lastInvoiceId: invoiceId,
        createdAt: existingSub?.createdAt || FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });

      batch.set(db.collection("subscription_invoices").doc(invoiceId), {
        invoiceNumber,
        businessId,
        businessName: biz.tradingName || biz.registeredName || "",
        ownerUid: biz.ownerUid || "",
        ownerEmail: biz.ownerEmail || "",
        subscriptionId: businessId,
        paymentId: payId,
        planId: plan.planId,
        planCode: plan.planCode,
        planNameSnapshot: plan.name,
        billingFrequencySnapshot: plan.billingFrequency,
        subtotalMinor: plan.priceMinor,
        prorationCreditMinor: 0,
        taxMinor: 0,
        totalMinor: amountMinor,
        currency: plan.currency,
        status: "PAID",
        cycleStartMillis: cycleStart,
        cycleEndMillis: cycleEnd,
        issuedAtMillis: now,
        paidAtMillis: now,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });

      batch.set(db.collection("usage_quotas").doc(`${businessId}_current`), {
        businessId,
        ownerUid: biz.ownerUid || "",
        memberUids: biz.memberUids || [biz.ownerUid || ""],
        cycleKey: `cycle_${cycleEnd}`,
        cycleStartMillis: cycleStart,
        cycleEndMillis: cycleEnd,
        receiptsAllocated: ent.maxReceiptsPerCycle,
        receiptsUsed: 0,
        rolloverReceipts,
        overageReceiptsUsed: 0,
        ocrAllocated: ent.maxOcrPerCycle,
        ocrUsed: 0,
        warningsSentPct: [],
        updatedAt: FieldValue.serverTimestamp(),
      });

      const prevStore = storageSnap.exists ? storageSnap.data() : {};
      batch.set(db.collection("storage_usage").doc(businessId), {
        businessId,
        ownerUid: biz.ownerUid || "",
        memberUids: biz.memberUids || [biz.ownerUid || ""],
        allocatedBytes: ent.storageLimitBytes,
        usedBytes: Number(prevStore.usedBytes ?? biz.storageUsedBytes ?? 0),
        bankReceiptsBytes: Number(prevStore.bankReceiptsBytes || 0),
        generatedReceiptsBytes: Number(prevStore.generatedReceiptsBytes || 0),
        brandingBytes: Number(prevStore.brandingBytes || 0),
        otherDocsBytes: Number(prevStore.otherDocsBytes || 0),
        limitBehavior: ent.storageOverLimitAction || "BLOCK_NEW_UPLOADS",
        warningThresholdsSent: [],
        updatedAt: FieldValue.serverTimestamp(),
      });

      batch.set(db.collection("plan_change_history").doc(`chg_${flwTxId}`), {
        businessId,
        businessName: biz.tradingName || biz.registeredName || "",
        ownerUid: biz.ownerUid || "",
        fromPlanId: existingSub?.planId || "",
        fromPlanName: existingSub?.planNameSnapshot || "",
        toPlanId: plan.planId,
        toPlanName: plan.name,
        changeType: existingSub ? (existingSub.planId === plan.planId ? "RENEWAL" : "UPGRADE") : "NEW",
        prorationCreditMinor: 0,
        chargedMinor: amountMinor,
        currency: plan.currency,
        actorUid: "system_flutterwave_webhook",
        actorEmail: "webhook@flutterwave",
        notes: `Verified via Flutterwave API (${flwTxId})`,
        createdAt: FieldValue.serverTimestamp(),
      });

      batch.set(db.collection("subscription_events").doc(`evt_${flwTxId}`), {
        actorUid: "system_flutterwave_webhook",
        actorEmail: "webhook@flutterwave",
        actorRole: "WEBHOOK",
        businessId,
        entityType: "business_subscriptions",
        entityId: businessId,
        action: "FLUTTERWAVE_WEBHOOK_VERIFIED",
        previousValue: `plan=${existingSub?.planId || "none"}`,
        newValue: `plan=${plan.planId},invoice=${invoiceNumber},amountMinor=${amountMinor}`,
        createdAt: FieldValue.serverTimestamp(),
      });

      await batch.commit();
      res.status(200).json({ ok: true, paymentId: payId, invoiceId });
    } catch (err) {
      console.error("Flutterwave webhook verification error:", err);
      res.status(500).json({ ok: false, error: String(err?.message || err) });
    }
  }
);

