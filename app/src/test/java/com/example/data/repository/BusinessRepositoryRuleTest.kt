package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.BankTransactionRecord
import com.example.data.model.Business
import com.example.data.model.Customer
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StaffRole
import com.example.data.model.SubscriptionStatus
import com.example.data.model.UserProfile
import com.example.data.model.VerificationStatus
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BusinessRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createBusinessWorkspace_authenticatedOwner_createsIsolatedBusiness() = runBlocking {
        val uid = signInTestUser("owner_${UUID.randomUUID().toString().take(6)}@test.ng")
        val repo = BusinessRepository(firestore, auth)

        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createBusinessWorkspace(
                ownerProfile = UserProfile(
                    userId = uid,
                    fullName = "Chidi Nwosu",
                    email = "owner@test.ng",
                    phone = "+2348012345678",
                    state = "Lagos",
                    city = "Ikeja"
                ),
                business = Business(
                    registeredName = "Lagos Prime Services Ltd",
                    tradingName = "Lagos Prime",
                    receiptPrefix = "LPR"
                )
            )
        }
        assertTrue("Expected workspace creation to succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val businessId = result.getOrThrow()

        val businesses = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeUserBusinesses(uid).first { list -> list.any { it.id == businessId } }
        }
        val created = businesses.first { it.id == businessId }
        assertEquals("Lagos Prime", created.tradingName)
        assertEquals(SubscriptionStatus.TRIALING.name, created.subscriptionStatus)
        assertEquals("plan_trial_90d", created.subscriptionPlanId)
        assertEquals("FREE_TRIAL", created.subscriptionPlanCode)
        assertTrue(created.isSubscriptionActive())
        assertTrue(created.trialDaysRemaining() in 89..90)
    }

    @Test
    fun activateLegacyFreeTrialIfEligible_transitionsAwaitingSubscriptionOnce() = runBlocking {
        val ownerUid = signInTestUser("legacy_${UUID.randomUUID().toString().take(6)}@test.ng")
        val repo = BusinessRepository(firestore, auth)
        val legacyBizId = "biz_legacy_${UUID.randomUUID().toString().replace("-", "").take(10)}"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            firestore.collection("businesses").document(legacyBizId).set(
                mapOf(
                    "ownerUid" to ownerUid,
                    "memberUids" to listOf(ownerUid),
                    "ownerName" to "Legacy Owner",
                    "ownerEmail" to "legacy@test.ng",
                    "registeredName" to "Legacy Enterprise Ltd",
                    "tradingName" to "Legacy Enterprise",
                    "defaultCurrency" to "NGN",
                    "receiptPrefix" to "LGC",
                    "subscriptionStatus" to SubscriptionStatus.AWAITING_SUBSCRIPTION.name,
                    "subscriptionActivatedMillis" to 0L,
                    "subscriptionExpiryMillis" to 0L,
                    "accountStatus" to "ACTIVE",
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            ).await()
        }

        val initial = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeBusiness(legacyBizId).first { it != null }!!
        }
        assertEquals(SubscriptionStatus.AWAITING_SUBSCRIPTION.name, initial.subscriptionStatus)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.activateLegacyFreeTrialIfEligible(initial).getOrThrow()
        }

        val activated = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeBusiness(legacyBizId).first { it?.subscriptionStatus == SubscriptionStatus.TRIALING.name }!!
        }
        assertEquals("plan_trial_90d", activated.subscriptionPlanId)
        assertEquals("FREE_TRIAL", activated.subscriptionPlanCode)
        assertTrue(activated.subscriptionActivatedMillis > 0L)
        assertTrue(activated.trialDaysRemaining() in 89..90)

        // Calling a second time must be a safe no-op without changing activatedMillis
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.activateLegacyFreeTrialIfEligible(activated).getOrThrow()
        }
        val afterSecondCall = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeBusiness(legacyBizId).first { it != null }!!
        }
        assertEquals(activated.subscriptionActivatedMillis, afterSecondCall.subscriptionActivatedMillis)
    }

    @Test
    fun crossTenantIsolation_bobCannotReadAliceCustomers() = runBlocking {
        val aliceUid = signInTestUser("alice_${UUID.randomUUID().toString().take(6)}@test.ng")
        val aliceRepo = BusinessRepository(firestore, auth)
        val bizId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createBusinessWorkspace(
                ownerProfile = UserProfile(userId = aliceUid, fullName = "Alice", email = "alice@test.ng"),
                business = Business(registeredName = "Alice Co", tradingName = "Alice Co", receiptPrefix = "ALC")
            ).getOrThrow()
        }
        val biz = withTimeout(FLOW_TIMEOUT_MS) {
            aliceRepo.observeBusiness(bizId).first { it != null }!!
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.upsertCustomer(biz, Customer(fullName = "Tunde Bakare", phone = "08030000000")).getOrThrow()
        }

        // Switch to Bob
        val bobUid = signInTestUser("bob_${UUID.randomUUID().toString().take(6)}@test.ng")
        val bobRepo = BusinessRepository(firestore, auth)

        // 1. Filtered member query returns empty list (zero cross-tenant leakage)
        val bobCustomers = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeCustomers(bizId, bobUid).first()
        }
        assertTrue("Bob must never see Alice's customers", bobCustomers.isEmpty())

        // 2. Direct get on Alice's business fails with PERMISSION_DENIED
        var capturedError: Exception? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeBusiness(bizId).first()
            }
        } catch (e: Exception) {
            capturedError = e
        }
        val fsErr = capturedError?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    @Test
    fun unauthenticatedCaller_observeBusinesses_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = BusinessRepository(firestore, auth)
        var capturedError: Exception? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeUserBusinesses("anon_uid").first()
            }
        } catch (e: Exception) {
            capturedError = e
        }
        val fsErr = capturedError?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    @Test
    fun upsertCustomer_andVerificationStatusTransitions_preserveContactAndReconcileTotals() = runBlocking {
        val ownerUid = signInTestUser("ver_${UUID.randomUUID().toString().take(6)}@test.ng")
        val repo = BusinessRepository(firestore, auth)
        val bizId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createBusinessWorkspace(
                ownerProfile = UserProfile(userId = ownerUid, fullName = "Amina", email = "amina@test.ng"),
                business = Business(registeredName = "Amina Logistics", tradingName = "Amina", receiptPrefix = "AMN")
            ).getOrThrow()
        }
        val biz = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeBusiness(bizId).first { it != null }!!
        }

        val custId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertCustomer(
                biz,
                Customer(fullName = "Bola Tinubu", phone = "08011112222", email = "bola@client.ng")
            ).getOrThrow()
        }

        // Expired 90-day trial business must be blocked from issuing receipts until subscribed
        val expiredTrialBiz = biz.copy(subscriptionExpiryMillis = System.currentTimeMillis() - 60_000L)
        assertTrue(expiredTrialBiz.isTrialExpired())
        val blockedAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.issueReceiptFromTransaction(
                business = expiredTrialBiz,
                transaction = BankTransactionRecord(amount = 25_000.0, senderBank = "GTBank", humanReviewed = true),
                customer = Customer(fullName = "Bola Tinubu"),
                paymentPurpose = "Freight Delivery",
                invoiceNumber = "",
                orderNumber = "",
                notes = "",
                amountPaid = 25_000.0,
                discountAmount = 0.0,
                amountInWords = "Twenty-Five Thousand Naira Only",
                templateType = ReceiptTemplateType.STANDARD,
                actorRole = StaffRole.OWNER
            )
        }
        assertTrue(blockedAttempt.isFailure)

        // Active 90-day trial (or paid ACTIVE subscription) can issue receipts cleanly and preserves CRM details
        val receipt = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.issueReceiptFromTransaction(
                business = biz,
                transaction = BankTransactionRecord(amount = 25_000.0, senderBank = "GTBank", humanReviewed = true),
                customer = Customer(fullName = "Bola Tinubu"),
                paymentPurpose = "Freight Delivery",
                invoiceNumber = "",
                orderNumber = "",
                notes = "",
                amountPaid = 25_000.0,
                discountAmount = 0.0,
                amountInWords = "Twenty-Five Thousand Naira Only",
                templateType = ReceiptTemplateType.STANDARD,
                actorRole = StaffRole.OWNER
            ).getOrThrow()
        }

        val afterIssue = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCustomers(bizId, ownerUid).first { list -> list.any { it.id == custId && it.receiptCount == 1L } }
        }.first { it.id == custId }
        assertEquals("08011112222", afterIssue.phone)
        assertEquals("bola@client.ng", afterIssue.email)

        // Mark VERIFIED -> totalVerifiedAmount increases to 25000.0
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateReceiptVerificationStatus(biz, receipt, VerificationStatus.VERIFIED, "Credited", StaffRole.OWNER).getOrThrow()
        }
        val afterVerify = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCustomers(bizId, ownerUid).first { list -> list.any { it.id == custId && it.totalVerifiedAmount >= 25_000.0 } }
        }.first { it.id == custId }
        assertEquals(25_000.0, afterVerify.totalVerifiedAmount, 0.01)

        // Transition VERIFIED -> REJECTED -> totalVerifiedAmount decrements back to 0.0
        val verifiedReceipt = receipt.copy(verificationStatus = VerificationStatus.VERIFIED.name)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateReceiptVerificationStatus(biz, verifiedReceipt, VerificationStatus.REJECTED, "Chargeback", StaffRole.OWNER).getOrThrow()
        }
        val afterReject = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCustomers(bizId, ownerUid).first { list -> list.any { it.id == custId && it.totalVerifiedAmount == 0.0 } }
        }.first { it.id == custId }
        assertEquals(0.0, afterReject.totalVerifiedAmount, 0.01)
    }

    @Test
    fun acceptStaffInvite_preservesUserProfileAndBackfillsWorkspaceAccess() = runBlocking {
        val ownerUid = signInTestUser("inv_owner_${UUID.randomUUID().toString().take(6)}@test.ng")
        val ownerRepo = BusinessRepository(firestore, auth)
        val bizId = withTimeout(DEFAULT_TIMEOUT_MS) {
            ownerRepo.createBusinessWorkspace(
                ownerProfile = UserProfile(userId = ownerUid, fullName = "Owner One", email = "owner1@test.ng"),
                business = Business(registeredName = "Kano Retail Ltd", tradingName = "Kano Retail", receiptPrefix = "KNR")
            ).getOrThrow()
        }
        val biz = withTimeout(FLOW_TIMEOUT_MS) {
            ownerRepo.observeBusiness(bizId).first { it != null }!!
        }
        val staffEmail = "staff_${UUID.randomUUID().toString().take(6)}@test.ng"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            ownerRepo.inviteStaff(biz, staffEmail, "Staff Member", StaffRole.RECEIPT_OFFICER).getOrThrow()
        }

        // Staff signs in and saves a profile first
        val staffUid = signInTestUser(staffEmail)
        val staffRepo = BusinessRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            staffRepo.saveUserProfile(
                UserProfile(
                    userId = staffUid,
                    fullName = "Saved Staff Name",
                    email = staffEmail,
                    phone = "08099998888",
                    city = "Abuja"
                )
            ).getOrThrow()
        }

        val invites = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observePendingInvitesForEmail(staffEmail).first { it.isNotEmpty() }
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            staffRepo.acceptStaffInvite(invites.first()).getOrThrow()
        }

        // Existing user profile phone/city must remain preserved
        val profileSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
            firestore.collection("users").document(staffUid).get().await()
        }
        assertEquals("08099998888", profileSnap.getString("phone"))
        assertEquals("Abuja", profileSnap.getString("city"))
        assertEquals(bizId, profileSnap.getString("activeBusinessId"))

        // Staff can observe historical audit logs and issue a receipt immediately
        val logs = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeAuditLogs(bizId, staffUid).first { it.isNotEmpty() }
        }
        assertTrue(logs.isNotEmpty())
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8000L
        const val FLOW_TIMEOUT_MS = 5000L
    }
}

