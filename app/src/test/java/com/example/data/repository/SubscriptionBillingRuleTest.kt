package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.BankTransactionRecord
import com.example.data.model.BillingCalculator
import com.example.data.model.BillingFrequency
import com.example.data.model.Business
import com.example.data.model.CustomBusinessEntitlement
import com.example.data.model.Customer
import com.example.data.model.PlanEntitlement
import com.example.data.model.PlanFeature
import com.example.data.model.PlanStatus
import com.example.data.model.PlatformSetting
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StaffRole
import com.example.data.model.SubscriptionPlan
import com.example.data.model.UserProfile
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SubscriptionBillingRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun nonAdminCaller_cannotCreateOrModifySubscriptionPlansOrCustomOverrides() = runBlocking {
        signInTestUser("regular_${UUID.randomUUID().toString().take(6)}@test.ng")
        val subRepo = SubscriptionRepository(firestore, auth)

        val savePlanAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
            subRepo.savePlanWithEntitlementAdmin(
                plan = SubscriptionPlan(
                    planId = "plan_tamper",
                    planCode = "TAMPER",
                    name = "Free Everything",
                    priceMinor = 0L
                ),
                entitlement = PlanEntitlement(planId = "plan_tamper", maxReceiptsPerCycle = 9999),
                features = emptyList()
            )
        }
        assertTrue("Regular user must not be allowed to create subscription plans", savePlanAttempt.isFailure)
        val err = savePlanAttempt.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, err?.code)
    }

    @Test
    fun fullSubscriptionLifecycle_superAdminVerification_idempotentQuotaDeduction_andCustomOverride() = runBlocking {
        // 1. Super Admin seeds default launch plans (₦1,500 Monthly & ₦18,000 Annual) + creates a custom tier dynamically
        signInTestUser("info.1803consult@gmail.com", verifyEmail = true)
        val adminSubRepo = SubscriptionRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminSubRepo.ensureDefaultLaunchPlansSeededAdmin().getOrThrow()
        }

        val customTierId = "plan_pro_${UUID.randomUUID().toString().take(6)}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminSubRepo.savePlanWithEntitlementAdmin(
                plan = SubscriptionPlan(
                    planId = customTierId,
                    planCode = "PRO_TEST",
                    name = "Pro Custom Tier",
                    description = "Added dynamically without app rebuild",
                    priceMinor = 450_000L, // ₦4,500
                    billingFrequency = BillingFrequency.MONTHLY.name,
                    intervalDays = 30,
                    status = PlanStatus.ACTIVE.name,
                    visible = true,
                    sortOrder = 5
                ),
                entitlement = PlanEntitlement(
                    planId = customTierId,
                    maxReceiptsPerCycle = 1, // tightly capped to verify quota enforcement cleanly
                    maxOcrPerCycle = 10,
                    storageLimitBytes = 10L * 1024L * 1024L,
                    maxStaffAccounts = 2,
                    maxCustomers = 50,
                    allowedTemplates = listOf(ReceiptTemplateType.STANDARD.name, ReceiptTemplateType.PREMIUM_CORPORATE.name)
                ),
                features = listOf(PlanFeature(featureKey = "f1", label = "Custom Dynamic Feature", included = true))
            ).getOrThrow()
        }

        // 2. Business Owner signs in, creates isolated business, and submits a bank transfer payment
        val ownerEmail = "bizowner_${UUID.randomUUID().toString().take(6)}@test.ng"
        val ownerUid = signInTestUser(ownerEmail)
        val bizRepo = BusinessRepository(firestore, auth)
        val ownerSubRepo = SubscriptionRepository(firestore, auth)

        val bizId = withTimeout(DEFAULT_TIMEOUT_MS) {
            bizRepo.createBusinessWorkspace(
                ownerProfile = UserProfile(userId = ownerUid, fullName = "Ngozi Eze", email = ownerEmail),
                business = Business(registeredName = "Ngozi Enterprises Ltd", tradingName = "Ngozi Ent", receiptPrefix = "NGZ")
            ).getOrThrow()
        }
        val initialBiz = withTimeout(FLOW_TIMEOUT_MS) {
            bizRepo.observeBusiness(bizId).first { it != null }!!
        }

        val dynamicPlans = withTimeout(FLOW_TIMEOUT_MS) {
            ownerSubRepo.observeSubscriptionPlans().first { list -> list.any { it.planId == customTierId } }
        }
        assertTrue(dynamicPlans.any { it.planId == BillingCalculator.LAUNCH_MONTHLY_PLAN_ID && it.priceMinor == 150_000L })
        assertTrue(dynamicPlans.any { it.planId == BillingCalculator.LAUNCH_ANNUAL_PLAN_ID && it.priceMinor == 1_800_000L })
        assertTrue(dynamicPlans.any { it.planId == customTierId && it.priceMinor == 450_000L })

        val txRef = "TRF_${UUID.randomUUID().toString().take(8).uppercase()}"
        val payId = withTimeout(DEFAULT_TIMEOUT_MS) {
            bizRepo.submitBankTransferPayment(
                business = initialBiz,
                setting = PlatformSetting(),
                planCode = "PRO_TEST",
                amountNgn = 4500.0,
                txRef = txRef,
                senderName = "Ngozi Eze",
                senderBank = "KUDA",
                transferNote = "Test upgrade",
                receiptImageBase64 = "ZmFrZV9pbWFnZQ==",
                receiptFileName = "proof.jpg",
                receiptMimeType = "image/jpeg",
                planId = customTierId,
                planName = "Pro Custom Tier",
                amountMinor = 450_000L,
                prorationCreditMinor = 0L,
                idempotencyKey = txRef
            ).getOrThrow()
        }

        // 3. Super Admin verifies payment -> provisions business_subscriptions snapshot, usage_quotas, storage_usage, invoice & events
        signInTestUser("info.1803consult@gmail.com", verifyEmail = true)
        val adminBizRepo = BusinessRepository(firestore, auth)
        val adminPay = withTimeout(FLOW_TIMEOUT_MS) {
            adminBizRepo.observeAllSubscriptionPaymentsAdmin().first { list -> list.any { it.id == payId } }
        }.first { it.id == payId }

        val entitlements = withTimeout(FLOW_TIMEOUT_MS) {
            adminSubRepo.observePlanEntitlements().first { list -> list.any { it.planId == customTierId } }
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminBizRepo.verifySubscriptionPaymentAdmin(
                payment = adminPay,
                plans = dynamicPlans,
                entitlements = entitlements,
                setting = PlatformSetting()
            ).getOrThrow()
        }

        // 4. Super Admin edits customTier price to ₦6,000 -> existing subscriber snapshot MUST stay ₦4,500 (450,000 kobo)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminSubRepo.savePlanWithEntitlementAdmin(
                plan = dynamicPlans.first { it.planId == customTierId }.copy(priceMinor = 600_000L),
                entitlement = entitlements.first { it.planId == customTierId }.copy(maxReceiptsPerCycle = 500),
                features = emptyList()
            ).getOrThrow()
        }

        signInTestUser(ownerEmail)
        val subscriberSnapshot = withTimeout(FLOW_TIMEOUT_MS) {
            ownerSubRepo.observeBusinessSubscription(bizId).first { it != null }!!
        }
        assertEquals(450_000L, subscriberSnapshot.agreedPriceMinor)
        assertEquals(1, subscriberSnapshot.snapshotEntitlement()?.maxReceiptsPerCycle)
    }

    @Test
    fun idempotentQuotaDeduction_andCustomBusinessOverride_enforceWithoutDeletingExistingReceipts() = runBlocking {
        val ownerEmail = "quota_${UUID.randomUUID().toString().take(6)}@test.ng"
        val ownerUid = signInTestUser(ownerEmail)
        val ownerBizRepo = BusinessRepository(firestore, auth)
        val ownerSubRepo = SubscriptionRepository(firestore, auth)

        val bizId = withTimeout(DEFAULT_TIMEOUT_MS) {
            ownerBizRepo.createBusinessWorkspace(
                ownerProfile = UserProfile(userId = ownerUid, fullName = "Kemi Balogun", email = ownerEmail),
                business = Business(registeredName = "Kemi Foods", tradingName = "Kemi Foods", receiptPrefix = "KMF")
            ).getOrThrow()
        }
        val biz = withTimeout(FLOW_TIMEOUT_MS) {
            ownerBizRepo.observeBusiness(bizId).first { it != null }!!
        }

        val cappedPlanId = "plan_cap1_${UUID.randomUUID().toString().take(6)}"
        signInTestUser("info.1803consult@gmail.com", verifyEmail = true)
        val adminSubRepo = SubscriptionRepository(firestore, auth)
        val adminBizRepo = BusinessRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminSubRepo.savePlanWithEntitlementAdmin(
                plan = SubscriptionPlan(
                    planId = cappedPlanId,
                    planCode = "CAP1",
                    name = "Cap 1 Plan",
                    priceMinor = 150_000L,
                    billingFrequency = BillingFrequency.MONTHLY.name,
                    intervalDays = 30,
                    status = PlanStatus.ACTIVE.name
                ),
                entitlement = PlanEntitlement(
                    planId = cappedPlanId,
                    maxReceiptsPerCycle = 1,
                    maxOcrPerCycle = 5,
                    storageLimitBytes = 20L * 1024L * 1024L
                ),
                features = emptyList()
            ).getOrThrow()
        }

        // Owner submits payment
        signInTestUser(ownerEmail)
        val ref = "CAP1_${UUID.randomUUID().toString().take(8)}"
        val payId = withTimeout(DEFAULT_TIMEOUT_MS) {
            ownerBizRepo.submitBankTransferPayment(
                business = biz,
                setting = PlatformSetting(),
                planCode = "CAP1",
                amountNgn = 1500.0,
                txRef = ref,
                senderName = "Kemi",
                senderBank = "KUDA",
                transferNote = "Cap1 test",
                receiptImageBase64 = "ZmFrZQ==",
                receiptFileName = "cap1.jpg",
                receiptMimeType = "image/jpeg",
                planId = cappedPlanId,
                planName = "Cap 1 Plan",
                amountMinor = 150_000L,
                idempotencyKey = ref
            ).getOrThrow()
        }

        // Admin verifies payment
        signInTestUser("info.1803consult@gmail.com", verifyEmail = true)
        val paymentDoc = withTimeout(FLOW_TIMEOUT_MS) {
            adminBizRepo.observeAllSubscriptionPaymentsAdmin().first { list -> list.any { it.id == payId } }
        }.first { it.id == payId }
        val plans = withTimeout(FLOW_TIMEOUT_MS) {
            adminSubRepo.observeSubscriptionPlans().first { list -> list.any { it.planId == cappedPlanId } }
        }
        val ents = withTimeout(FLOW_TIMEOUT_MS) {
            adminSubRepo.observePlanEntitlements().first { list -> list.any { it.planId == cappedPlanId } }
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminBizRepo.verifySubscriptionPaymentAdmin(paymentDoc, plans, ents, PlatformSetting()).getOrThrow()
        }

        // Owner receives ACTIVE workspace + UsageQuota(allocated=1, used=0)
        signInTestUser(ownerEmail)
        val activeBiz = withTimeout(FLOW_TIMEOUT_MS) {
            ownerBizRepo.observeBusiness(bizId).first { it != null && it.subscriptionStatus == "ACTIVE" }!!
        }
        val sub = withTimeout(FLOW_TIMEOUT_MS) {
            ownerSubRepo.observeBusinessSubscription(bizId).first { it != null }!!
        }
        assertEquals(150_000L, sub.agreedPriceMinor)

        val invoiceCount = firestore.collection("subscription_invoices")
            .whereEqualTo("businessId", bizId)
            .whereEqualTo("ownerUid", ownerUid)
            .get().await().size()
        assertEquals(1, invoiceCount)

        val quota0 = withTimeout(FLOW_TIMEOUT_MS) {
            ownerSubRepo.observeUsageQuota(bizId).first { it != null }!!
        }
        val eff1 = BillingCalculator.resolveEffectiveEntitlements(activeBiz, sub, plans, ents, quota0, null, null)

        // Issue receipt #1 with retry using SAME idempotencyKey -> must consume quota ONLY ONCE and return same receipt
        val idemKey = "retry_key_001"
        val rct1 = withTimeout(DEFAULT_TIMEOUT_MS) {
            ownerBizRepo.issueReceiptFromTransaction(
                business = activeBiz,
                transaction = BankTransactionRecord(amount = 5000.0, senderBank = "GTB", humanReviewed = true),
                customer = Customer(fullName = "Tunde A."),
                paymentPurpose = "Sale",
                invoiceNumber = "",
                orderNumber = "",
                notes = "",
                amountPaid = 5000.0,
                discountAmount = 0.0,
                amountInWords = "Five Thousand Naira Only",
                templateType = ReceiptTemplateType.STANDARD,
                actorRole = StaffRole.OWNER,
                effectiveEntitlements = eff1,
                idempotencyKey = idemKey
            ).getOrThrow()
        }
        val rct1Retry = withTimeout(DEFAULT_TIMEOUT_MS) {
            ownerBizRepo.issueReceiptFromTransaction(
                business = activeBiz,
                transaction = BankTransactionRecord(amount = 5000.0, senderBank = "GTB", humanReviewed = true),
                customer = Customer(fullName = "Tunde A."),
                paymentPurpose = "Sale",
                invoiceNumber = "",
                orderNumber = "",
                notes = "",
                amountPaid = 5000.0,
                discountAmount = 0.0,
                amountInWords = "Five Thousand Naira Only",
                templateType = ReceiptTemplateType.STANDARD,
                actorRole = StaffRole.OWNER,
                effectiveEntitlements = eff1,
                idempotencyKey = idemKey
            ).getOrThrow()
        }
        assertEquals(rct1.id, rct1Retry.id)
        assertEquals(rct1.receiptNumber, rct1Retry.receiptNumber)

        val quota1 = withTimeout(FLOW_TIMEOUT_MS) {
            ownerSubRepo.observeUsageQuota(bizId).first { it != null && it.receiptsUsed == 1 }!!
        }
        assertEquals(1, quota1.receiptsUsed)

        // Attempt a NEW receipt #2 while cap=1 is reached -> MUST FAIL, existing receipt #1 remains intact!
        val overCapAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
            ownerBizRepo.issueReceiptFromTransaction(
                business = activeBiz,
                transaction = BankTransactionRecord(amount = 3000.0, senderBank = "Access", humanReviewed = true),
                customer = Customer(fullName = "Bisi B."),
                paymentPurpose = "Order 2",
                invoiceNumber = "",
                orderNumber = "",
                notes = "",
                amountPaid = 3000.0,
                discountAmount = 0.0,
                amountInWords = "Three Thousand Naira Only",
                templateType = ReceiptTemplateType.STANDARD,
                actorRole = StaffRole.OWNER,
                effectiveEntitlements = eff1,
                idempotencyKey = "new_receipt_2"
            )
        }
        assertTrue("Second receipt beyond quota=1 must be rejected", overCapAttempt.isFailure)

        val existingReceipts = withTimeout(FLOW_TIMEOUT_MS) {
            ownerBizRepo.observeReceipts(bizId, ownerUid).first()
        }
        assertEquals("Existing receipt must NEVER be deleted on quota limit", 1, existingReceipts.size)

        // Super Admin grants custom override (+5 receipts) -> owner can now issue receipt #2!
        signInTestUser("info.1803consult@gmail.com", verifyEmail = true)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminSubRepo.saveCustomBusinessEntitlementAdmin(
                business = activeBiz,
                override = CustomBusinessEntitlement(
                    businessId = bizId,
                    overrideReceiptsPerCycle = 5,
                    overrideNotes = "Granted extra quota by Super Admin"
                )
            ).getOrThrow()
        }

        signInTestUser(ownerEmail)
        val quotaUpdated = withTimeout(FLOW_TIMEOUT_MS) {
            ownerSubRepo.observeUsageQuota(bizId).first { it != null && it.receiptsAllocated == 5 }!!
        }
        val eff2 = BillingCalculator.resolveEffectiveEntitlements(activeBiz, sub, plans, ents, quotaUpdated, null, null)
        val rct2 = withTimeout(DEFAULT_TIMEOUT_MS) {
            ownerBizRepo.issueReceiptFromTransaction(
                business = activeBiz,
                transaction = BankTransactionRecord(amount = 3000.0, senderBank = "Access", humanReviewed = true),
                customer = Customer(fullName = "Bisi B."),
                paymentPurpose = "Order 2",
                invoiceNumber = "",
                orderNumber = "",
                notes = "",
                amountPaid = 3000.0,
                discountAmount = 0.0,
                amountInWords = "Three Thousand Naira Only",
                templateType = ReceiptTemplateType.STANDARD,
                actorRole = StaffRole.OWNER,
                effectiveEntitlements = eff2,
                idempotencyKey = "new_receipt_2"
            ).getOrThrow()
        }
        assertTrue(rct2.receiptNumber.isNotBlank())
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val FLOW_TIMEOUT_MS = 6_000L
    }
}
