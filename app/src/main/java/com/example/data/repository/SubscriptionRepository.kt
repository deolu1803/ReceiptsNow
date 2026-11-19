package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.BillingCalculator
import com.example.data.model.BillingFrequency
import com.example.data.model.Business
import com.example.data.model.BusinessSubscription
import com.example.data.model.CustomBusinessEntitlement
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.PlanChangeHistory
import com.example.data.model.PlanChangeType
import com.example.data.model.PlanEntitlement
import com.example.data.model.PlanFeature
import com.example.data.model.PlanStatus
import com.example.data.model.StorageOverLimitAction
import com.example.data.model.StorageUsage
import com.example.data.model.SubscriptionEvent
import com.example.data.model.SubscriptionInvoice
import com.example.data.model.SubscriptionPayment
import com.example.data.model.SubscriptionPlan
import com.example.data.model.SubscriptionStatus
import com.example.data.model.UsageQuota
import com.example.data.model.UsageType
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max

class SubscriptionRepository(
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
            ?: throw IllegalStateException("User must be signed in with Google before accessing billing.")

    private fun requireUserEmail(): String =
        auth.currentUser?.email.orEmpty()

    // --- CATALOG OBSERVERS ---

    fun observeSubscriptionPlans(): Flow<List<SubscriptionPlan>> =
        db.collection("subscription_plans")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SubscriptionPlan::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedWith(compareBy<SubscriptionPlan> { it.sortOrder }.thenBy { it.priceMinor })
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "subscription_plans", auth.currentUser?.uid)
                throw error
            }

    fun observePlanFeatures(): Flow<List<PlanFeature>> =
        db.collection("plan_features")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(PlanFeature::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.sortOrder }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "plan_features", auth.currentUser?.uid)
                throw error
            }

    fun observePlanEntitlements(): Flow<List<PlanEntitlement>> =
        db.collection("plan_entitlements")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(PlanEntitlement::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "plan_entitlements", auth.currentUser?.uid)
                throw error
            }

    // --- BUSINESS-SCOPED OBSERVERS ---

    fun observeBusinessSubscription(businessId: String): Flow<BusinessSubscription?> =
        db.collection("business_subscriptions").document(businessId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(BusinessSubscription::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                logFlowError(error, OperationType.GET, "business_subscriptions/$businessId", auth.currentUser?.uid)
                throw error
            }

    fun observeUsageQuota(businessId: String): Flow<UsageQuota?> =
        db.collection("usage_quotas").document("${businessId}_current")
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(UsageQuota::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                logFlowError(error, OperationType.GET, "usage_quotas/${businessId}_current", auth.currentUser?.uid)
                throw error
            }

    fun observeStorageUsage(businessId: String): Flow<StorageUsage?> =
        db.collection("storage_usage").document(businessId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(StorageUsage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                logFlowError(error, OperationType.GET, "storage_usage/$businessId", auth.currentUser?.uid)
                throw error
            }

    fun observeCustomBusinessEntitlement(businessId: String): Flow<CustomBusinessEntitlement?> =
        db.collection("custom_business_entitlements").document(businessId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(CustomBusinessEntitlement::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                logFlowError(error, OperationType.GET, "custom_business_entitlements/$businessId", auth.currentUser?.uid)
                throw error
            }

    fun observeBusinessInvoices(businessId: String, ownerUid: String): Flow<List<SubscriptionInvoice>> =
        db.collection("subscription_invoices")
            .whereEqualTo("ownerUid", ownerUid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SubscriptionInvoice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter { it.businessId == businessId }
                    .sortedByDescending { it.issuedAtMillis }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "subscription_invoices", ownerUid)
                throw error
            }

    fun observePlanChangeHistory(businessId: String, ownerUid: String): Flow<List<PlanChangeHistory>> =
        db.collection("plan_change_history")
            .whereEqualTo("ownerUid", ownerUid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(PlanChangeHistory::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter { it.businessId == businessId }
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "plan_change_history", ownerUid)
                throw error
            }

    // --- SUPER ADMIN OBSERVERS ---

    fun observeAllSubscriptionsAdmin(): Flow<List<BusinessSubscription>> =
        db.collection("business_subscriptions")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(BusinessSubscription::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "business_subscriptions", auth.currentUser?.uid)
                throw error
            }

    fun observeAllInvoicesAdmin(): Flow<List<SubscriptionInvoice>> =
        db.collection("subscription_invoices")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SubscriptionInvoice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.issuedAtMillis }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "subscription_invoices", auth.currentUser?.uid)
                throw error
            }

    fun observeAllUsageQuotasAdmin(): Flow<List<UsageQuota>> =
        db.collection("usage_quotas")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(UsageQuota::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "usage_quotas", auth.currentUser?.uid)
                throw error
            }

    fun observeAllStorageUsageAdmin(): Flow<List<StorageUsage>> =
        db.collection("storage_usage")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(StorageUsage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "storage_usage", auth.currentUser?.uid)
                throw error
            }

    fun observeAllSubscriptionEventsAdmin(): Flow<List<SubscriptionEvent>> =
        db.collection("subscription_events")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SubscriptionEvent::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "subscription_events", auth.currentUser?.uid)
                throw error
            }

    fun observeAllPlanChangeHistoryAdmin(): Flow<List<PlanChangeHistory>> =
        db.collection("plan_change_history")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(PlanChangeHistory::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "plan_change_history", auth.currentUser?.uid)
                throw error
            }

    fun observeAllCustomEntitlementsAdmin(): Flow<List<CustomBusinessEntitlement>> =
        db.collection("custom_business_entitlements")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(CustomBusinessEntitlement::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                logFlowError(error, OperationType.LIST, "custom_business_entitlements", auth.currentUser?.uid)
                throw error
            }

    // --- LAUNCH SEEDING (FIXED ₦1,500 MONTHLY / ₦18,000 ANNUAL) ---

    suspend fun ensureDefaultLaunchPlansSeededAdmin(): Result<Unit> = runCatching {
        val adminUid = requireUserId()
        val adminEmail = requireUserEmail()
        val existingPlanIds = db.collection("subscription_plans").get().await().documents.map { it.id }.toSet()
        val missingPlans = BillingCalculator.defaultLaunchPlans().filter { it.planId !in existingPlanIds }
        if (missingPlans.isEmpty()) return@runCatching Unit

        val missingIds = missingPlans.map { it.planId }.toSet()
        val ents = BillingCalculator.defaultLaunchEntitlements().filter { it.planId in missingIds }
        val feats = BillingCalculator.defaultLaunchFeatures().filter { it.planId in missingIds }
        val batch = db.batch()

        missingPlans.forEach { plan ->
            batch.set(
                db.collection("subscription_plans").document(plan.planId),
                mapOf(
                    "planId" to plan.planId,
                    "planCode" to plan.planCode,
                    "name" to plan.name,
                    "description" to plan.description,
                    "priceMinor" to plan.priceMinor,
                    "currency" to plan.currency,
                    "billingFrequency" to plan.billingFrequency,
                    "intervalDays" to plan.intervalDays,
                    "trialDays" to plan.trialDays,
                    "visible" to plan.visible,
                    "status" to plan.status,
                    "sortOrder" to plan.sortOrder,
                    "paystackPaymentUrl" to plan.paystackPaymentUrl,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        ents.forEach { ent ->
            batch.set(
                db.collection("plan_entitlements").document(ent.planId),
                ent.toSnapshotMap() + mapOf(
                    "planId" to ent.planId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        feats.forEach { feat ->
            batch.set(
                db.collection("plan_features").document(feat.id),
                mapOf(
                    "planId" to feat.planId,
                    "featureKey" to feat.featureKey,
                    "label" to feat.label,
                    "valueText" to feat.valueText,
                    "included" to feat.included,
                    "sortOrder" to feat.sortOrder,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.set(
            db.collection("subscription_events").document("evt_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "actorUid" to adminUid,
                "actorEmail" to adminEmail,
                "actorRole" to "SUPER_ADMIN",
                "businessId" to "",
                "entityType" to "subscription_plans",
                "entityId" to "launch_catalog",
                "action" to "LAUNCH_PLANS_SEEDED",
                "previousValue" to existingPlanIds.toString(),
                "newValue" to "Monthly=₦1,500 (150000), Annual=₦18,000 (1800000)",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "subscription_plans") }

    // --- SUPER ADMIN PLAN CRUD & AUDIT ---

    suspend fun savePlanWithEntitlementAdmin(
        plan: SubscriptionPlan,
        entitlement: PlanEntitlement,
        features: List<PlanFeature>
    ): Result<String> = runCatching {
        val adminUid = requireUserId()
        val adminEmail = requireUserEmail()
        val cleanPlanId = plan.planId.trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .ifBlank { "plan_${UUID.randomUUID().toString().replace("-", "").take(10)}" }

        val planRef = db.collection("subscription_plans").document(cleanPlanId)
        val entRef = db.collection("plan_entitlements").document(cleanPlanId)
        val existingPlan = planRef.get().await()
        val existingEnt = entRef.get().await()
        val prevSummary = if (existingPlan.exists()) {
            "name=${existingPlan.getString("name")},priceMinor=${existingPlan.getLong("priceMinor")},status=${existingPlan.getString("status")},maxReceipts=${existingEnt.getLong("maxReceiptsPerCycle")},storageBytes=${existingEnt.getLong("storageLimitBytes")}"
        } else "null"

        val batch = db.batch()
        batch.set(
            planRef,
            mapOf(
                "planId" to cleanPlanId,
                "planCode" to plan.planCode.trim().uppercase().ifBlank { cleanPlanId.uppercase() },
                "name" to plan.name.trim(),
                "description" to plan.description.trim(),
                "priceMinor" to max(0L, plan.priceMinor),
                "currency" to plan.currency.ifBlank { "NGN" }.uppercase(),
                "billingFrequency" to plan.billingFrequency,
                "intervalDays" to max(1, plan.intervalDays),
                "trialDays" to max(0, plan.trialDays),
                "visible" to plan.visible,
                "status" to plan.status,
                "sortOrder" to plan.sortOrder,
                "paystackPaymentUrl" to plan.paystackPaymentUrl.trim(),
                "createdAt" to (existingPlan.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        batch.set(
            entRef,
            entitlement.copy(planId = cleanPlanId).toSnapshotMap() + mapOf(
                "planId" to cleanPlanId,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        // Delete removed features for this planId & upsert provided features
        val oldFeatures = db.collection("plan_features").whereEqualTo("planId", cleanPlanId).get().await()
        val keepIds = features.mapNotNull { it.id.takeIf(String::isNotBlank) }.toSet()
        oldFeatures.documents.forEach { doc ->
            if (doc.id !in keepIds) batch.delete(doc.reference)
        }
        features.forEachIndexed { idx, feat ->
            val featId = feat.id.ifBlank { "feat_${cleanPlanId}_${idx}_${UUID.randomUUID().toString().take(6)}" }
            batch.set(
                db.collection("plan_features").document(featId),
                mapOf(
                    "planId" to cleanPlanId,
                    "featureKey" to feat.featureKey.ifBlank { "feature_$idx" },
                    "label" to feat.label.trim(),
                    "valueText" to feat.valueText.trim(),
                    "included" to feat.included,
                    "sortOrder" to idx + 1,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        val newSummary = "name=${plan.name.trim()},priceMinor=${plan.priceMinor},status=${plan.status},maxReceipts=${entitlement.maxReceiptsPerCycle},storageBytes=${entitlement.storageLimitBytes}"
        batch.set(
            db.collection("subscription_events").document("evt_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "actorUid" to adminUid,
                "actorEmail" to adminEmail,
                "actorRole" to "SUPER_ADMIN",
                "businessId" to "",
                "entityType" to "subscription_plans",
                "entityId" to cleanPlanId,
                "action" to if (existingPlan.exists()) "PLAN_UPDATED" else "PLAN_CREATED",
                "previousValue" to prevSummary.take(1800),
                "newValue" to newSummary.take(1800),
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        cleanPlanId
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "subscription_plans") }

    suspend fun setPlanStatusAdmin(plan: SubscriptionPlan, status: PlanStatus, visible: Boolean): Result<Unit> = runCatching {
        val adminUid = requireUserId()
        val adminEmail = requireUserEmail()
        val batch = db.batch()
        batch.update(
            db.collection("subscription_plans").document(plan.planId),
            mapOf(
                "status" to status.name,
                "visible" to visible,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            db.collection("subscription_events").document("evt_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "actorUid" to adminUid,
                "actorEmail" to adminEmail,
                "actorRole" to "SUPER_ADMIN",
                "businessId" to "",
                "entityType" to "subscription_plans",
                "entityId" to plan.planId,
                "action" to "PLAN_STATUS_${status.name}",
                "previousValue" to "status=${plan.status},visible=${plan.visible}",
                "newValue" to "status=${status.name},visible=$visible",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "subscription_plans/${plan.planId}") }

    // --- CUSTOM OVERRIDES, QUOTA ADJUSTMENT & SUSPENSION ---

    suspend fun saveCustomBusinessEntitlementAdmin(
        business: Business,
        override: CustomBusinessEntitlement
    ): Result<Unit> = runCatching {
        val adminUid = requireUserId()
        val adminEmail = requireUserEmail()
        val overrideRef = db.collection("custom_business_entitlements").document(business.id)
        val prevSnap = overrideRef.get().await()
        val prevVal = if (prevSnap.exists()) prevSnap.data?.toString().orEmpty().take(1500) else "none"

        val payload = mutableMapOf<String, Any?>(
            "businessId" to business.id,
            "overrideReceiptsPerCycle" to override.overrideReceiptsPerCycle,
            "overrideStorageLimitBytes" to override.overrideStorageLimitBytes,
            "overrideMaxStaff" to override.overrideMaxStaff,
            "overrideMaxOcr" to override.overrideMaxOcr,
            "overrideMaxCustomers" to override.overrideMaxCustomers,
            "overrideRolloverEnabled" to override.overrideRolloverEnabled,
            "overrideNotes" to override.overrideNotes.trim(),
            "updatedByUid" to adminUid,
            "updatedByEmail" to adminEmail,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        val batch = db.batch()
        batch.set(overrideRef, payload)

        // Sync active usage_quotas and storage_usage limits when present
        val quotaRef = db.collection("usage_quotas").document("${business.id}_current")
        val quotaSnap = quotaRef.get().await()
        if (quotaSnap.exists()) {
            val quotaUpdate = mutableMapOf<String, Any>("updatedAt" to FieldValue.serverTimestamp())
            override.overrideReceiptsPerCycle?.let { quotaUpdate["receiptsAllocated"] = it }
            override.overrideMaxOcr?.let { quotaUpdate["ocrAllocated"] = it }
            batch.update(quotaRef, quotaUpdate)
        }
        override.overrideStorageLimitBytes?.let { newStorageBytes ->
            val storageRef = db.collection("storage_usage").document(business.id)
            if (storageRef.get().await().exists()) {
                batch.update(
                    storageRef,
                    mapOf(
                        "allocatedBytes" to newStorageBytes,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.update(
                db.collection("businesses").document(business.id),
                mapOf(
                    "storageLimitBytes" to newStorageBytes,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        batch.set(
            db.collection("plan_change_history").document("chg_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "businessId" to business.id,
                "businessName" to business.tradingName.ifBlank { business.registeredName },
                "ownerUid" to business.ownerUid,
                "fromPlanId" to business.subscriptionPlanId,
                "fromPlanName" to business.subscriptionPlanCode,
                "toPlanId" to business.subscriptionPlanId,
                "toPlanName" to "Custom Override",
                "changeType" to PlanChangeType.CUSTOM_OVERRIDE.name,
                "prorationCreditMinor" to 0L,
                "chargedMinor" to 0L,
                "currency" to business.defaultCurrency,
                "actorUid" to adminUid,
                "actorEmail" to adminEmail,
                "notes" to override.overrideNotes.ifBlank { "Custom business allowance override applied by Super Admin." },
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            db.collection("subscription_events").document("evt_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "actorUid" to adminUid,
                "actorEmail" to adminEmail,
                "actorRole" to "SUPER_ADMIN",
                "businessId" to business.id,
                "entityType" to "custom_business_entitlements",
                "entityId" to business.id,
                "action" to "CUSTOM_OVERRIDE_SAVED",
                "previousValue" to prevVal,
                "newValue" to "receipts=${override.overrideReceiptsPerCycle},storage=${override.overrideStorageLimitBytes},staff=${override.overrideMaxStaff},ocr=${override.overrideMaxOcr},customers=${override.overrideMaxCustomers}",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "custom_business_entitlements/${business.id}") }

    suspend fun suspendOrRestoreSubscriptionAdmin(
        business: Business,
        suspend: Boolean,
        reason: String
    ): Result<Unit> = runCatching {
        val adminUid = requireUserId()
        val adminEmail = requireUserEmail()
        val newAccountStatus = if (suspend) "SUSPENDED" else "ACTIVE"
        val newSubStatus = if (suspend) SubscriptionStatus.SUSPENDED.name else SubscriptionStatus.ACTIVE.name
        val batch = db.batch()
        batch.update(
            db.collection("businesses").document(business.id),
            mapOf(
                "accountStatus" to newAccountStatus,
                "subscriptionStatus" to newSubStatus,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val subRef = db.collection("business_subscriptions").document(business.id)
        if (subRef.get().await().exists()) {
            batch.update(
                subRef,
                mapOf(
                    "status" to newSubStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.set(
            db.collection("plan_change_history").document("chg_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "businessId" to business.id,
                "businessName" to business.tradingName.ifBlank { business.registeredName },
                "ownerUid" to business.ownerUid,
                "fromPlanId" to business.subscriptionPlanId,
                "fromPlanName" to business.subscriptionPlanCode,
                "toPlanId" to business.subscriptionPlanId,
                "toPlanName" to business.subscriptionPlanCode,
                "changeType" to if (suspend) PlanChangeType.SUSPENSION.name else PlanChangeType.RESTORATION.name,
                "prorationCreditMinor" to 0L,
                "chargedMinor" to 0L,
                "currency" to business.defaultCurrency,
                "actorUid" to adminUid,
                "actorEmail" to adminEmail,
                "notes" to reason.ifBlank { if (suspend) "Suspended by Super Admin" else "Restored by Super Admin" },
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            db.collection("subscription_events").document("evt_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "actorUid" to adminUid,
                "actorEmail" to adminEmail,
                "actorRole" to "SUPER_ADMIN",
                "businessId" to business.id,
                "entityType" to "business_subscriptions",
                "entityId" to business.id,
                "action" to if (suspend) "SUBSCRIPTION_SUSPENDED" else "SUBSCRIPTION_RESTORED",
                "previousValue" to "accountStatus=${business.accountStatus},subscriptionStatus=${business.subscriptionStatus}",
                "newValue" to "accountStatus=$newAccountStatus,subscriptionStatus=$newSubStatus",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            db.collection("businesses").document(business.id)
                .collection("notifications").document("ntf_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "title" to if (suspend) "Subscription Suspended" else "Subscription Restored",
                "body" to if (suspend) {
                    "Your subscription has been suspended: ${reason.ifBlank { "Contact ReceiptsNow support" }}. All historical receipts and documents remain safely preserved."
                } else {
                    "Your subscription has been restored. You may resume generating receipts."
                },
                "category" to "SUBSCRIPTION",
                "read" to false,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "business_subscriptions/${business.id}") }

    suspend fun processCycleExpiriesAdmin(
        businesses: List<Business>,
        subscriptions: List<BusinessSubscription>,
        gracePeriodDays: Int
    ): Result<Int> = runCatching {
        val adminUid = requireUserId()
        val adminEmail = requireUserEmail()
        val now = System.currentTimeMillis()
        var transitioned = 0

        for (biz in businesses) {
            val sub = subscriptions.firstOrNull { it.businessId == biz.id } ?: continue
            if (sub.cycleEndMillis <= 0L || now <= sub.cycleEndMillis) continue
            val graceEnd = if (sub.gracePeriodEndMillis > sub.cycleEndMillis) {
                sub.gracePeriodEndMillis
            } else {
                sub.cycleEndMillis + max(0, gracePeriodDays).toLong() * 24L * 60L * 60L * 1000L
            }

            val targetStatus = when {
                sub.status in listOf(SubscriptionStatus.ACTIVE.name, SubscriptionStatus.EXPIRING_SOON.name, SubscriptionStatus.TRIALING.name) && now <= graceEnd ->
                    SubscriptionStatus.GRACE_PERIOD.name
                sub.status in listOf(SubscriptionStatus.ACTIVE.name, SubscriptionStatus.EXPIRING_SOON.name, SubscriptionStatus.TRIALING.name, SubscriptionStatus.GRACE_PERIOD.name) && now > graceEnd ->
                    SubscriptionStatus.EXPIRED.name
                else -> null
            } ?: continue

            val batch = db.batch()
            batch.update(
                db.collection("business_subscriptions").document(biz.id),
                mapOf(
                    "status" to targetStatus,
                    "gracePeriodEndMillis" to graceEnd,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                db.collection("businesses").document(biz.id),
                mapOf(
                    "subscriptionStatus" to targetStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (targetStatus == SubscriptionStatus.EXPIRED.name) {
                batch.set(
                    db.collection("plan_change_history").document("chg_${UUID.randomUUID().toString().take(12)}"),
                    mapOf(
                        "businessId" to biz.id,
                        "businessName" to biz.tradingName.ifBlank { biz.registeredName },
                        "ownerUid" to biz.ownerUid,
                        "fromPlanId" to sub.planId,
                        "fromPlanName" to sub.planNameSnapshot,
                        "toPlanId" to sub.planId,
                        "toPlanName" to sub.planNameSnapshot,
                        "changeType" to PlanChangeType.EXPIRY.name,
                        "prorationCreditMinor" to 0L,
                        "chargedMinor" to 0L,
                        "currency" to sub.agreedCurrency,
                        "actorUid" to adminUid,
                        "actorEmail" to adminEmail,
                        "notes" to "Cycle & grace period expired. Historical receipts and records remain read-only.",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.set(
                db.collection("businesses").document(biz.id)
                    .collection("notifications").document("ntf_${UUID.randomUUID().toString().take(12)}"),
                mapOf(
                    "businessId" to biz.id,
                    "ownerUid" to biz.ownerUid,
                    "memberUids" to biz.memberUids,
                    "title" to if (targetStatus == SubscriptionStatus.GRACE_PERIOD.name) {
                        "Subscription in Grace Period"
                    } else "Subscription Expired - Renew to Resume Issuing",
                    "body" to if (targetStatus == SubscriptionStatus.GRACE_PERIOD.name) {
                        "Your ${sub.planNameSnapshot} billing cycle ended. You are in your grace period—renew soon to avoid interruption."
                    } else {
                        "Your ${sub.planNameSnapshot} cycle & grace period ended. Existing receipts remain safe & downloadable; renew to generate new receipts."
                    },
                    "category" to "SUBSCRIPTION",
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            transitioned++
        }
        transitioned
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "business_subscriptions") }

    // --- OWNER LIFECYCLE CONTROLS (CANCEL / REACTIVATE / AUTO-RENEW) ---

    suspend fun updateAutoRenewOrCancellation(
        business: Business,
        subscription: BusinessSubscription?,
        autoRenew: Boolean,
        cancelAtPeriodEnd: Boolean,
        cancelImmediately: Boolean = false
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val email = requireUserEmail()
        val sub = subscription ?: throw IllegalStateException("No active subscription record found.")
        val now = System.currentTimeMillis()
        val subRef = db.collection("business_subscriptions").document(business.id)

        val newStatus = if (cancelImmediately) SubscriptionStatus.CANCELLED.name else sub.status
        val batch = db.batch()
        batch.update(
            subRef,
            mapOf(
                "autoRenew" to autoRenew,
                "cancelAtPeriodEnd" to cancelAtPeriodEnd,
                "cancelledAtMillis" to if (cancelAtPeriodEnd || cancelImmediately) now else 0L,
                "status" to newStatus,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        if (cancelImmediately) {
            batch.update(
                db.collection("businesses").document(business.id),
                mapOf(
                    "subscriptionStatus" to SubscriptionStatus.CANCELLED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        if (cancelAtPeriodEnd || cancelImmediately) {
            batch.set(
                db.collection("plan_change_history").document("chg_${UUID.randomUUID().toString().take(12)}"),
                mapOf(
                    "businessId" to business.id,
                    "businessName" to business.tradingName.ifBlank { business.registeredName },
                    "ownerUid" to business.ownerUid,
                    "fromPlanId" to sub.planId,
                    "fromPlanName" to sub.planNameSnapshot,
                    "toPlanId" to sub.planId,
                    "toPlanName" to sub.planNameSnapshot,
                    "changeType" to PlanChangeType.CANCELLATION.name,
                    "prorationCreditMinor" to 0L,
                    "chargedMinor" to 0L,
                    "currency" to sub.agreedCurrency,
                    "actorUid" to uid,
                    "actorEmail" to email,
                    "notes" to if (cancelImmediately) "Cancelled immediately by business owner." else "Scheduled cancellation at period end.",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.set(
            db.collection("subscription_events").document("evt_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "actorUid" to uid,
                "actorEmail" to email,
                "actorRole" to "OWNER",
                "businessId" to business.id,
                "entityType" to "business_subscriptions",
                "entityId" to business.id,
                "action" to if (cancelImmediately) "CANCELLED_IMMEDIATELY" else if (cancelAtPeriodEnd) "CANCEL_SCHEDULED" else "AUTO_RENEW_UPDATED",
                "previousValue" to "autoRenew=${sub.autoRenew},cancelAtPeriodEnd=${sub.cancelAtPeriodEnd},status=${sub.status}",
                "newValue" to "autoRenew=$autoRenew,cancelAtPeriodEnd=$cancelAtPeriodEnd,status=$newStatus",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        Unit
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.UPDATE, "business_subscriptions/${business.id}") }

    // --- ATOMIC SERVER-SIDE OCR QUOTA CONSUMPTION WITH IDEMPOTENCY ---

    suspend fun checkAndConsumeOcrQuota(
        business: Business,
        effective: EffectiveEntitlements,
        idempotencyKey: String
    ): Result<UsageQuota> = runCatching {
        val uid = requireUserId()
        val now = System.currentTimeMillis()
        require(business.isSubscriptionActive(now, effective.gracePeriodEndMillis)) {
            "Active subscription or grace period required to run smart receipt extraction."
        }
        val cleanKey = idempotencyKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
            .ifBlank { "ocr_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val recordRef = db.collection("usage_records").document("${business.id}_ocr_$cleanKey")
        val quotaRef = db.collection("usage_quotas").document("${business.id}_current")
        val cycleKey = "cycle_${(effective.cycleEndMillis.takeIf { it > 0L } ?: now)}"

        val updatedQuota = db.runTransaction { tx ->
            val recSnap = tx.get(recordRef)
            val quotaSnap = tx.get(quotaRef)
            val currentQuota = if (quotaSnap.exists()) {
                quotaSnap.toObject(UsageQuota::class.java) ?: UsageQuota()
            } else {
                UsageQuota(
                    businessId = business.id,
                    ownerUid = business.ownerUid,
                    memberUids = business.memberUids,
                    cycleKey = cycleKey,
                    cycleStartMillis = business.subscriptionActivatedMillis,
                    cycleEndMillis = effective.cycleEndMillis,
                    receiptsAllocated = effective.maxReceiptsPerCycle,
                    ocrAllocated = effective.maxOcrPerCycle
                )
            }

            if (recSnap.exists()) {
                // Idempotent replay — never deduct twice
                return@runTransaction currentQuota
            }

            if (effective.maxOcrPerCycle >= 0 && currentQuota.ocrUsed >= effective.maxOcrPerCycle) {
                throw IllegalStateException(
                    "Monthly OCR allowance reached (${currentQuota.ocrUsed}/${effective.maxOcrPerCycle}). Upgrade your plan or enter details manually."
                )
            }

            val newOcrUsed = currentQuota.ocrUsed + 1
            if (quotaSnap.exists()) {
                tx.update(
                    quotaRef,
                    mapOf(
                        "ocrUsed" to newOcrUsed,
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
                        "cycleKey" to currentQuota.cycleKey,
                        "cycleStartMillis" to currentQuota.cycleStartMillis,
                        "cycleEndMillis" to currentQuota.cycleEndMillis,
                        "receiptsAllocated" to currentQuota.receiptsAllocated,
                        "receiptsUsed" to 0,
                        "rolloverReceipts" to 0,
                        "overageReceiptsUsed" to 0,
                        "ocrAllocated" to currentQuota.ocrAllocated,
                        "ocrUsed" to newOcrUsed,
                        "warningsSentPct" to emptyList<Int>(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            tx.set(
                recordRef,
                mapOf(
                    "businessId" to business.id,
                    "ownerUid" to business.ownerUid,
                    "memberUids" to business.memberUids,
                    "usageType" to UsageType.OCR_EXTRACTION.name,
                    "quantity" to 1,
                    "bytesDelta" to 0L,
                    "idempotencyKey" to cleanKey,
                    "resourceId" to cleanKey,
                    "cycleKey" to currentQuota.cycleKey,
                    "actorUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            currentQuota.copy(ocrUsed = newOcrUsed)
        }.await()

        updatedQuota
    }.onFailure { if (it !is CancellationException) handleFirestoreError(it, OperationType.WRITE, "usage_quotas/${business.id}_current") }

    // --- HELPER: FULL ACTIVATION / RENEWAL / UPGRADE / DOWNGRADE ON VERIFIED PAYMENT ---

    suspend fun applyVerifiedSubscriptionPayment(
        payment: SubscriptionPayment,
        plan: SubscriptionPlan,
        entitlement: PlanEntitlement,
        gracePeriodDays: Int,
        rolloverDefault: Boolean,
        actorUid: String,
        actorEmail: String,
        paystackMetadata: Map<String, Any>? = null
    ): String {
        val ctx = loadActivationContext(payment.businessId)
        val timing = computeActivationCycle(ctx, payment, plan, entitlement, gracePeriodDays, rolloverDefault)

        val batch = db.batch()
        val paymentUpdate = mutableMapOf<String, Any>(
            "planId" to plan.planId,
            "planName" to plan.name,
            "amountMinor" to timing.paidMinor,
            "paymentStatus" to "VERIFIED",
            "verifiedByUid" to actorUid,
            "verifiedByEmail" to actorEmail,
            "verifiedAtMillis" to timing.now,
            "cycleStartMillis" to timing.cycleStart,
            "cycleEndMillis" to timing.cycleEnd,
            "invoiceId" to timing.invoiceId,
            "rejectionReason" to "",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (paystackMetadata != null) {
            paymentUpdate.putAll(paystackMetadata)
        }
        batch.update(
            db.collection("subscription_payments").document(payment.id),
            paymentUpdate
        )

        appendActivationBatchWrites(
            batch = batch,
            ctx = ctx,
            timing = timing,
            payment = payment,
            plan = plan,
            entitlement = entitlement,
            actorUid = actorUid,
            actorEmail = actorEmail,
            actorRole = "SUPER_ADMIN"
        )

        batch.commit().await()
        return timing.invoiceId
    }

    suspend fun createAndActivatePaystackPayment(
        payment: SubscriptionPayment,
        plan: SubscriptionPlan,
        entitlement: PlanEntitlement,
        gracePeriodDays: Int,
        rolloverDefault: Boolean,
        actorUid: String,
        actorEmail: String,
        mailNotificationPayload: Map<String, Any>? = null
    ): String {
        val ctx = loadActivationContext(payment.businessId)
        val timing = computeActivationCycle(ctx, payment, plan, entitlement, gracePeriodDays, rolloverDefault)
        val paidAt = payment.paidAtMillis.takeIf { it > 0L } ?: timing.now
        val ref = payment.paystackReference.ifBlank { payment.txRef }
        val bizName = ctx.business.tradingName.ifBlank { ctx.business.registeredName }

        val batch = db.batch()
        batch.set(
            db.collection("subscription_payments").document(payment.id),
            mapOf(
                "businessId" to ctx.business.id,
                "businessName" to bizName,
                "ownerUid" to ctx.business.ownerUid,
                "ownerName" to ctx.business.ownerName,
                "ownerEmail" to ctx.business.ownerEmail,
                "ownerPhone" to ctx.business.ownerPhone.ifBlank { ctx.business.phone },
                "planId" to plan.planId,
                "planCode" to plan.planCode,
                "planName" to plan.name,
                "amountNgn" to (timing.paidMinor / 100.0),
                "amountMinor" to timing.paidMinor,
                "prorationCreditMinor" to payment.prorationCreditMinor,
                "currency" to plan.currency.ifBlank { "NGN" },
                "txRef" to ref,
                "idempotencyKey" to payment.idempotencyKey.ifBlank { ref },
                "flwTransactionId" to payment.paystackTransactionId,
                "paymentMethod" to "PAYSTACK",
                "paystackReference" to ref,
                "paystackTransactionId" to payment.paystackTransactionId,
                "paystackChannel" to payment.paystackChannel,
                "paystackBank" to payment.paystackBank,
                "paystackCardSummary" to payment.paystackCardSummary,
                "paystackCustomerEmail" to payment.paystackCustomerEmail.ifBlank { actorEmail },
                "paystackGatewayResponse" to payment.paystackGatewayResponse.ifBlank { "Payment confirmed via Paystack" },
                "paystackPaymentUrl" to payment.paystackPaymentUrl,
                "verificationSource" to payment.verificationSource.ifBlank { "CHECKOUT_CONFIRMED" },
                "paidAtMillis" to paidAt,
                "bankAccountName" to "",
                "bankAccountNumber" to "",
                "bankName" to "",
                "senderName" to ctx.business.ownerName,
                "senderBank" to payment.paystackBank,
                "transferNote" to "Paystack ${payment.paystackChannel.ifBlank { "checkout" }}",
                "receiptImageBase64" to "",
                "receiptFileName" to "",
                "receiptMimeType" to "",
                "paymentStatus" to "VERIFIED",
                "verifiedByUid" to actorUid,
                "verifiedByEmail" to actorEmail,
                "verifiedAtMillis" to timing.now,
                "cycleStartMillis" to timing.cycleStart,
                "cycleEndMillis" to timing.cycleEnd,
                "invoiceId" to timing.invoiceId,
                "rejectionReason" to "",
                "emailNotificationStatus" to "QUEUED",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        appendActivationBatchWrites(
            batch = batch,
            ctx = ctx,
            timing = timing,
            payment = payment.copy(txRef = ref, paystackReference = ref, paymentMethod = "PAYSTACK"),
            plan = plan,
            entitlement = entitlement,
            actorUid = actorUid,
            actorEmail = actorEmail,
            actorRole = "OWNER"
        )

        if (mailNotificationPayload != null) {
            batch.set(
                db.collection("mail_notifications").document(payment.id),
                mailNotificationPayload
            )
        }

        batch.commit().await()
        return timing.invoiceId
    }

    private data class ActivationContext(
        val business: Business,
        val existingSub: BusinessSubscription?,
        val existingQuota: UsageQuota?,
        val currentStorage: StorageUsage?,
        val custom: CustomBusinessEntitlement?
    )

    private data class ActivationCycleTiming(
        val now: Long,
        val changeType: PlanChangeType,
        val cycleStart: Long,
        val cycleEnd: Long,
        val graceEnd: Long,
        val rolloverReceipts: Int,
        val effectiveMaxReceipts: Int,
        val effectiveMaxOcr: Int,
        val effectiveStorageLimit: Long,
        val invoiceId: String,
        val invoiceNumber: String,
        val paidMinor: Long,
        val cycleKey: String
    )

    private suspend fun loadActivationContext(businessId: String): ActivationContext {
        val bizSnap = db.collection("businesses").document(businessId).get().await()
        val business = bizSnap.toObject(Business::class.java)
            ?: throw IllegalStateException("Business workspace not found for payment.")
        val subSnap = db.collection("business_subscriptions").document(business.id).get().await()
        val existingSub = if (subSnap.exists()) subSnap.toObject(BusinessSubscription::class.java) else null
        val quotaSnap = db.collection("usage_quotas").document("${business.id}_current").get().await()
        val existingQuota = if (quotaSnap.exists()) quotaSnap.toObject(UsageQuota::class.java) else null
        val storageSnap = db.collection("storage_usage").document(business.id).get().await()
        val currentStorage = if (storageSnap.exists()) storageSnap.toObject(StorageUsage::class.java) else null
        val customSnap = db.collection("custom_business_entitlements").document(business.id).get().await()
        val custom = if (customSnap.exists()) customSnap.toObject(CustomBusinessEntitlement::class.java) else null
        return ActivationContext(business, existingSub, existingQuota, currentStorage, custom)
    }

    private fun computeActivationCycle(
        ctx: ActivationContext,
        payment: SubscriptionPayment,
        plan: SubscriptionPlan,
        entitlement: PlanEntitlement,
        gracePeriodDays: Int,
        rolloverDefault: Boolean
    ): ActivationCycleTiming {
        val existingSub = ctx.existingSub
        val existingQuota = ctx.existingQuota
        val custom = ctx.custom
        val now = System.currentTimeMillis()
        val freq = plan.frequencyEnum()

        val changeType = when {
            existingSub == null ||
                existingSub.planId.isBlank() ||
                existingSub.planId == BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID ||
                existingSub.agreedPriceMinor <= 0L -> PlanChangeType.NEW
            existingSub.planId == plan.planId && existingSub.status in listOf(
                SubscriptionStatus.EXPIRED.name,
                SubscriptionStatus.CANCELLED.name,
                SubscriptionStatus.PAST_DUE.name
            ) -> PlanChangeType.REACTIVATION
            existingSub.planId == plan.planId -> PlanChangeType.RENEWAL
            plan.priceMinor >= existingSub.agreedPriceMinor -> PlanChangeType.UPGRADE
            else -> PlanChangeType.DOWNGRADE
        }

        val cycleStart = if (changeType == PlanChangeType.RENEWAL &&
            existingSub != null &&
            existingSub.cycleEndMillis > now
        ) {
            existingSub.cycleEndMillis
        } else {
            now
        }
        val cycleEnd = BillingCalculator.calculateCycleEndMillis(cycleStart, freq, plan.intervalDays)
        val graceEnd = cycleEnd + max(0, gracePeriodDays).toLong() * 24L * 60L * 60L * 1000L

        val rolloverEnabled = custom?.overrideRolloverEnabled ?: entitlement.rolloverEnabled || rolloverDefault
        val rolloverReceipts = if (rolloverEnabled && existingQuota != null && existingQuota.receiptsAllocated >= 0) {
            existingQuota.receiptsRemaining.coerceAtLeast(0)
        } else 0

        val effectiveMaxReceipts = custom?.overrideReceiptsPerCycle ?: entitlement.maxReceiptsPerCycle
        val effectiveMaxOcr = custom?.overrideMaxOcr ?: entitlement.maxOcrPerCycle
        val effectiveStorageLimit = custom?.overrideStorageLimitBytes ?: entitlement.storageLimitBytes

        val cal = Calendar.getInstance(TimeZone.getTimeZone("Africa/Lagos")).apply { timeInMillis = now }
        val invoiceId = payment.invoiceId.ifBlank { "inv_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val invoiceNumber = "RNW-INV-${cal.get(Calendar.YEAR)}-${invoiceId.takeLast(6).uppercase(Locale.US)}"
        val paidMinor = payment.effectiveAmountMinor().takeIf { it > 0L } ?: plan.priceMinor
        val cycleKey = "cycle_$cycleEnd"

        return ActivationCycleTiming(
            now = now,
            changeType = changeType,
            cycleStart = cycleStart,
            cycleEnd = cycleEnd,
            graceEnd = graceEnd,
            rolloverReceipts = rolloverReceipts,
            effectiveMaxReceipts = effectiveMaxReceipts,
            effectiveMaxOcr = effectiveMaxOcr,
            effectiveStorageLimit = effectiveStorageLimit,
            invoiceId = invoiceId,
            invoiceNumber = invoiceNumber,
            paidMinor = paidMinor,
            cycleKey = cycleKey
        )
    }

    private fun appendActivationBatchWrites(
        batch: com.google.firebase.firestore.WriteBatch,
        ctx: ActivationContext,
        timing: ActivationCycleTiming,
        payment: SubscriptionPayment,
        plan: SubscriptionPlan,
        entitlement: PlanEntitlement,
        actorUid: String,
        actorEmail: String,
        actorRole: String
    ) {
        val business = ctx.business
        val existingSub = ctx.existingSub
        val currentStorage = ctx.currentStorage
        val bizName = business.tradingName.ifBlank { business.registeredName }
        val refDisplay = payment.effectiveReference()

        batch.update(
            db.collection("businesses").document(business.id),
            mapOf(
                "subscriptionStatus" to SubscriptionStatus.ACTIVE.name,
                "subscriptionPlanCode" to plan.planCode,
                "subscriptionPlanId" to plan.planId,
                "subscriptionActivatedMillis" to timing.now,
                "subscriptionExpiryMillis" to timing.cycleEnd,
                "storageLimitBytes" to timing.effectiveStorageLimit,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        batch.set(
            db.collection("business_subscriptions").document(business.id),
            mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "planId" to plan.planId,
                "planCode" to plan.planCode,
                "planNameSnapshot" to plan.name,
                "agreedPriceMinor" to plan.priceMinor,
                "agreedCurrency" to plan.currency,
                "agreedBillingFrequency" to plan.billingFrequency,
                "agreedIntervalDays" to plan.intervalDays,
                "agreedEntitlementsSnapshot" to entitlement.toSnapshotMap(),
                "status" to SubscriptionStatus.ACTIVE.name,
                "cycleStartMillis" to timing.cycleStart,
                "cycleEndMillis" to timing.cycleEnd,
                "gracePeriodEndMillis" to timing.graceEnd,
                "trialEndMillis" to 0L,
                "autoRenew" to (existingSub?.autoRenew ?: true),
                "cancelAtPeriodEnd" to false,
                "cancelledAtMillis" to 0L,
                "pendingUpgradePlanId" to "",
                "creditBalanceMinor" to 0L,
                "lastPaymentId" to payment.id,
                "lastInvoiceId" to timing.invoiceId,
                "createdAt" to (existingSub?.createdAt ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        batch.set(
            db.collection("subscription_invoices").document(timing.invoiceId),
            mapOf(
                "invoiceNumber" to timing.invoiceNumber,
                "businessId" to business.id,
                "businessName" to bizName,
                "ownerUid" to business.ownerUid,
                "ownerEmail" to business.ownerEmail,
                "subscriptionId" to business.id,
                "paymentId" to payment.id,
                "planId" to plan.planId,
                "planCode" to plan.planCode,
                "planNameSnapshot" to plan.name,
                "billingFrequencySnapshot" to plan.billingFrequency,
                "subtotalMinor" to plan.priceMinor,
                "prorationCreditMinor" to payment.prorationCreditMinor,
                "taxMinor" to 0L,
                "totalMinor" to timing.paidMinor,
                "currency" to plan.currency,
                "status" to "PAID",
                "cycleStartMillis" to timing.cycleStart,
                "cycleEndMillis" to timing.cycleEnd,
                "issuedAtMillis" to timing.now,
                "paidAtMillis" to timing.now,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        batch.set(
            db.collection("usage_quotas").document("${business.id}_current"),
            mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "cycleKey" to timing.cycleKey,
                "cycleStartMillis" to timing.cycleStart,
                "cycleEndMillis" to timing.cycleEnd,
                "receiptsAllocated" to timing.effectiveMaxReceipts,
                "receiptsUsed" to 0,
                "rolloverReceipts" to timing.rolloverReceipts,
                "overageReceiptsUsed" to 0,
                "ocrAllocated" to timing.effectiveMaxOcr,
                "ocrUsed" to 0,
                "warningsSentPct" to emptyList<Int>(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        batch.set(
            db.collection("storage_usage").document(business.id),
            mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "allocatedBytes" to timing.effectiveStorageLimit,
                "usedBytes" to (currentStorage?.usedBytes ?: business.storageUsedBytes),
                "bankReceiptsBytes" to (currentStorage?.bankReceiptsBytes ?: 0L),
                "generatedReceiptsBytes" to (currentStorage?.generatedReceiptsBytes ?: 0L),
                "brandingBytes" to (currentStorage?.brandingBytes ?: 0L),
                "otherDocsBytes" to (currentStorage?.otherDocsBytes ?: 0L),
                "limitBehavior" to entitlement.storageOverLimitAction,
                "warningThresholdsSent" to emptyList<Int>(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        batch.set(
            db.collection("plan_change_history").document("chg_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "businessId" to business.id,
                "businessName" to bizName,
                "ownerUid" to business.ownerUid,
                "fromPlanId" to (existingSub?.planId ?: ""),
                "fromPlanName" to (existingSub?.planNameSnapshot ?: ""),
                "toPlanId" to plan.planId,
                "toPlanName" to plan.name,
                "changeType" to timing.changeType.name,
                "prorationCreditMinor" to payment.prorationCreditMinor,
                "chargedMinor" to timing.paidMinor,
                "currency" to plan.currency,
                "actorUid" to actorUid,
                "actorEmail" to actorEmail,
                "notes" to "${timing.changeType.label} verified (${payment.paymentMethod}, Ref $refDisplay, Invoice ${timing.invoiceNumber}).",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        batch.set(
            db.collection("subscription_events").document("evt_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "actorUid" to actorUid,
                "actorEmail" to actorEmail,
                "actorRole" to actorRole,
                "businessId" to business.id,
                "entityType" to "business_subscriptions",
                "entityId" to business.id,
                "action" to "PAYMENT_VERIFIED_${timing.changeType.name}",
                "previousValue" to "plan=${existingSub?.planNameSnapshot.orEmpty()},status=${existingSub?.status.orEmpty()}",
                "newValue" to "plan=${plan.name},invoice=${timing.invoiceNumber},paidMinor=${timing.paidMinor},method=${payment.paymentMethod}",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        batch.set(
            db.collection("businesses").document(business.id)
                .collection("notifications").document("ntf_${UUID.randomUUID().toString().take(12)}"),
            mapOf(
                "businessId" to business.id,
                "ownerUid" to business.ownerUid,
                "memberUids" to business.memberUids,
                "title" to "Subscription Verified & Active (${plan.name})",
                "body" to "Payment Ref $refDisplay confirmed! Invoice ${timing.invoiceNumber} issued. Quota: ${if (timing.effectiveMaxReceipts < 0) "Unlimited" else "${timing.effectiveMaxReceipts + timing.rolloverReceipts} receipts"}.",
                "category" to "SUBSCRIPTION",
                "read" to false,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
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
}
