package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AppNotification
import com.example.data.model.AuditLog
import com.example.data.model.BankTransactionRecord
import com.example.data.model.BillingCalculator
import com.example.data.model.BillingFrequency
import com.example.data.model.Business
import com.example.data.model.CatalogItem
import com.example.data.model.Customer
import com.example.data.model.DailyLedgerSheet
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.LedgerCalculator
import com.example.data.model.MailNotification
import com.example.data.model.PlatformAnnouncement
import com.example.data.model.PlatformSetting
import com.example.data.model.PlanEntitlement
import com.example.data.model.PlanStatus
import com.example.data.model.PublicVerification
import com.example.data.model.ReceiptRecord
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StaffInvite
import com.example.data.model.StaffMember
import com.example.data.model.StaffRole
import com.example.data.model.StorageOverLimitAction
import com.example.data.model.StorageUsage
import com.example.data.model.SubscriptionPayment
import com.example.data.model.SubscriptionPlan
import com.example.data.model.SubscriptionStatus
import com.example.data.model.SupportTicket
import com.example.data.model.UsageQuota
import com.example.data.model.UsageType
import com.example.data.model.UserProfile
import com.example.data.model.VerificationStatus
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

class BusinessRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        Firebase.auth
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    private fun requireUserEmail(): String =
        auth.currentUser?.email.orEmpty()

    private fun requireUserName(): String =
        auth.currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: requireUserEmail().substringBefore("@").ifBlank { "Business User" }

    // --- USER PROFILE ---

    fun observeUserProfile(userId: String): Flow<UserProfile?> =
        db.collection("users").document(userId)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                logFlowError(error, OperationType.GET, "users/$userId", userId)
                throw error
            }

    suspend fun saveUserProfile(profile: UserProfile): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val existing = docRef.get().await()
        val payload = mapOf(
            "userId" to uid,
            "fullName" to profile.fullName.trim(),
            "email" to profile.email.ifBlank { requireUserEmail() },
            "phone" to profile.phone.trim(),
            "country" to profile.country.ifBlank { "Nigeria" },
            "state" to profile.state.trim(),
            "city" to profile.city.trim(),
            "address" to profile.address.trim(),
            "activeBusinessId" to profile.activeBusinessId,
            "createdAt" to (existing.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(payload).await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "users") }

    // --- BUSINESS WORKSPACES (MULTI-TENANT ISOLATED) ---

    fun observeUserBusinesses(userId: String): Flow<List<Business>> =
        db.collection("businesses")
            .whereArrayContains("memberUids", userId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(Business::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses", userId)
                throw error
            }

    fun observeBusiness(businessId: String): Flow<Business?> =
        db.collection("businesses").document(businessId)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(Business::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                logFlowError(error, OperationType.GET, "businesses/$businessId", auth.currentUser?.uid)
                throw error
            }

    suspend fun createBusinessWorkspace(
        ownerProfile: UserProfile,
        business: Business
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val businessId = "biz_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val bizRef = db.collection("businesses").document(businessId)
        val userRef = db.collection("users").document(uid)
        val ownerStaffRef = bizRef.collection("staff").document(uid)
        val auditRef = bizRef.collection("audit_logs").document("log_${UUID.randomUUID().toString().take(12)}")

        val memberUids = listOf(uid)
        val batch = db.batch()

        val existingUser = userRef.get().await()
        val userPayload = mapOf(
            "userId" to uid,
            "fullName" to ownerProfile.fullName.trim(),
            "email" to ownerProfile.email.ifBlank { requireUserEmail() },
            "phone" to ownerProfile.phone.trim(),
            "country" to ownerProfile.country.ifBlank { "Nigeria" },
            "state" to ownerProfile.state.trim(),
            "city" to ownerProfile.city.trim(),
            "address" to ownerProfile.address.trim(),
            "activeBusinessId" to businessId,
            "createdAt" to (existingUser.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(userRef, userPayload)

        val now = System.currentTimeMillis()
        val trialEnd = now + BillingCalculator.FREE_TRIAL_MILLIS
        val trialEnt = BillingCalculator.freeTrialEntitlement()

        val bizPayload = mutableMapOf<String, Any>(
            "ownerUid" to uid,
            "memberUids" to memberUids,
            "ownerName" to ownerProfile.fullName.trim(),
            "ownerEmail" to ownerProfile.email.ifBlank { requireUserEmail() },
            "ownerPhone" to ownerProfile.phone.trim(),
            "registeredName" to business.registeredName.trim(),
            "tradingName" to business.tradingName.ifBlank { business.registeredName.trim() },
            "category" to business.category.trim(),
            "description" to business.description.trim(),
            "structure" to business.structure.ifBlank { "Business Name" },
            "address" to business.address.trim(),
            "phone" to business.phone.trim(),
            "email" to business.email.trim(),
            "website" to business.website.trim(),
            "cacNumber" to business.cacNumber.trim(),
            "socialMedia" to business.socialMedia.trim(),
            "authorisedRepName" to business.authorisedRepName.ifBlank { ownerProfile.fullName.trim() },
            "logoBase64" to business.logoBase64,
            "signatureBase64" to business.signatureBase64,
            "stampText" to business.stampText.trim(),
            "primaryColorHex" to business.primaryColorHex.ifBlank { "#054230" },
            "secondaryColorHex" to business.secondaryColorHex.ifBlank { "#10B981" },
            "accentColorHex" to business.accentColorHex.ifBlank { "#D99B26" },
            "backgroundColorHex" to business.backgroundColorHex.ifBlank { "#FFFFFF" },
            "textColorHex" to business.textColorHex.ifBlank { "#0F172A" },
            "defaultCurrency" to business.defaultCurrency.ifBlank { "NGN" },
            "receiptPrefix" to business.receiptPrefix.trim().uppercase().ifBlank { "RCN" },
            "numberingFormat" to business.numberingFormat.ifBlank { "{PREFIX}/RCT/{YEAR}/{SEQ}" },
            "defaultAcknowledgement" to business.defaultAcknowledgement.ifBlank {
                "Received with thanks from the above-named customer the sum stated herein for the service or goods described in this receipt."
            },
            "businessSlogan" to business.businessSlogan.trim(),
            "defaultTemplate" to business.defaultTemplate.ifBlank { ReceiptTemplateType.STANDARD.name },
            "publicVerificationEnabled" to business.publicVerificationEnabled,
            "maskAccountNumbers" to business.maskAccountNumbers,
            "showSignature" to business.showSignature,
            "showQrCode" to business.showQrCode,
            "subscriptionStatus" to SubscriptionStatus.TRIALING.name,
            "subscriptionPlanCode" to "FREE_TRIAL",
            "subscriptionPlanId" to BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID,
            "subscriptionExpiryMillis" to trialEnd,
            "subscriptionActivatedMillis" to now,
            "accountStatus" to "ACTIVE",
            "storageUsedBytes" to business.logoBase64.length.toLong(),
            "storageLimitBytes" to trialEnt.storageLimitBytes,
            "receiptsCount" to 0L,
            "uploadedDocsCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(bizRef, bizPayload)

        val subPayload = mapOf(
            "businessId" to businessId,
            "ownerUid" to uid,
            "memberUids" to memberUids,
            "planId" to BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID,
            "planCode" to "FREE_TRIAL",
            "planNameSnapshot" to "90-Day Free Trial",
            "agreedPriceMinor" to 0L,
            "agreedCurrency" to "NGN",
            "agreedBillingFrequency" to BillingFrequency.CUSTOM.name,
            "agreedIntervalDays" to BillingCalculator.FREE_TRIAL_DAYS,
            "agreedEntitlementsSnapshot" to trialEnt.toSnapshotMap(),
            "status" to SubscriptionStatus.TRIALING.name,
            "cycleStartMillis" to now,
            "cycleEndMillis" to trialEnd,
            "gracePeriodEndMillis" to trialEnd,
            "trialEndMillis" to trialEnd,
            "autoRenew" to false,
            "cancelAtPeriodEnd" to false,
            "cancelledAtMillis" to 0L,
            "pendingUpgradePlanId" to "",
            "creditBalanceMinor" to 0L,
            "lastPaymentId" to "",
            "lastInvoiceId" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(db.collection("business_subscriptions").document(businessId), subPayload)

        val quotaPayload = mapOf(
            "businessId" to businessId,
            "ownerUid" to uid,
            "memberUids" to memberUids,
            "cycleKey" to "trial_$trialEnd",
            "receiptsAllocated" to trialEnt.maxReceiptsPerCycle,
            "receiptsUsed" to 0,
            "rolloverReceipts" to 0,
            "overageReceiptsUsed" to 0,
            "ocrAllocated" to trialEnt.maxOcrPerCycle,
            "ocrUsed" to 0,
            "warningsSentPct" to emptyList<Int>(),
            "cycleStartMillis" to now,
            "cycleEndMillis" to trialEnd,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(db.collection("usage_quotas").document("${businessId}_current"), quotaPayload)

        val storagePayload = mapOf(
            "businessId" to businessId,
            "ownerUid" to uid,
            "memberUids" to memberUids,
            "usedBytes" to business.logoBase64.length.toLong(),
            "allocatedBytes" to trialEnt.storageLimitBytes,
            "bankReceiptsBytes" to 0L,
            "generatedReceiptsBytes" to 0L,
            "brandingBytes" to business.logoBase64.length.toLong(),
            "limitBehavior" to trialEnt.storageOverLimitAction,
            "warningThresholdsSent" to emptyList<Int>(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(db.collection("storage_usage").document(businessId), storagePayload)

        val staffPayload = mapOf(
            "businessId" to businessId,
            "ownerUid" to uid,
            "memberUids" to memberUids,
            "staffUid" to uid,
            "fullName" to ownerProfile.fullName.trim(),
            "email" to ownerProfile.email.ifBlank { requireUserEmail() },
            "role" to StaffRole.OWNER.name,
            "active" to true,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(ownerStaffRef, staffPayload)

        val auditPayload = mapOf(
            "businessId" to businessId,
            "ownerUid" to uid,
            "memberUids" to memberUids,
            "actorUid" to uid,
            "actorName" to ownerProfile.fullName.trim(),
            "actorRole" to StaffRole.OWNER.name,
            "action" to "BUSINESS_WORKSPACE_CREATED",
            "targetType" to "BUSINESS",
            "targetId" to businessId,
            "details" to "Registered business workspace ${business.registeredName} with 90-day free trial",
            "createdAt" to FieldValue.serverTimestamp()
        )
        batch.set(auditRef, auditPayload)

        batch.commit().await()
        businessId
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.CREATE, "businesses") }

    suspend fun activateLegacyFreeTrialIfEligible(business: Business): Result<Unit> = runCatching {
        val uid = requireUserId()
        if (business.ownerUid != uid ||
            business.subscriptionStatus != SubscriptionStatus.AWAITING_SUBSCRIPTION.name ||
            business.subscriptionActivatedMillis > 0L
        ) {
            return@runCatching
        }
        val now = System.currentTimeMillis()
        val trialEnd = now + BillingCalculator.FREE_TRIAL_MILLIS
        val trialEnt = BillingCalculator.freeTrialEntitlement()

        val subRef = db.collection("business_subscriptions").document(business.id)
        val existingSubSnap = subRef.get().await()
        if (existingSubSnap.exists() &&
            existingSubSnap.getString("status") != SubscriptionStatus.AWAITING_SUBSCRIPTION.name
        ) {
            return@runCatching
        }
        val quotaSnap = db.collection("usage_quotas").document("${business.id}_current").get().await()
        val storageSnap = db.collection("storage_usage").document(business.id).get().await()

        val batch = db.batch()
        batch.update(
            db.collection("businesses").document(business.id),
            mapOf(
                "subscriptionStatus" to SubscriptionStatus.TRIALING.name,
                "subscriptionPlanCode" to "FREE_TRIAL",
                "subscriptionPlanId" to BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID,
                "subscriptionActivatedMillis" to now,
                "subscriptionExpiryMillis" to trialEnd,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            subRef,
            mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "planId" to BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID,
                "planCode" to "FREE_TRIAL",
                "planNameSnapshot" to "90-Day Free Trial",
                "agreedPriceMinor" to 0L,
                "agreedCurrency" to "NGN",
                "agreedBillingFrequency" to BillingFrequency.CUSTOM.name,
                "agreedIntervalDays" to BillingCalculator.FREE_TRIAL_DAYS,
                "agreedEntitlementsSnapshot" to trialEnt.toSnapshotMap(),
                "status" to SubscriptionStatus.TRIALING.name,
                "cycleStartMillis" to now,
                "cycleEndMillis" to trialEnd,
                "gracePeriodEndMillis" to trialEnd,
                "trialEndMillis" to trialEnd,
                "autoRenew" to false,
                "cancelAtPeriodEnd" to false,
                "cancelledAtMillis" to 0L,
                "pendingUpgradePlanId" to "",
                "creditBalanceMinor" to 0L,
                "lastPaymentId" to "",
                "lastInvoiceId" to "",
                "createdAt" to (existingSubSnap.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        if (!quotaSnap.exists()) {
            batch.set(
                db.collection("usage_quotas").document("${business.id}_current"),
                mapOf(
                    "businessId" to business.id,
                    "ownerUid" to business.ownerUid,
                    "memberUids" to business.memberUids,
                    "cycleKey" to "trial_$trialEnd",
                    "receiptsAllocated" to trialEnt.maxReceiptsPerCycle,
                    "receiptsUsed" to 0,
                    "rolloverReceipts" to 0,
                    "overageReceiptsUsed" to 0,
                    "ocrAllocated" to trialEnt.maxOcrPerCycle,
                    "ocrUsed" to 0,
                    "warningsSentPct" to emptyList<Int>(),
                    "cycleStartMillis" to now,
                    "cycleEndMillis" to trialEnd,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        if (!storageSnap.exists()) {
            batch.set(
                db.collection("storage_usage").document(business.id),
                mapOf(
                    "businessId" to business.id,
                    "ownerUid" to business.ownerUid,
                    "memberUids" to business.memberUids,
                    "usedBytes" to business.storageUsedBytes.coerceAtLeast(0L),
                    "allocatedBytes" to trialEnt.storageLimitBytes,
                    "bankReceiptsBytes" to 0L,
                    "generatedReceiptsBytes" to 0L,
                    "brandingBytes" to business.logoBase64.length.toLong(),
                    "limitBehavior" to trialEnt.storageOverLimitAction,
                    "warningThresholdsSent" to emptyList<Int>(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "businesses/${business.id}") }

    suspend fun updateBusinessSettings(
        business: Business,
        actorRole: StaffRole = StaffRole.OWNER
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(business.id.isNotBlank()) { "Business ID required" }
        val docRef = db.collection("businesses").document(business.id)
        val existing = docRef.get().await()
        require(existing.exists()) { "Business workspace not found." }
        val createdAt = existing.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()
        val previousPublicVerify = existing.getBoolean("publicVerificationEnabled") ?: true
        val serverOwnerUid = existing.getString("ownerUid").orEmpty().ifBlank { business.ownerUid }
        @Suppress("UNCHECKED_CAST")
        val serverMemberUids = (existing.get("memberUids") as? List<*>)
            ?.mapNotNull { it as? String }
            ?.takeIf { it.isNotEmpty() }
            ?: business.memberUids
        val serverSubStatus = existing.getString("subscriptionStatus").orEmpty().ifBlank { business.subscriptionStatus }
        val serverPlanCode = existing.getString("subscriptionPlanCode").orEmpty().ifBlank { business.subscriptionPlanCode }
        val serverPlanId = existing.getString("subscriptionPlanId").orEmpty().ifBlank { business.subscriptionPlanId }
        val serverSubExpiry = existing.getLong("subscriptionExpiryMillis") ?: business.subscriptionExpiryMillis
        val serverSubActivated = existing.getLong("subscriptionActivatedMillis") ?: business.subscriptionActivatedMillis
        val serverAccountStatus = existing.getString("accountStatus").orEmpty().ifBlank { business.accountStatus }
        val serverStorageUsed = existing.getLong("storageUsedBytes") ?: business.storageUsedBytes
        val serverStorageLimit = existing.getLong("storageLimitBytes") ?: business.storageLimitBytes
        val serverReceiptsCount = existing.getLong("receiptsCount") ?: business.receiptsCount
        val serverUploadedDocsCount = existing.getLong("uploadedDocsCount") ?: business.uploadedDocsCount

        val payload = mapOf(
            "ownerUid" to serverOwnerUid,
            "memberUids" to serverMemberUids,
            "ownerName" to business.ownerName,
            "ownerEmail" to business.ownerEmail,
            "ownerPhone" to business.ownerPhone,
            "registeredName" to business.registeredName,
            "tradingName" to business.tradingName,
            "category" to business.category,
            "description" to business.description,
            "structure" to business.structure,
            "address" to business.address,
            "phone" to business.phone,
            "email" to business.email,
            "website" to business.website,
            "cacNumber" to business.cacNumber,
            "socialMedia" to business.socialMedia,
            "authorisedRepName" to business.authorisedRepName,
            "logoBase64" to business.logoBase64,
            "signatureBase64" to business.signatureBase64,
            "stampText" to business.stampText,
            "primaryColorHex" to business.primaryColorHex,
            "secondaryColorHex" to business.secondaryColorHex,
            "accentColorHex" to business.accentColorHex,
            "backgroundColorHex" to business.backgroundColorHex,
            "textColorHex" to business.textColorHex,
            "defaultCurrency" to business.defaultCurrency,
            "receiptPrefix" to business.receiptPrefix.trim().uppercase(),
            "numberingFormat" to business.numberingFormat,
            "defaultAcknowledgement" to business.defaultAcknowledgement,
            "businessSlogan" to business.businessSlogan,
            "defaultTemplate" to business.defaultTemplate,
            "publicVerificationEnabled" to business.publicVerificationEnabled,
            "maskAccountNumbers" to business.maskAccountNumbers,
            "showSignature" to business.showSignature,
            "showQrCode" to business.showQrCode,
            "subscriptionStatus" to serverSubStatus,
            "subscriptionPlanCode" to serverPlanCode,
            "subscriptionPlanId" to serverPlanId,
            "subscriptionExpiryMillis" to serverSubExpiry,
            "subscriptionActivatedMillis" to serverSubActivated,
            "accountStatus" to serverAccountStatus,
            "storageUsedBytes" to serverStorageUsed,
            "storageLimitBytes" to serverStorageLimit,
            "receiptsCount" to serverReceiptsCount,
            "uploadedDocsCount" to serverUploadedDocsCount,
            "createdAt" to createdAt,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(payload).await()

        if (previousPublicVerify != business.publicVerificationEnabled) {
            val pubDocs = db.collection("public_verifications")
                .whereArrayContains("memberUids", uid)
                .get()
                .await()
                .documents
                .filter { it.getString("businessId") == business.id }
            pubDocs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { snap ->
                    batch.update(
                        snap.reference,
                        mapOf(
                            "publicVerificationEnabled" to business.publicVerificationEnabled,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
            }
        }

        logAuditAction(
            business = business,
            actorUid = uid,
            actorRole = actorRole.name,
            action = "BUSINESS_SETTINGS_UPDATED",
            targetType = "BUSINESS",
            targetId = business.id,
            details = "Updated branding, profile, or receipt preferences"
        )
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "businesses/${business.id}") }

    // --- CUSTOMERS (CRM) ---

    fun observeCustomers(businessId: String, currentUserId: String): Flow<List<Customer>> =
        db.collection("businesses").document(businessId).collection("customers")
            .whereArrayContains("memberUids", currentUserId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(Customer::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.fullName.lowercase() }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses/$businessId/customers", currentUserId)
                throw error
            }

    suspend fun upsertCustomer(
        business: Business,
        customer: Customer,
        maxCustomers: Int = -1
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val col = db.collection("businesses").document(business.id).collection("customers")
        val existingList = col.whereArrayContains("memberUids", uid).get().await()
            .toObjects(Customer::class.java)

        // Prevent duplicate customer creation via phone/email/name match when creating new
        val matched = if (customer.id.isNotBlank()) {
            existingList.firstOrNull { it.id == customer.id }
        } else {
            existingList.firstOrNull {
                (customer.phone.isNotBlank() && it.phone.equals(customer.phone.trim(), ignoreCase = true)) ||
                    (customer.email.isNotBlank() && it.email.equals(customer.email.trim(), ignoreCase = true)) ||
                    it.fullName.equals(customer.fullName.trim(), ignoreCase = true)
            }
        }

        if (matched == null && maxCustomers >= 0 && existingList.size >= maxCustomers) {
            throw IllegalStateException(
                "Customer record limit reached ($maxCustomers). Existing customer records remain safe; upgrade your subscription plan to add new customers."
            )
        }

        val docId = matched?.id?.ifBlank { null } ?: customer.id.ifBlank {
            "cust_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        }
        val docRef = col.document(docId)
        val createdAt = matched?.createdAt ?: FieldValue.serverTimestamp()

        val preserveMissingOnAutoMatch = matched != null && customer.id.isBlank()
        val resolvedPhone = if (preserveMissingOnAutoMatch) customer.phone.trim().ifBlank { matched?.phone.orEmpty() } else customer.phone.trim()
        val resolvedEmail = if (preserveMissingOnAutoMatch) customer.email.trim().ifBlank { matched?.email.orEmpty() } else customer.email.trim()
        val resolvedAddress = if (preserveMissingOnAutoMatch) customer.address.trim().ifBlank { matched?.address.orEmpty() } else customer.address.trim()

        val payload = mapOf(
            "businessId" to business.id,
            "ownerUid" to business.ownerUid,
            "memberUids" to business.memberUids,
            "fullName" to customer.fullName.trim(),
            "phone" to resolvedPhone,
            "email" to resolvedEmail,
            "address" to resolvedAddress,
            "customerCode" to customer.customerCode.ifBlank { matched?.customerCode.orEmpty().ifBlank { "CUS-${docId.takeLast(6).uppercase()}" } },
            "totalVerifiedAmount" to (matched?.totalVerifiedAmount ?: customer.totalVerifiedAmount),
            "receiptCount" to (matched?.receiptCount ?: customer.receiptCount),
            "createdAt" to createdAt,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(payload).await()
        docId
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "businesses/${business.id}/customers") }

    // --- SERVICE / PRODUCT CATALOG ---

    fun observeCatalog(businessId: String, currentUserId: String): Flow<List<CatalogItem>> =
        db.collection("businesses").document(businessId).collection("products")
            .whereArrayContains("memberUids", currentUserId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(CatalogItem::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.name.lowercase() }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses/$businessId/products", currentUserId)
                throw error
            }

    suspend fun saveCatalogItem(business: Business, item: CatalogItem): Result<String> = runCatching {
        requireUserId()
        val col = db.collection("businesses").document(business.id).collection("products")
        val docId = item.id.ifBlank { "prd_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val existing = if (item.id.isNotBlank()) col.document(docId).get().await() else null
        val payload = mapOf(
            "businessId" to business.id,
            "ownerUid" to business.ownerUid,
            "memberUids" to business.memberUids,
            "name" to item.name.trim(),
            "description" to item.description.trim(),
            "unitPrice" to item.unitPrice,
            "category" to item.category.trim(),
            "active" to item.active,
            "createdAt" to (existing?.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        col.document(docId).set(payload).await()
        docId
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "businesses/${business.id}/products") }

    // --- TRANSACTIONS & RECEIPT GENERATION (ATOMIC NUMBERING + PUBLIC QR + AUDIT) ---

    fun observeTransactions(businessId: String, currentUserId: String): Flow<List<BankTransactionRecord>> =
        db.collection("businesses").document(businessId).collection("transactions")
            .whereArrayContains("memberUids", currentUserId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(BankTransactionRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses/$businessId/transactions", currentUserId)
                throw error
            }

    fun observeReceipts(businessId: String, currentUserId: String): Flow<List<ReceiptRecord>> =
        db.collection("businesses").document(businessId).collection("receipts")
            .whereArrayContains("memberUids", currentUserId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(ReceiptRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses/$businessId/receipts", currentUserId)
                throw error
            }

    suspend fun issueReceiptFromTransaction(
        business: Business,
        transaction: BankTransactionRecord,
        customer: Customer,
        paymentPurpose: String,
        invoiceNumber: String,
        orderNumber: String,
        notes: String,
        amountPaid: Double,
        discountAmount: Double,
        amountInWords: String,
        templateType: ReceiptTemplateType,
        actorRole: StaffRole,
        amendedFromReceiptId: String = "",
        effectiveEntitlements: EffectiveEntitlements? = null,
        idempotencyKey: String = ""
    ): Result<ReceiptRecord> = runCatching {
        val uid = requireUserId()
        val actorName = requireUserName()
        val now = System.currentTimeMillis()
        require(business.isSubscriptionActive(now, effectiveEntitlements?.gracePeriodEndMillis ?: 0L)) {
            "Active subscription required to issue new official receipts."
        }
        require(transaction.humanReviewed) {
            "Human review of extracted transaction details is required before issuing a receipt."
        }
        if (effectiveEntitlements != null && templateType !in effectiveEntitlements.allowedTemplates) {
            throw IllegalStateException(
                "Template '${templateType.label}' is not included in your current ${effectiveEntitlements.planName} plan. Upgrade your plan or choose an included template."
            )
        }

        val cleanIdemKey = idempotencyKey.trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(64)
            .ifBlank { "idem_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val usageDocId = "${business.id}_rct_$cleanIdemKey"
        val bizRef = db.collection("businesses").document(business.id)
        val usageRecordRef = db.collection("usage_records").document(usageDocId)

        // Fast idempotency check before customer upsert to prevent duplicate customer/quota work on retry
        val priorUsage = usageRecordRef.get().await()
        if (priorUsage.exists()) {
            val priorReceiptId = priorUsage.getString("resourceId").orEmpty()
            if (priorReceiptId.isNotBlank()) {
                val existingRct = bizRef.collection("receipts").document(priorReceiptId).get().await()
                if (existingRct.exists()) {
                    return@runCatching existingRct.toObject(
                        ReceiptRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: throw IllegalStateException("Unable to decode existing receipt for idempotent retry.")
                }
            }
        }

        // 1. Upsert/match customer first (enforcing customer cap only for new customers)
        val customerId = upsertCustomer(
            business = business,
            customer = customer,
            maxCustomers = effectiveEntitlements?.maxCustomers ?: -1
        ).getOrThrow()

        // 2. Atomic Firestore transaction: idempotency check + quota & storage enforcement + counter + receipt + storage + warning alerts
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Africa/Lagos")).apply { timeInMillis = now }
        val year = cal.get(Calendar.YEAR)
        val counterRef = bizRef.collection("counters").document("receipt_$year")
        val quotaRef = db.collection("usage_quotas").document("${business.id}_current")
        val storageRef = db.collection("storage_usage").document(business.id)

        val receiptId = "rct_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val txId = transaction.id.ifBlank { "tx_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val verificationCode = "RNW-${UUID.randomUUID().toString().replace("-", "").take(12).uppercase()}"

        val txRef = bizRef.collection("transactions").document(txId)
        val rctRef = bizRef.collection("receipts").document(receiptId)
        val custRef = bizRef.collection("customers").document(customerId)
        val pubRef = db.collection("public_verifications").document(verificationCode)
        val auditRef = bizRef.collection("audit_logs").document("log_${UUID.randomUUID().toString().take(12)}")
        val notifRef = bizRef.collection("notifications").document("ntf_${UUID.randomUUID().toString().take(12)}")

        val issuedReceipt = db.runTransaction { tx ->
            // --- ALL READS FIRST ---
            val idempotentSnap = tx.get(usageRecordRef)
            val quotaSnap = tx.get(quotaRef)
            val storageSnap = tx.get(storageRef)
            val counterSnap = tx.get(counterRef)

            if (idempotentSnap.exists()) {
                val existingReceiptId = idempotentSnap.getString("resourceId").orEmpty()
                if (existingReceiptId.isNotBlank()) {
                    val existingRctSnap = tx.get(bizRef.collection("receipts").document(existingReceiptId))
                    val existingObj = existingRctSnap.toObject(ReceiptRecord::class.java)
                    if (existingObj != null) return@runTransaction existingObj
                }
            }

            val currentSeq = if (counterSnap.exists()) counterSnap.getLong("lastSequence") ?: 0L else 0L
            val nextSeq = currentSeq + 1L
            val prefix = business.receiptPrefix.trim().uppercase().ifBlank { "RCN" }
            val receiptNumber = formatReceiptNumber(business.numberingFormat, prefix, year, nextSeq)
            val receiptCurrency = transaction.currency.trim().ifBlank {
                business.defaultCurrency.trim().ifBlank { "NGN" }
            }

            // Receipt quota check (strictly atomic)
            val cycleKey = if (quotaSnap.exists()) {
                quotaSnap.getString("cycleKey").orEmpty().ifBlank { "cycle_${business.subscriptionExpiryMillis}" }
            } else {
                "cycle_${(effectiveEntitlements?.cycleEndMillis ?: business.subscriptionExpiryMillis).takeIf { it > 0L } ?: now}"
            }
            val currentAllocated = if (quotaSnap.exists()) {
                (quotaSnap.getLong("receiptsAllocated") ?: 300L).toInt()
            } else {
                effectiveEntitlements?.maxReceiptsPerCycle ?: 300
            }
            val currentRollover = if (quotaSnap.exists()) (quotaSnap.getLong("rolloverReceipts") ?: 0L).toInt() else 0
            val currentReceiptsUsed = if (quotaSnap.exists()) (quotaSnap.getLong("receiptsUsed") ?: 0L).toInt() else 0
            val currentOcrAllocated = if (quotaSnap.exists()) {
                (quotaSnap.getLong("ocrAllocated") ?: 300L).toInt()
            } else {
                effectiveEntitlements?.maxOcrPerCycle ?: 300
            }
            val currentOcrUsed = if (quotaSnap.exists()) (quotaSnap.getLong("ocrUsed") ?: 0L).toInt() else 0
            val prevQuotaWarnings = if (quotaSnap.exists()) {
                (quotaSnap.get("warningsSentPct") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
            } else emptyList()

            val totalReceiptAllowance = if (currentAllocated < 0) Int.MAX_VALUE else currentAllocated + currentRollover
            if (currentAllocated >= 0 && currentReceiptsUsed >= totalReceiptAllowance) {
                throw IllegalStateException(
                    "Monthly receipt limit reached ($currentReceiptsUsed/$totalReceiptAllowance). Existing receipts remain safe and downloadable; upgrade your plan to issue new receipts."
                )
            }

            // Storage quota check (non-destructive)
            val addedBankBytes = transaction.documentSizeBytes.coerceAtLeast(0L)
            val addedGeneratedBytes = 4096L
            val addedTotalBytes = addedBankBytes + addedGeneratedBytes
            val currentStorageAllocated = if (storageSnap.exists()) {
                storageSnap.getLong("allocatedBytes") ?: business.storageLimitBytes
            } else {
                effectiveEntitlements?.storageLimitBytes ?: business.storageLimitBytes
            }
            val currentStorageUsed = if (storageSnap.exists()) {
                storageSnap.getLong("usedBytes") ?: business.storageUsedBytes
            } else {
                business.storageUsedBytes
            }
            val currentBankBytes = if (storageSnap.exists()) storageSnap.getLong("bankReceiptsBytes") ?: 0L else 0L
            val currentGenBytes = if (storageSnap.exists()) storageSnap.getLong("generatedReceiptsBytes") ?: 0L else 0L
            val currentBrandingBytes = if (storageSnap.exists()) storageSnap.getLong("brandingBytes") ?: 0L else business.logoBase64.length.toLong()
            val currentOtherBytes = if (storageSnap.exists()) storageSnap.getLong("otherDocsBytes") ?: 0L else 0L
            val limitBehavior = if (storageSnap.exists()) {
                storageSnap.getString("limitBehavior") ?: StorageOverLimitAction.BLOCK_NEW_UPLOADS.name
            } else {
                effectiveEntitlements?.storageOverLimitAction?.name ?: StorageOverLimitAction.BLOCK_NEW_UPLOADS.name
            }
            val prevStorageWarnings = if (storageSnap.exists()) {
                (storageSnap.get("warningThresholdsSent") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
            } else emptyList()

            if (currentStorageAllocated >= 0L &&
                (currentStorageUsed + addedTotalBytes) > currentStorageAllocated &&
                limitBehavior == StorageOverLimitAction.BLOCK_NEW_UPLOADS.name
            ) {
                throw IllegalStateException(
                    "Cloud storage limit reached (${BillingCalculator.formatBytes(currentStorageUsed)} / ${BillingCalculator.formatBytes(currentStorageAllocated)}). No existing receipts or files were deleted; upgrade your plan to upload new documents."
                )
            }

            val newReceiptsUsed = currentReceiptsUsed + 1
            val newStorageUsed = currentStorageUsed + addedTotalBytes
            val thresholds = effectiveEntitlements?.warningThresholds ?: listOf(50, 75, 90, 100)
            val newReceiptThresholds = if (currentAllocated > 0) {
                BillingCalculator.evaluateNewlyTriggeredThresholds(
                    used = newReceiptsUsed.toLong(),
                    allocated = (currentAllocated + currentRollover).toLong(),
                    alreadySent = prevQuotaWarnings,
                    configuredThresholds = thresholds
                )
            } else emptyList()
            val newStorageThresholds = if (currentStorageAllocated > 0L) {
                BillingCalculator.evaluateNewlyTriggeredThresholds(
                    used = newStorageUsed,
                    allocated = currentStorageAllocated,
                    alreadySent = prevStorageWarnings,
                    configuredThresholds = thresholds
                )
            } else emptyList()

            // --- ALL WRITES ---
            tx.set(
                counterRef,
                mapOf(
                    "ownerUid" to business.ownerUid,
                    "memberUids" to business.memberUids,
                    "year" to year,
                    "lastSequence" to nextSeq,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            if (quotaSnap.exists()) {
                tx.update(
                    quotaRef,
                    mapOf(
                        "receiptsUsed" to newReceiptsUsed,
                        "warningsSentPct" to (prevQuotaWarnings + newReceiptThresholds).distinct(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
                tx.set(
                    quotaRef,
                    mapOf(
                        "businessId" to business.id,
                        "ownerUid" to business.ownerUid,
                        "memberUids" to business.memberUids,
                        "cycleKey" to cycleKey,
                        "cycleStartMillis" to business.subscriptionActivatedMillis,
                        "cycleEndMillis" to (effectiveEntitlements?.cycleEndMillis ?: business.subscriptionExpiryMillis),
                        "receiptsAllocated" to currentAllocated,
                        "receiptsUsed" to newReceiptsUsed,
                        "rolloverReceipts" to 0,
                        "overageReceiptsUsed" to 0,
                        "ocrAllocated" to currentOcrAllocated,
                        "ocrUsed" to currentOcrUsed,
                        "warningsSentPct" to newReceiptThresholds,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            if (storageSnap.exists()) {
                tx.update(
                    storageRef,
                    mapOf(
                        "usedBytes" to newStorageUsed,
                        "bankReceiptsBytes" to (currentBankBytes + addedBankBytes),
                        "generatedReceiptsBytes" to (currentGenBytes + addedGeneratedBytes),
                        "warningThresholdsSent" to (prevStorageWarnings + newStorageThresholds).distinct(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
                tx.set(
                    storageRef,
                    mapOf(
                        "businessId" to business.id,
                        "ownerUid" to business.ownerUid,
                        "memberUids" to business.memberUids,
                        "allocatedBytes" to currentStorageAllocated,
                        "usedBytes" to newStorageUsed,
                        "bankReceiptsBytes" to (currentBankBytes + addedBankBytes),
                        "generatedReceiptsBytes" to (currentGenBytes + addedGeneratedBytes),
                        "brandingBytes" to currentBrandingBytes,
                        "otherDocsBytes" to currentOtherBytes,
                        "limitBehavior" to limitBehavior,
                        "warningThresholdsSent" to newStorageThresholds,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            tx.set(
                usageRecordRef,
                mapOf(
                    "businessId" to business.id,
                    "ownerUid" to business.ownerUid,
                    "memberUids" to business.memberUids,
                    "usageType" to UsageType.RECEIPT_GENERATED.name,
                    "quantity" to 1,
                    "bytesDelta" to addedTotalBytes,
                    "idempotencyKey" to cleanIdemKey,
                    "resourceId" to receiptId,
                    "cycleKey" to cycleKey,
                    "actorUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            // Save bank transaction record
            val txPayload = mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "uploadedByUid" to uid,
                "uploadedByName" to actorName,
                "documentType" to transaction.documentType,
                "documentName" to transaction.documentName,
                "documentSizeBytes" to transaction.documentSizeBytes,
                "thumbnailBase64" to transaction.thumbnailBase64,
                "amount" to transaction.amount,
                "currency" to receiptCurrency,
                "transactionDate" to transaction.transactionDate,
                "transactionTime" to transaction.transactionTime,
                "transactionReference" to transaction.transactionReference,
                "sessionId" to transaction.sessionId,
                "transactionId" to transaction.transactionId,
                "bankReceiptNumber" to transaction.bankReceiptNumber,
                "paymentNarration" to transaction.paymentNarration,
                "transferChannel" to transaction.transferChannel,
                "bankTransactionStatus" to transaction.bankTransactionStatus,
                "transferFee" to transaction.transferFee,
                "senderName" to transaction.senderName,
                "senderAccountName" to transaction.senderAccountName,
                "senderBank" to transaction.senderBank,
                "senderAccountNumber" to transaction.senderAccountNumber,
                "beneficiaryName" to transaction.beneficiaryName,
                "beneficiaryAccountName" to transaction.beneficiaryAccountName,
                "beneficiaryBank" to transaction.beneficiaryBank,
                "beneficiaryAccountNumber" to transaction.beneficiaryAccountNumber,
                "ocrConfidenceScore" to transaction.ocrConfidenceScore,
                "humanReviewed" to true,
                "verificationStatus" to VerificationStatus.PENDING_VERIFICATION.name,
                "verificationComment" to transaction.verificationComment,
                "verifiedByUid" to "",
                "verifiedByName" to "",
                "receiptId" to receiptId,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            tx.set(txRef, txPayload)

            // Save immutable receipt record
            val rctPayload = mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "receiptNumber" to receiptNumber,
                "verificationCode" to verificationCode,
                "transactionRecordId" to txId,
                "customerId" to customerId,
                "customerName" to customer.fullName.trim(),
                "customerPhone" to customer.phone.trim(),
                "customerEmail" to customer.email.trim(),
                "customerAddress" to customer.address.trim(),
                "paymentPurpose" to paymentPurpose.trim(),
                "invoiceNumber" to invoiceNumber.trim(),
                "orderNumber" to orderNumber.trim(),
                "notes" to notes.trim(),
                "amountPaid" to amountPaid,
                "discountAmount" to discountAmount,
                "currency" to receiptCurrency,
                "amountInWords" to amountInWords,
                "templateType" to templateType.name,
                "verificationStatus" to VerificationStatus.PENDING_VERIFICATION.name,
                "issuedByUid" to uid,
                "issuedByName" to actorName,
                "issuedByRole" to actorRole.name,
                "amendedFromReceiptId" to amendedFromReceiptId,
                "cancellationReason" to "",
                "businessNameSnapshot" to business.tradingName.ifBlank { business.registeredName },
                "businessPrimaryHexSnapshot" to business.primaryColorHex,
                "businessAccentHexSnapshot" to business.accentColorHex,
                "senderBankSnapshot" to transaction.senderBank,
                "senderAccountNameSnapshot" to transaction.senderAccountName.ifBlank { transaction.senderName },
                "beneficiaryBankSnapshot" to transaction.beneficiaryBank,
                "transactionRefSnapshot" to transaction.transactionReference,
                "sessionIdSnapshot" to transaction.sessionId,
                "transactionDateSnapshot" to transaction.transactionDate,
                "transactionTimeSnapshot" to transaction.transactionTime,
                "transferChannelSnapshot" to transaction.transferChannel,
                "narrationSnapshot" to transaction.paymentNarration,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            tx.set(rctRef, rctPayload)

            // Public QR verification summary (sanitized - NO account numbers, NO customer PII)
            val maskedCustomer = customer.fullName.trim().split(" ").let { parts ->
                if (parts.size >= 2) "${parts.first()} ${parts.last().take(1)}." else parts.firstOrNull().orEmpty()
            }
            val pubPayload = mapOf(
                "verificationCode" to verificationCode,
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "businessName" to business.tradingName.ifBlank { business.registeredName },
                "receiptNumber" to receiptNumber,
                "receiptDateText" to transaction.transactionDate.ifBlank { "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}" },
                "customerDisplayName" to maskedCustomer,
                "amountFormatted" to "$receiptCurrency ${"%,.2f".format(amountPaid)}",
                "purposeSummary" to paymentPurpose.trim().take(200),
                "verificationStatus" to VerificationStatus.PENDING_VERIFICATION.name,
                "publicVerificationEnabled" to business.publicVerificationEnabled,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            tx.set(pubRef, pubPayload)

            tx.update(
                custRef,
                mapOf(
                    "receiptCount" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            tx.update(
                bizRef,
                mapOf(
                    "receiptsCount" to FieldValue.increment(1L),
                    "uploadedDocsCount" to FieldValue.increment(1L),
                    "storageUsedBytes" to FieldValue.increment(addedTotalBytes),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            tx.set(
                auditRef,
                mapOf(
                    "businessId" to business.id,
                    "ownerUid" to business.ownerUid,
                    "memberUids" to business.memberUids,
                    "actorUid" to uid,
                    "actorName" to actorName,
                    "actorRole" to actorRole.name,
                    "action" to if (amendedFromReceiptId.isBlank()) "RECEIPT_ISSUED" else "RECEIPT_AMENDED",
                    "targetType" to "RECEIPT",
                    "targetId" to receiptId,
                    "details" to "Issued $receiptNumber for ${customer.fullName} ($receiptCurrency ${"%,.2f".format(amountPaid)})",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            tx.set(
                notifRef,
                mapOf(
                    "businessId" to business.id,
                    "ownerUid" to business.ownerUid,
                    "memberUids" to business.memberUids,
                    "title" to "Receipt $receiptNumber Issued",
                    "body" to "Generated for ${customer.fullName} (Pending payment verification).",
                    "category" to "RECEIPT",
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            if (newReceiptThresholds.isNotEmpty() || newStorageThresholds.isNotEmpty()) {
                val warnRef = bizRef.collection("notifications").document("ntf_${UUID.randomUUID().toString().take(12)}")
                val parts = mutableListOf<String>()
                if (newReceiptThresholds.isNotEmpty()) {
                    parts += "Receipt quota reached ${newReceiptThresholds.max()}% ($newReceiptsUsed/$totalReceiptAllowance)"
                }
                if (newStorageThresholds.isNotEmpty()) {
                    parts += "Cloud storage reached ${newStorageThresholds.max()}% (${BillingCalculator.formatBytes(newStorageUsed)} / ${BillingCalculator.formatBytes(currentStorageAllocated)})"
                }
                tx.set(
                    warnRef,
                    mapOf(
                        "businessId" to business.id,
                        "ownerUid" to business.ownerUid,
                        "memberUids" to business.memberUids,
                        "title" to "Usage Threshold Alert",
                        "body" to "${parts.joinToString(". ")}. Upgrade anytime without losing existing records.",
                        "category" to "QUOTA_WARNING",
                        "read" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            ReceiptRecord(
                id = receiptId,
                businessId = business.id,
                ownerUid = business.ownerUid,
                memberUids = business.memberUids,
                receiptNumber = receiptNumber,
                verificationCode = verificationCode,
                transactionRecordId = txId,
                customerId = customerId,
                customerName = customer.fullName.trim(),
                customerPhone = customer.phone.trim(),
                customerEmail = customer.email.trim(),
                customerAddress = customer.address.trim(),
                paymentPurpose = paymentPurpose.trim(),
                invoiceNumber = invoiceNumber.trim(),
                orderNumber = orderNumber.trim(),
                notes = notes.trim(),
                amountPaid = amountPaid,
                discountAmount = discountAmount,
                currency = receiptCurrency,
                amountInWords = amountInWords,
                templateType = templateType.name,
                verificationStatus = VerificationStatus.PENDING_VERIFICATION.name,
                issuedByUid = uid,
                issuedByName = actorName,
                issuedByRole = actorRole.name,
                amendedFromReceiptId = amendedFromReceiptId,
                businessNameSnapshot = business.tradingName.ifBlank { business.registeredName },
                businessPrimaryHexSnapshot = business.primaryColorHex,
                businessAccentHexSnapshot = business.accentColorHex,
                senderBankSnapshot = transaction.senderBank,
                senderAccountNameSnapshot = transaction.senderAccountName.ifBlank { transaction.senderName },
                beneficiaryBankSnapshot = transaction.beneficiaryBank,
                transactionRefSnapshot = transaction.transactionReference,
                sessionIdSnapshot = transaction.sessionId,
                transactionDateSnapshot = transaction.transactionDate,
                transactionTimeSnapshot = transaction.transactionTime,
                transferChannelSnapshot = transaction.transferChannel,
                narrationSnapshot = transaction.paymentNarration
            )
        }.await()

        issuedReceipt
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "businesses/${business.id}/receipts") }

    suspend fun updateReceiptVerificationStatus(
        business: Business,
        receipt: ReceiptRecord,
        newStatus: VerificationStatus,
        comment: String,
        actorRole: StaffRole
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val actorName = requireUserName()
        require(actorRole.canVerifyTransactions()) {
            "Current role (${actorRole.displayName}) is not authorised to verify or reject transactions."
        }

        val bizRef = db.collection("businesses").document(business.id)
        val rctRef = bizRef.collection("receipts").document(receipt.id)
        val pubRef = db.collection("public_verifications").document(receipt.verificationCode)
        val auditRef = bizRef.collection("audit_logs").document("log_${UUID.randomUUID().toString().take(12)}")

        val batch = db.batch()
        batch.update(
            rctRef,
            mapOf(
                "verificationStatus" to newStatus.name,
                "cancellationReason" to comment.trim(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        if (receipt.transactionRecordId.isNotBlank()) {
            val txRef = bizRef.collection("transactions").document(receipt.transactionRecordId)
            batch.update(
                txRef,
                mapOf(
                    "verificationStatus" to newStatus.name,
                    "verificationComment" to comment.trim(),
                    "verifiedByUid" to uid,
                    "verifiedByName" to actorName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        batch.update(
            pubRef,
            mapOf(
                "verificationStatus" to newStatus.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        // Update customer verified total only when transitioning from non-verified -> VERIFIED, or decrement if reversed
        if (receipt.customerId.isNotBlank()) {
            val custRef = bizRef.collection("customers").document(receipt.customerId)
            if (receipt.verificationStatus != VerificationStatus.VERIFIED.name && newStatus == VerificationStatus.VERIFIED) {
                batch.update(
                    custRef,
                    mapOf(
                        "totalVerifiedAmount" to FieldValue.increment(receipt.amountPaid),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else if (receipt.verificationStatus == VerificationStatus.VERIFIED.name &&
                newStatus != VerificationStatus.VERIFIED
            ) {
                batch.update(
                    custRef,
                    mapOf(
                        "totalVerifiedAmount" to FieldValue.increment(-receipt.amountPaid),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }

        batch.set(
            auditRef,
            mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "actorUid" to uid,
                "actorName" to actorName,
                "actorRole" to actorRole.name,
                "action" to "VERIFICATION_${newStatus.name}",
                "targetType" to "RECEIPT",
                "targetId" to receipt.id,
                "details" to "${receipt.receiptNumber} marked ${newStatus.label}. Note: ${comment.ifBlank { "None" }}",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "businesses/${business.id}/receipts/${receipt.id}") }

    // --- PUBLIC QR VERIFICATION LOOKUP ---

    suspend fun lookupPublicVerification(verificationCode: String): Result<PublicVerification?> = runCatching {
        val cleanCode = verificationCode.trim()
        require(PUBLIC_VERIFICATION_CODE_REGEX.matches(cleanCode)) {
            "Enter a valid verification code (letters, numbers, '-' or '_')."
        }
        val snap = db.collection("public_verifications").document(cleanCode).get().await()
        if (snap.exists()) {
            snap.toObject(PublicVerification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } else null
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.GET, "public_verifications/$verificationCode") }

    // --- STAFF & RBAC ---

    fun observeStaff(businessId: String, currentUserId: String): Flow<List<StaffMember>> =
        db.collection("businesses").document(businessId).collection("staff")
            .whereArrayContains("memberUids", currentUserId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(StaffMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses/$businessId/staff", currentUserId)
                throw error
            }

    fun observePendingInvitesForEmail(email: String): Flow<List<StaffInvite>> =
        db.collection("staff_invites")
            .whereEqualTo("invitedEmail", email.trim().lowercase())
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(StaffInvite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter { it.status == "PENDING" }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "staff_invites", auth.currentUser?.uid)
                throw error
            }

    suspend fun inviteStaff(
        business: Business,
        invitedEmail: String,
        invitedName: String,
        role: StaffRole,
        maxStaffAccounts: Int = -1
    ): Result<String> = runCatching {
        val uid = requireUserId()
        require(role != StaffRole.OWNER) { "Cannot invite another OWNER." }
        if (maxStaffAccounts >= 0) {
            val activeStaffCount = db.collection("businesses").document(business.id)
                .collection("staff")
                .whereArrayContains("memberUids", uid)
                .get()
                .await()
                .documents
                .count { it.getBoolean("active") != false }
            if (activeStaffCount >= maxStaffAccounts) {
                throw IllegalStateException(
                    "Staff account limit reached ($activeStaffCount/$maxStaffAccounts). Upgrade your subscription plan to invite more team members."
                )
            }
        }
        val inviteId = "inv_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val payload = mapOf(
            "businessId" to business.id,
            "businessName" to business.tradingName.ifBlank { business.registeredName },
            "ownerUid" to business.ownerUid,
            "invitedEmail" to invitedEmail.trim().lowercase(),
            "invitedName" to invitedName.trim(),
            "role" to role.name,
            "status" to "PENDING",
            "acceptedByUid" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        db.collection("staff_invites").document(inviteId).set(payload).await()
        logAuditAction(
            business = business,
            actorUid = uid,
            actorRole = StaffRole.OWNER.name,
            action = "STAFF_INVITED",
            targetType = "STAFF_INVITE",
            targetId = inviteId,
            details = "Invited ${invitedEmail.trim()} as ${role.displayName}"
        )
        inviteId
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.CREATE, "staff_invites") }

    suspend fun acceptStaffInvite(invite: StaffInvite): Result<Unit> = runCatching {
        val uid = requireUserId()
        val email = requireUserEmail().trim().lowercase()
        val fallbackName = requireUserName()
        val inviteRef = db.collection("staff_invites").document(invite.id)
        val bizRef = db.collection("businesses").document(invite.businessId)
        val staffRef = bizRef.collection("staff").document(uid)
        val userRef = db.collection("users").document(uid)

        val existingUser = userRef.get().await()
        val initialMembers = listOf(invite.ownerUid, uid).distinct()
        val resolvedName = existingUser.getString("fullName")?.takeIf { it.isNotBlank() } ?: fallbackName

        val batch = db.batch()
        batch.update(
            inviteRef,
            mapOf(
                "status" to "ACCEPTED",
                "acceptedByUid" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            staffRef,
            mapOf(
                "businessId" to invite.businessId,
                "ownerUid" to invite.ownerUid,
                "memberUids" to initialMembers,
                "staffUid" to uid,
                "fullName" to resolvedName,
                "email" to email,
                "role" to invite.role,
                "active" to true,
                "inviteId" to invite.id,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.update(
            bizRef,
            mapOf(
                "memberUids" to FieldValue.arrayUnion(uid),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            userRef,
            mapOf(
                "userId" to uid,
                "fullName" to resolvedName,
                "email" to (existingUser.getString("email")?.takeIf { it.isNotBlank() } ?: email),
                "phone" to existingUser.getString("phone").orEmpty(),
                "country" to (existingUser.getString("country")?.takeIf { it.isNotBlank() } ?: "Nigeria"),
                "state" to existingUser.getString("state").orEmpty(),
                "city" to existingUser.getString("city").orEmpty(),
                "address" to existingUser.getString("address").orEmpty(),
                "activeBusinessId" to invite.businessId,
                "createdAt" to (existingUser.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()

        // Now that uid is in businesses/{businessId}.memberUids, sync full workspace memberUids & backfill docs
        runCatching {
            val bizSnap = bizRef.get().await()
            val allMembers = (bizSnap.get("memberUids") as? List<*>)?.filterIsInstance<String>()?.distinct().orEmpty()
            if (allMembers.isNotEmpty() && allMembers.size > initialMembers.size) {
                staffRef.update(
                    mapOf(
                        "memberUids" to allMembers,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        }
        syncMemberUidAcrossWorkspaceDocs(bizRef, uid)
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "staff_invites/${invite.id}") }

    private suspend fun syncMemberUidAcrossWorkspaceDocs(
        bizRef: com.google.firebase.firestore.DocumentReference,
        uid: String
    ) {
        val subcollectionsWithUpdatedAt = listOf("counters", "customers", "products", "transactions", "receipts", "staff")
        runCatching {
            val refsToUpdate = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Boolean>>()
            for (sub in subcollectionsWithUpdatedAt) {
                val snaps = bizRef.collection(sub).get().await()
                snaps.documents.forEach { doc ->
                    val members = (doc.get("memberUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
                    if (uid !in members) {
                        refsToUpdate.add(doc.reference to true)
                    }
                }
            }
            val notifSnaps = bizRef.collection("notifications").get().await()
            notifSnaps.documents.forEach { doc ->
                val members = (doc.get("memberUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (uid !in members) {
                    refsToUpdate.add(doc.reference to false)
                }
            }
            refsToUpdate.chunked(400).forEach { chunk ->
                val b = db.batch()
                chunk.forEach { (ref, includeUpdatedAt) ->
                    val update = if (includeUpdatedAt) {
                        mapOf(
                            "memberUids" to FieldValue.arrayUnion(uid),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    } else {
                        mapOf("memberUids" to FieldValue.arrayUnion(uid))
                    }
                    b.update(ref, update)
                }
                b.commit().await()
            }
        }
    }

    suspend fun toggleStaffActive(business: Business, member: StaffMember, active: Boolean): Result<Unit> = runCatching {
        requireUserId()
        require(member.staffUid != business.ownerUid) { "Cannot deactivate business owner." }
        val staffRef = db.collection("businesses").document(business.id)
            .collection("staff").document(member.staffUid)
        staffRef.update(
            mapOf(
                "active" to active,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "staff") }

    // --- AUDIT LOGS & NOTIFICATIONS ---

    fun observeAuditLogs(businessId: String, currentUserId: String): Flow<List<AuditLog>> =
        db.collection("businesses").document(businessId).collection("audit_logs")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses/$businessId/audit_logs", currentUserId)
                throw error
            }

    fun observeNotifications(businessId: String, currentUserId: String): Flow<List<AppNotification>> =
        db.collection("businesses").document(businessId).collection("notifications")
            .whereArrayContains("memberUids", currentUserId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(AppNotification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses/$businessId/notifications", currentUserId)
                throw error
            }

    suspend fun markNotificationRead(businessId: String, notificationId: String): Result<Unit> = runCatching {
        db.collection("businesses").document(businessId)
            .collection("notifications").document(notificationId)
            .update("read", true).await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "notifications") }

    private suspend fun logAuditAction(
        business: Business,
        actorUid: String,
        actorRole: String,
        action: String,
        targetType: String,
        targetId: String,
        details: String
    ) {
        val logId = "log_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        db.collection("businesses").document(business.id)
            .collection("audit_logs").document(logId)
            .set(
                mapOf(
                    "businessId" to business.id,
                    "ownerUid" to business.ownerUid,
                    "memberUids" to business.memberUids,
                    "actorUid" to actorUid,
                    "actorName" to requireUserName(),
                    "actorRole" to actorRole,
                    "action" to action,
                    "targetType" to targetType,
                    "targetId" to targetId,
                    "details" to details,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    // --- PLATFORM SETTINGS, ANNOUNCEMENTS, SUBSCRIPTIONS & SUPPORT ---

    fun observePlatformSettings(): Flow<PlatformSetting> =
        db.collection("platform_settings").document("global")
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(PlatformSetting::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        ?: PlatformSetting()
                } else PlatformSetting()
            }
            .catch { error ->
                logFlowError(error, OperationType.GET, "platform_settings/global", auth.currentUser?.uid)
                throw error
            }

    fun observeAnnouncements(): Flow<List<PlatformAnnouncement>> =
        db.collection("platform_announcements")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(PlatformAnnouncement::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "platform_announcements", auth.currentUser?.uid)
                throw error
            }

    fun observeBusinessSubscriptionPayments(businessId: String, ownerUid: String): Flow<List<SubscriptionPayment>> =
        db.collection("subscription_payments")
            .whereEqualTo("ownerUid", ownerUid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SubscriptionPayment::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter { it.businessId == businessId }
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "subscription_payments", ownerUid)
                throw error
            }

    suspend fun submitBankTransferPayment(
        business: Business,
        setting: PlatformSetting,
        planCode: String,
        amountNgn: Double,
        txRef: String,
        senderName: String,
        senderBank: String,
        transferNote: String,
        receiptImageBase64: String,
        receiptFileName: String,
        receiptMimeType: String,
        planId: String = "",
        planName: String = "",
        amountMinor: Long = kotlin.math.round(amountNgn * 100.0).toLong(),
        prorationCreditMinor: Long = 0L,
        idempotencyKey: String = ""
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val payId = "pay_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val bizName = business.tradingName.ifBlank { business.registeredName }
        val targetMail = setting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }
        val bankAccName = setting.bankAccountName.ifBlank { "ReceiptsNow" }
        val bankAccNumber = setting.bankAccountNumber.ifBlank { "3001823009" }
        val bankDisplay = setting.bankName.ifBlank { "KUDA" }
        val resolvedPlanName = planName.ifBlank { planCode }

        val emailSubject = "New ReceiptsNow $resolvedPlanName Transfer Payment - $bizName"
        val emailBody = buildString {
            appendLine("A business owner has submitted a bank transfer payment receipt for verification.")
            appendLine()
            appendLine("Business Name: $bizName")
            appendLine("Business ID: ${business.id}")
            appendLine("Owner Name: ${business.ownerName}")
            appendLine("Owner Email: ${business.ownerEmail}")
            appendLine("Owner Phone: ${business.ownerPhone.ifBlank { business.phone }}")
            appendLine("Plan: $resolvedPlanName ($planCode)")
            appendLine("Amount: NGN ${"%,.2f".format(amountNgn)}")
            if (prorationCreditMinor > 0L) {
                appendLine("Proration Credit Applied: NGN ${"%,.2f".format(prorationCreditMinor / 100.0)}")
            }
            appendLine("Paid Into: $bankAccName ($bankAccNumber - $bankDisplay)")
            appendLine("Transfer Reference: $txRef")
            appendLine("Sender Name: $senderName")
            if (senderBank.isNotBlank()) appendLine("Sender Bank: $senderBank")
            if (transferNote.isNotBlank()) appendLine("Notes: $transferNote")
            appendLine()
            appendLine("Open the Super Admin console (Payments tab) to review the receipt and click 'Verified' to activate their subscription.")
        }

        val batch = db.batch()
        batch.set(
            db.collection("subscription_payments").document(payId),
            mapOf(
                "businessId" to business.id,
                "businessName" to bizName,
                "ownerUid" to uid,
                "ownerName" to business.ownerName,
                "ownerEmail" to business.ownerEmail,
                "ownerPhone" to business.ownerPhone.ifBlank { business.phone },
                "planId" to planId,
                "planCode" to planCode,
                "planName" to resolvedPlanName,
                "amountNgn" to amountNgn,
                "amountMinor" to amountMinor,
                "prorationCreditMinor" to prorationCreditMinor,
                "currency" to "NGN",
                "txRef" to txRef,
                "idempotencyKey" to idempotencyKey.ifBlank { txRef },
                "flwTransactionId" to "",
                "paymentMethod" to "BANK_TRANSFER",
                "bankAccountName" to bankAccName,
                "bankAccountNumber" to bankAccNumber,
                "bankName" to bankDisplay,
                "senderName" to senderName.trim(),
                "senderBank" to senderBank.trim(),
                "transferNote" to transferNote.trim(),
                "receiptImageBase64" to receiptImageBase64,
                "receiptFileName" to receiptFileName.trim(),
                "receiptMimeType" to receiptMimeType,
                "paymentStatus" to "PENDING",
                "verifiedByUid" to "",
                "verifiedByEmail" to "",
                "verifiedAtMillis" to 0L,
                "cycleStartMillis" to 0L,
                "cycleEndMillis" to 0L,
                "invoiceId" to "",
                "rejectionReason" to "",
                "emailNotificationStatus" to "QUEUED",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            db.collection("mail_notifications").document(payId),
            mapOf(
                "paymentId" to payId,
                "businessId" to business.id,
                "businessName" to bizName,
                "ownerUid" to uid,
                "ownerName" to business.ownerName,
                "ownerEmail" to business.ownerEmail,
                "ownerPhone" to business.ownerPhone.ifBlank { business.phone },
                "planCode" to planCode,
                "amountNgn" to amountNgn,
                "txRef" to txRef,
                "senderName" to senderName.trim(),
                "toEmail" to targetMail,
                "subject" to emailSubject,
                "body" to emailBody,
                "deliveryStatus" to "QUEUED",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.update(
            db.collection("businesses").document(business.id),
            mapOf(
                "subscriptionStatus" to SubscriptionStatus.PENDING_PAYMENT.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        payId
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.CREATE, "subscription_payments") }

    data class PaystackActivationRecord(
        val paymentId: String,
        val invoiceId: String,
        val activated: Boolean
    )

    suspend fun recordAndActivatePaystackPayment(
        business: Business,
        setting: PlatformSetting,
        plan: SubscriptionPlan,
        entitlement: PlanEntitlement,
        reference: String,
        verification: com.example.data.billing.PaystackVerificationResult,
        paymentUrl: String,
        prorationCreditMinor: Long = 0L
    ): Result<PaystackActivationRecord> = runCatching {
        val uid = requireUserId()
        val email = requireUserEmail().ifBlank { business.ownerEmail }
        val cleanRef = reference.trim()
        require(cleanRef.length >= 6) { "Invalid Paystack reference." }

        // Idempotency: check if this Paystack reference was already verified for this owner
        val existing = db.collection("subscription_payments")
            .whereEqualTo("ownerUid", uid)
            .whereEqualTo("paystackReference", cleanRef)
            .get()
            .await()
            .toObjects(SubscriptionPayment::class.java)
            .firstOrNull { it.businessId == business.id && it.paymentStatus == "VERIFIED" }
        if (existing != null) {
            return@runCatching PaystackActivationRecord(
                paymentId = existing.id,
                invoiceId = existing.invoiceId,
                activated = true
            )
        }

        val payId = "pay_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val bizName = business.tradingName.ifBlank { business.registeredName }
        val targetMail = setting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }
        val paidMinor = verification.amountMinor.takeIf { it > 0L } ?: plan.priceMinor
        val amountNgn = paidMinor / 100.0
        val channelLabel = verification.channel.ifBlank { "paystack_checkout" }
        val sourceLabel = verification.verificationSource.ifBlank {
            com.example.data.billing.PaystackPaymentService.SOURCE_CHECKOUT_CONFIRMED
        }

        val emailSubject = if (verification.verified) {
            "Paystack Subscription Confirmed (${plan.name}) - $bizName"
        } else {
            "Paystack Reference Pending Verification (${plan.name}) - $bizName"
        }
        val emailBody = buildString {
            appendLine(
                if (verification.verified) {
                    "A business owner completed a Paystack subscription payment and their plan was automatically activated."
                } else {
                    "A business owner submitted a Paystack payment reference from external checkout for manual Super Admin verification."
                }
            )
            appendLine()
            appendLine("Business Name: $bizName")
            appendLine("Business ID: ${business.id}")
            appendLine("Owner Name: ${business.ownerName}")
            appendLine("Owner Email: ${business.ownerEmail}")
            appendLine("Plan: ${plan.name} (${plan.planCode})")
            appendLine("Amount: NGN ${"%,.2f".format(amountNgn)}")
            appendLine("Paystack Reference: $cleanRef")
            if (verification.transactionId.isNotBlank()) appendLine("Paystack Tx ID: ${verification.transactionId}")
            appendLine("Channel: $channelLabel")
            if (verification.cardSummary.isNotBlank()) appendLine("Card: ${verification.cardSummary}")
            if (verification.bank.isNotBlank()) appendLine("Bank: ${verification.bank}")
            appendLine("Verification Source: $sourceLabel")
            appendLine("Gateway Response: ${verification.gatewayResponse.ifBlank { "Completed on Paystack" }}")
        }

        val mailPayload = mapOf<String, Any>(
            "paymentId" to payId,
            "businessId" to business.id,
            "businessName" to bizName,
            "ownerUid" to uid,
            "ownerName" to business.ownerName,
            "ownerEmail" to business.ownerEmail,
            "ownerPhone" to business.ownerPhone.ifBlank { business.phone },
            "planCode" to plan.planCode,
            "amountNgn" to amountNgn,
            "txRef" to cleanRef,
            "senderName" to channelLabel,
            "toEmail" to targetMail,
            "subject" to emailSubject,
            "body" to emailBody,
            "deliveryStatus" to "QUEUED",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        if (verification.verified) {
            val draftPayment = SubscriptionPayment(
                id = payId,
                businessId = business.id,
                businessName = bizName,
                ownerUid = uid,
                ownerName = business.ownerName,
                ownerEmail = business.ownerEmail,
                ownerPhone = business.ownerPhone.ifBlank { business.phone },
                planId = plan.planId,
                planCode = plan.planCode,
                planName = plan.name,
                amountNgn = amountNgn,
                amountMinor = paidMinor,
                prorationCreditMinor = prorationCreditMinor,
                currency = plan.currency.ifBlank { "NGN" },
                txRef = cleanRef,
                idempotencyKey = cleanRef,
                paymentMethod = "PAYSTACK",
                paystackReference = cleanRef,
                paystackTransactionId = verification.transactionId,
                paystackChannel = channelLabel,
                paystackBank = verification.bank,
                paystackCardSummary = verification.cardSummary,
                paystackCustomerEmail = verification.customerEmail.ifBlank { email },
                paystackGatewayResponse = verification.gatewayResponse.ifBlank { "Payment confirmed via Paystack" },
                paystackPaymentUrl = paymentUrl,
                verificationSource = sourceLabel,
                paidAtMillis = verification.paidAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            val invoiceId = SubscriptionRepository(db, auth).createAndActivatePaystackPayment(
                payment = draftPayment,
                plan = plan,
                entitlement = entitlement,
                gracePeriodDays = setting.gracePeriodDays,
                rolloverDefault = setting.defaultRolloverEnabled,
                actorUid = uid,
                actorEmail = email,
                mailNotificationPayload = mailPayload
            )
            PaystackActivationRecord(paymentId = payId, invoiceId = invoiceId, activated = true)
        } else {
            val batch = db.batch()
            batch.set(
                db.collection("subscription_payments").document(payId),
                mapOf(
                    "businessId" to business.id,
                    "businessName" to bizName,
                    "ownerUid" to uid,
                    "ownerName" to business.ownerName,
                    "ownerEmail" to business.ownerEmail,
                    "ownerPhone" to business.ownerPhone.ifBlank { business.phone },
                    "planId" to plan.planId,
                    "planCode" to plan.planCode,
                    "planName" to plan.name,
                    "amountNgn" to (plan.priceMinor / 100.0),
                    "amountMinor" to plan.priceMinor,
                    "prorationCreditMinor" to prorationCreditMinor,
                    "currency" to plan.currency.ifBlank { "NGN" },
                    "txRef" to cleanRef,
                    "idempotencyKey" to cleanRef,
                    "flwTransactionId" to "",
                    "paymentMethod" to "PAYSTACK",
                    "paystackReference" to cleanRef,
                    "paystackTransactionId" to "",
                    "paystackChannel" to channelLabel,
                    "paystackBank" to "",
                    "paystackCardSummary" to "",
                    "paystackCustomerEmail" to email,
                    "paystackGatewayResponse" to "Submitted external Paystack reference",
                    "paystackPaymentUrl" to paymentUrl,
                    "verificationSource" to com.example.data.billing.PaystackPaymentService.SOURCE_MANUAL_UNVERIFIED,
                    "paidAtMillis" to 0L,
                    "bankAccountName" to "",
                    "bankAccountNumber" to "",
                    "bankName" to "",
                    "senderName" to business.ownerName,
                    "senderBank" to "",
                    "transferNote" to "External Paystack reference awaiting confirmation",
                    "receiptImageBase64" to "",
                    "receiptFileName" to "",
                    "receiptMimeType" to "",
                    "paymentStatus" to "PENDING",
                    "verifiedByUid" to "",
                    "verifiedByEmail" to "",
                    "verifiedAtMillis" to 0L,
                    "cycleStartMillis" to 0L,
                    "cycleEndMillis" to 0L,
                    "invoiceId" to "",
                    "rejectionReason" to "",
                    "emailNotificationStatus" to "QUEUED",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(db.collection("mail_notifications").document(payId), mailPayload)
            batch.update(
                db.collection("businesses").document(business.id),
                mapOf(
                    "subscriptionStatus" to SubscriptionStatus.PENDING_PAYMENT.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            PaystackActivationRecord(paymentId = payId, invoiceId = "", activated = false)
        }
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.CREATE, "subscription_payments") }

    suspend fun verifySubscriptionPaymentAdmin(
        payment: SubscriptionPayment,
        plans: List<SubscriptionPlan> = emptyList(),
        entitlements: List<PlanEntitlement> = emptyList(),
        setting: PlatformSetting = PlatformSetting(),
        paystackVerification: com.example.data.billing.PaystackVerificationResult? = null
    ): Result<Unit> = runCatching {
        val adminUid = requireUserId()
        val adminEmail = requireUserEmail()
        val resolvedPlan = plans.firstOrNull {
            (payment.planId.isNotBlank() && it.planId == payment.planId) ||
                it.planCode.equals(payment.planCode, ignoreCase = true)
        } ?: db.collection("subscription_plans")
            .get()
            .await()
            .toObjects(SubscriptionPlan::class.java)
            .firstOrNull {
                (payment.planId.isNotBlank() && it.planId == payment.planId) ||
                    it.planCode.equals(payment.planCode, ignoreCase = true)
            }
            ?: BillingCalculator.defaultLaunchPlans().firstOrNull {
                it.planCode.equals(payment.planCode, ignoreCase = true)
            }
            ?: BillingCalculator.defaultLaunchPlans().first()

        val resolvedEntitlement = entitlements.firstOrNull { it.planId == resolvedPlan.planId }
            ?: db.collection("plan_entitlements").document(resolvedPlan.planId).get().await()
                .let { snap -> if (snap.exists()) snap.toObject(PlanEntitlement::class.java) else null }
            ?: BillingCalculator.defaultLaunchEntitlements().firstOrNull { it.planId == resolvedPlan.planId }
            ?: PlanEntitlement(planId = resolvedPlan.planId)

        val pskMetadata: Map<String, Any>? = if (payment.isPaystack() || paystackVerification != null) {
            val verifiedSrc = if (paystackVerification?.verified == true) {
                com.example.data.billing.PaystackPaymentService.SOURCE_PAYSTACK_API
            } else {
                com.example.data.billing.PaystackPaymentService.SOURCE_ADMIN_VERIFIED
            }
            mapOf(
                "paymentMethod" to "PAYSTACK",
                "paystackReference" to (paystackVerification?.reference?.ifBlank { null } ?: payment.effectiveReference()),
                "paystackTransactionId" to (paystackVerification?.transactionId?.ifBlank { null } ?: payment.paystackTransactionId),
                "paystackChannel" to (paystackVerification?.channel?.ifBlank { null } ?: payment.paystackChannel.ifBlank { "paystack" }),
                "paystackBank" to (paystackVerification?.bank?.ifBlank { null } ?: payment.paystackBank),
                "paystackCardSummary" to (paystackVerification?.cardSummary?.ifBlank { null } ?: payment.paystackCardSummary),
                "paystackCustomerEmail" to (paystackVerification?.customerEmail?.ifBlank { null } ?: payment.paystackCustomerEmail.ifBlank { payment.ownerEmail }),
                "paystackGatewayResponse" to (paystackVerification?.gatewayResponse?.ifBlank { null } ?: payment.paystackGatewayResponse.ifBlank { "Confirmed by Super Admin" }),
                "verificationSource" to verifiedSrc,
                "paidAtMillis" to (paystackVerification?.paidAtMillis?.takeIf { it > 0L } ?: payment.paidAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis())
            )
        } else null

        SubscriptionRepository(db, auth).applyVerifiedSubscriptionPayment(
            payment = payment,
            plan = resolvedPlan,
            entitlement = resolvedEntitlement,
            gracePeriodDays = setting.gracePeriodDays,
            rolloverDefault = setting.defaultRolloverEnabled,
            actorUid = adminUid,
            actorEmail = adminEmail,
            paystackMetadata = pskMetadata
        )
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "subscription_payments/${payment.id}") }

    suspend fun markPaymentStatusAdmin(
        payment: SubscriptionPayment,
        targetStatus: String,
        reason: String
    ): Result<Unit> = runCatching {
        val adminUid = requireUserId()
        val adminEmail = requireUserEmail()
        val cleanStatus = targetStatus.trim().uppercase().takeIf {
            it in listOf("FAILED", "REFUNDED", "REJECTED")
        } ?: "REJECTED"
        val cleanReason = reason.trim().ifBlank { "Updated to $cleanStatus by Super Admin." }
        db.collection("subscription_payments").document(payment.id).update(
            mapOf(
                "paymentStatus" to cleanStatus,
                "verifiedByUid" to adminUid,
                "verifiedByEmail" to adminEmail,
                "verifiedAtMillis" to System.currentTimeMillis(),
                "rejectionReason" to cleanReason,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "subscription_payments/${payment.id}") }

    suspend fun rejectSubscriptionPaymentAdmin(payment: SubscriptionPayment, reason: String): Result<Unit> = runCatching {
        val adminUid = requireUserId()
        val adminEmail = requireUserEmail()
        val bizSnap = db.collection("businesses").document(payment.businessId).get().await()
        val business = bizSnap.toObject(Business::class.java)
        val cleanReason = reason.trim().ifBlank { "Payment could not be confirmed." }

        val batch = db.batch()
        batch.update(
            db.collection("subscription_payments").document(payment.id),
            mapOf(
                "paymentStatus" to "REJECTED",
                "verifiedByUid" to adminUid,
                "verifiedByEmail" to adminEmail,
                "verifiedAtMillis" to System.currentTimeMillis(),
                "rejectionReason" to cleanReason,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        if (business != null) {
            if (!business.isSubscriptionActive()) {
                batch.update(
                    db.collection("businesses").document(payment.businessId),
                    mapOf(
                        "subscriptionStatus" to SubscriptionStatus.AWAITING_SUBSCRIPTION.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.set(
                db.collection("businesses").document(payment.businessId)
                    .collection("notifications")
                    .document("ntf_${UUID.randomUUID().toString().take(12)}"),
                mapOf(
                    "businessId" to payment.businessId,
                    "ownerUid" to business.ownerUid,
                    "memberUids" to business.memberUids,
                    "title" to "Transfer Verification Rejected",
                    "body" to "Payment Ref ${payment.txRef} was rejected: $cleanReason",
                    "category" to "SUBSCRIPTION",
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "subscription_payments/${payment.id}") }

    // --- SUPPORT TICKETS ---

    fun observeBusinessTickets(ownerUid: String): Flow<List<SupportTicket>> =
        db.collection("support_tickets")
            .whereEqualTo("ownerUid", ownerUid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SupportTicket::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "support_tickets", ownerUid)
                throw error
            }

    suspend fun createSupportTicket(
        business: Business,
        subject: String,
        category: String,
        description: String,
        setting: PlatformSetting = PlatformSetting(),
        source: String = "HELP_DESK"
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val userEmail = requireUserEmail()
        val userName = auth.currentUser?.displayName?.takeIf { it.isNotBlank() } ?: business.ownerName
        val bizName = business.tradingName.ifBlank { business.registeredName }
        val targetEmail = setting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }
        val cleanSubject = subject.trim().ifBlank { "Customer Complaint" }.take(200)
        val cleanCategory = category.trim().ifBlank { "TECHNICAL" }.take(64)
        val cleanSource = source.trim().ifBlank { "HELP_DESK" }.take(32)
        val cleanDesc = description.trim().take(4000)

        val ticketId = "tkt_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val emailSubject = "[ReceiptsNow Complaint][$cleanCategory] $cleanSubject - $bizName".take(240)
        val emailBody = buildString {
            appendLine("A business user has reported a complaint / support difficulty in ReceiptsNow.")
            appendLine()
            appendLine("Ticket ID: $ticketId")
            appendLine("Source: $cleanSource")
            appendLine("Category: $cleanCategory")
            appendLine("Business: $bizName (${business.id})")
            appendLine("Reported By: $userName ($userEmail)")
            appendLine("Subject: $cleanSubject")
            appendLine()
            appendLine("Details:")
            appendLine(cleanDesc)
            appendLine()
            appendLine("Open Super Admin -> Tickets tab or reply to $userEmail.")
        }.take(7800)

        val batch = db.batch()
        batch.set(
            db.collection("support_tickets").document(ticketId),
            mapOf(
                "businessId" to business.id,
                "businessName" to bizName,
                "ownerUid" to uid,
                "ownerName" to userName,
                "ownerEmail" to userEmail,
                "subject" to cleanSubject,
                "category" to cleanCategory,
                "source" to cleanSource,
                "description" to cleanDesc,
                "status" to "OPEN",
                "adminReply" to "",
                "emailNotificationStatus" to "QUEUED",
                "emailNotificationError" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            db.collection("mail_notifications").document(ticketId),
            mapOf(
                "paymentId" to ticketId,
                "businessId" to business.id,
                "businessName" to bizName,
                "ownerUid" to uid,
                "ownerName" to userName,
                "ownerEmail" to userEmail,
                "ownerPhone" to business.ownerPhone.ifBlank { business.phone },
                "planCode" to cleanCategory,
                "amountNgn" to 0.0,
                "txRef" to ticketId,
                "senderName" to cleanSource,
                "toEmail" to targetEmail,
                "subject" to emailSubject,
                "body" to emailBody,
                "deliveryStatus" to "QUEUED",
                "lastError" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        ticketId
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.CREATE, "support_tickets") }

    // --- SUPER ADMIN ---

    fun isCurrentUserSuperAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        return user.isEmailVerified && user.email.equals("info.1803consult@gmail.com", ignoreCase = true)
    }

    fun observeAllBusinessesAdmin(): Flow<List<Business>> =
        db.collection("businesses")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(Business::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses", auth.currentUser?.uid)
                throw error
            }

    fun observeAllSubscriptionPaymentsAdmin(): Flow<List<SubscriptionPayment>> =
        db.collection("subscription_payments")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SubscriptionPayment::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "subscription_payments", auth.currentUser?.uid)
                throw error
            }

    fun observeAllMailNotificationsAdmin(): Flow<List<MailNotification>> =
        db.collection("mail_notifications")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(MailNotification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "mail_notifications", auth.currentUser?.uid)
                throw error
            }

    suspend fun updateMailDeliveryStatusAdmin(
        paymentId: String,
        status: String,
        lastError: String = ""
    ): Result<Unit> = runCatching {
        val batch = db.batch()
        batch.update(
            db.collection("subscription_payments").document(paymentId),
            mapOf(
                "emailNotificationStatus" to status,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.update(
            db.collection("mail_notifications").document(paymentId),
            mapOf(
                "deliveryStatus" to status,
                "lastError" to lastError.take(500),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "mail_notifications/$paymentId") }

    fun observeAllTicketsAdmin(): Flow<List<SupportTicket>> =
        db.collection("support_tickets")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SupportTicket::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "support_tickets", auth.currentUser?.uid)
                throw error
            }

    suspend fun refreshPlatformSettingsOnce(): Result<PlatformSetting> = runCatching {
        val snap = db.collection("platform_settings").document("global").get().await()
        if (snap.exists()) {
            snap.toObject(PlatformSetting::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: PlatformSetting()
        } else {
            PlatformSetting()
        }
    }

    suspend fun savePlatformSettingsAdmin(setting: PlatformSetting): Result<Unit> = runCatching {
        db.collection("platform_settings").document("global").set(
            mapOf(
                "settingKey" to "global",
                "monthlyPriceNgn" to setting.monthlyPriceNgn,
                "annualPriceNgn" to setting.annualPriceNgn,
                "storageLimitMb" to setting.storageLimitMb,
                "gracePeriodDays" to setting.gracePeriodDays.coerceIn(0, 60),
                "prorationEnabled" to setting.prorationEnabled,
                "defaultRolloverEnabled" to setting.defaultRolloverEnabled,
                "bankAccountName" to setting.bankAccountName.ifBlank { "ReceiptsNow" }.trim(),
                "bankAccountNumber" to setting.bankAccountNumber.ifBlank { "3001823009" }.trim(),
                "bankName" to setting.bankName.ifBlank { "KUDA" }.trim(),
                "notificationEmail" to setting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }.trim(),
                "emailNotificationWebhookUrl" to setting.emailNotificationWebhookUrl.trim(),
                "paystackMonthlyUrl" to setting.paystackMonthlyUrl.trim().ifBlank {
                    com.example.data.billing.PaystackPaymentService.DEFAULT_PAYSTACK_MONTHLY_URL
                },
                "paystackAnnualUrl" to setting.paystackAnnualUrl.trim().ifBlank {
                    com.example.data.billing.PaystackPaymentService.DEFAULT_PAYSTACK_ANNUAL_URL
                },
                "paystackPublicKey" to setting.paystackPublicKey.trim(),
                "paystackVerifyEndpoint" to setting.paystackVerifyEndpoint.trim(),
                "flutterwavePublicKey" to setting.flutterwavePublicKey.trim(),
                "flutterwaveEnvironment" to setting.flutterwaveEnvironment.ifBlank { "LIVE" }.trim(),
                "flutterwaveCurrency" to setting.flutterwaveCurrency.ifBlank { "NGN" }.trim(),
                "flutterwaveVerifyEndpoint" to setting.flutterwaveVerifyEndpoint.trim(),
                "flutterwaveWebhookUrl" to setting.flutterwaveWebhookUrl.trim(),
                "supportEmail" to setting.supportEmail.ifBlank { "info.1803consult@gmail.com" }.trim(),
                "supportPhone" to setting.supportPhone.trim(),
                "latestVersionCode" to setting.latestVersionCode.coerceAtLeast(1),
                "latestVersionName" to setting.latestVersionName.trim().ifBlank { "1.0" },
                "updateTitle" to setting.updateTitle.trim(),
                "releaseNotes" to setting.releaseNotes.trim(),
                "updateDownloadUrl" to setting.updateDownloadUrl.trim(),
                "forceUpdate" to setting.forceUpdate,
                "configRevision" to setting.configRevision,
                "publishedAtMillis" to setting.publishedAtMillis,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "platform_settings/global") }

    // --- DAILY RECEIPT CALCULATOR & BREAKDOWN SHEETS ---

    fun observeDailyLedgers(businessId: String, ownerUid: String): Flow<List<DailyLedgerSheet>> =
        db.collection("businesses").document(businessId)
            .collection("daily_ledgers")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(DailyLedgerSheet::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "businesses/$businessId/daily_ledgers", ownerUid)
                throw error
            }

    suspend fun saveDailyLedgerSheet(
        business: Business,
        title: String,
        ledgerDate: String,
        entries: List<com.example.data.model.LedgerEntryItem>,
        existingId: String = ""
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val actorName = requireUserName()
        val summary = LedgerCalculator.summarize(entries)
        val sheetId = existingId.ifBlank { "ldg_${UUID.randomUUID().toString().replace("-", "").take(14)}" }
        val docRef = db.collection("businesses").document(business.id)
            .collection("daily_ledgers").document(sheetId)
        val existingSnap = if (existingId.isNotBlank()) docRef.get().await() else null
        val createdAtVal = existingSnap?.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()

        val entryMaps = entries.take(250).map { item ->
            mapOf(
                "id" to item.id,
                "documentName" to item.documentName.take(150),
                "merchantOrParty" to item.merchantOrParty.take(160),
                "category" to item.category,
                "amount" to item.amount,
                "currency" to item.currency.ifBlank { business.defaultCurrency.ifBlank { "NGN" } },
                "paymentMethod" to item.paymentMethod.take(64),
                "reference" to item.reference.take(120),
                "transactionDate" to item.transactionDate.take(64),
                "notes" to item.notes.take(300),
                "confidenceScore" to item.confidenceScore,
                "sourceType" to item.sourceType.take(40)
            )
        }

        docRef.set(
            mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "title" to title.trim().ifBlank { "End-of-Day Breakdown Sheet" }.take(160),
                "ledgerDate" to ledgerDate.trim().take(32),
                "currency" to business.defaultCurrency.ifBlank { "NGN" },
                "totalSales" to summary.totalSales,
                "totalPurchases" to summary.totalPurchases,
                "totalExpenditure" to summary.totalExpenditure,
                "totalRefunds" to summary.totalRefunds,
                "totalOther" to summary.totalOther,
                "grossProfit" to summary.grossProfit,
                "netProfit" to summary.netProfit,
                "entryCount" to entries.size,
                "entries" to entryMaps,
                "createdByUid" to uid,
                "createdByName" to actorName,
                "createdAt" to createdAtVal,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        sheetId
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "businesses/${business.id}/daily_ledgers") }

    suspend fun deleteDailyLedgerSheet(businessId: String, ledgerId: String): Result<Unit> = runCatching {
        db.collection("businesses").document(businessId)
            .collection("daily_ledgers").document(ledgerId)
            .delete().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.DELETE, "businesses/$businessId/daily_ledgers/$ledgerId") }

    suspend fun setBusinessAccountStatusAdmin(businessId: String, accountStatus: String): Result<Unit> = runCatching {
        db.collection("businesses").document(businessId).update(
            mapOf(
                "accountStatus" to accountStatus,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "businesses/$businessId") }

    suspend fun publishAnnouncementAdmin(title: String, message: String, category: String): Result<String> = runCatching {
        val uid = requireUserId()
        val id = "ann_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        db.collection("platform_announcements").document(id).set(
            mapOf(
                "title" to title.trim(),
                "message" to message.trim(),
                "category" to category,
                "status" to "PUBLISHED",
                "targetAudience" to "ALL",
                "createdByUid" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        id
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.CREATE, "platform_announcements") }

    suspend fun replySupportTicketAdmin(ticketId: String, status: String, reply: String): Result<Unit> = runCatching {
        db.collection("support_tickets").document(ticketId).update(
            mapOf(
                "status" to status,
                "adminReply" to reply.trim(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "support_tickets/$ticketId") }

    suspend fun updateTicketMailDeliveryStatusAdmin(
        ticketId: String,
        deliveryStatus: String,
        lastError: String = ""
    ): Result<Unit> = runCatching {
        val batch = db.batch()
        batch.update(
            db.collection("support_tickets").document(ticketId),
            mapOf(
                "emailNotificationStatus" to deliveryStatus,
                "emailNotificationError" to lastError.take(300),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            db.collection("mail_notifications").document(ticketId),
            mapOf(
                "deliveryStatus" to deliveryStatus,
                "lastError" to lastError.take(300),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )
        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "support_tickets/$ticketId") }

    suspend fun updateTicketRelayOutcome(
        ticketId: String,
        delivered: Boolean,
        errorMessage: String = ""
    ): Result<Unit> = runCatching {
        val status = if (delivered) "SENT" else "FAILED"
        db.collection("support_tickets").document(ticketId).update(
            mapOf(
                "emailNotificationStatus" to status,
                "emailNotificationError" to errorMessage.take(300),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }

    private fun logFlowError(error: Throwable, op: OperationType, path: String, expectedUid: String?) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<com.google.firebase.firestore.FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: error
        if (expectedUid == null || auth.currentUser?.uid == expectedUid) {
            handleFirestoreError(exceptionToLog, op, path)
        }
    }

    companion object {
        private val PUBLIC_VERIFICATION_CODE_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")

        fun formatReceiptNumber(
            numberingFormat: String,
            prefix: String,
            year: Int,
            sequence: Long
        ): String {
            val cleanPrefix = prefix.trim().uppercase().ifBlank { "RCN" }
            val seqStr = sequence.coerceAtLeast(1L).toString().padStart(6, '0')
            val template = numberingFormat.trim().ifBlank { "{PREFIX}/RCT/{YEAR}/{SEQ}" }
            val hasSeqPlaceholder = template.contains("{SEQ}", ignoreCase = false)
            val expanded = template
                .replace("{PREFIX}", cleanPrefix)
                .replace("{YEAR}", year.toString())
                .replace("{SEQ}", seqStr)
            return if (hasSeqPlaceholder) expanded else "$expanded/$seqStr"
        }
    }
}
