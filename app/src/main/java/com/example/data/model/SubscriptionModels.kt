package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToLong

enum class BillingFrequency(val defaultDays: Int, val label: String) {
    MONTHLY(30, "Monthly"),
    QUARTERLY(90, "Quarterly"),
    BIANNUAL(180, "Biannual"),
    ANNUAL(365, "Annually"),
    CUSTOM(30, "Custom")
}

enum class PlanStatus(val label: String) {
    DRAFT("Draft"),
    ACTIVE("Active"),
    INACTIVE("Inactive"),
    ARCHIVED("Archived")
}

enum class StorageOverLimitAction(val label: String) {
    BLOCK_NEW_UPLOADS("Block New Uploads"),
    WARN_ONLY("Warn Only")
}

enum class PlanChangeType(val label: String) {
    NEW("New Subscription"),
    RENEWAL("Renewal"),
    UPGRADE("Upgrade"),
    DOWNGRADE("Downgrade"),
    CANCELLATION("Cancellation"),
    REACTIVATION("Reactivation"),
    SUSPENSION("Suspension"),
    RESTORATION("Restoration"),
    EXPIRY("Expiry"),
    CUSTOM_OVERRIDE("Custom Override")
}

enum class UsageType {
    RECEIPT_GENERATED,
    OCR_EXTRACTION,
    STORAGE_DELTA,
    STAFF_INVITE,
    CUSTOMER_CREATE
}

@IgnoreExtraProperties
data class SubscriptionPlan(
    @DocumentId val id: String = "",
    val planId: String = "",
    val planCode: String = "",
    val name: String = "",
    val description: String = "",
    val priceMinor: Long = 0L,
    val currency: String = "NGN",
    val billingFrequency: String = BillingFrequency.MONTHLY.name,
    val intervalDays: Int = 30,
    val trialDays: Int = 0,
    val visible: Boolean = true,
    val status: String = PlanStatus.ACTIVE.name,
    val sortOrder: Int = 0,
    val paystackPaymentUrl: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun frequencyEnum(): BillingFrequency =
        runCatching { BillingFrequency.valueOf(billingFrequency) }.getOrDefault(BillingFrequency.MONTHLY)

    fun statusEnum(): PlanStatus =
        runCatching { PlanStatus.valueOf(status) }.getOrDefault(PlanStatus.ACTIVE)

    fun priceNgn(): Double = priceMinor / 100.0

    fun formattedPrice(): String = BillingCalculator.formatMinorCurrency(priceMinor, currency)
}

@IgnoreExtraProperties
data class PlanFeature(
    @DocumentId val id: String = "",
    val planId: String = "",
    val featureKey: String = "",
    val label: String = "",
    val valueText: String = "",
    val included: Boolean = true,
    val sortOrder: Int = 0,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PlanEntitlement(
    @DocumentId val id: String = "",
    val planId: String = "",
    val maxReceiptsPerCycle: Int = 300,
    val rolloverEnabled: Boolean = false,
    val overageReceiptPriceMinor: Long = 0L,
    val storageLimitBytes: Long = 500L * 1024L * 1024L,
    val storageOverLimitAction: String = StorageOverLimitAction.BLOCK_NEW_UPLOADS.name,
    val overageStoragePricePerGbMinor: Long = 0L,
    val maxStaffAccounts: Int = 5,
    val maxOcrPerCycle: Int = 300,
    val maxCustomers: Int = 1000,
    val allowedTemplates: List<String> = ReceiptTemplateType.entries.map { it.name },
    val analyticsEnabled: Boolean = true,
    val reportsExportEnabled: Boolean = true,
    val qrVerificationEnabled: Boolean = true,
    val whatsappSharingEnabled: Boolean = true,
    val emailSharingEnabled: Boolean = true,
    val prioritySupport: Boolean = false,
    val warningThresholds: List<Int> = listOf(50, 75, 90, 100),
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toSnapshotMap(): Map<String, Any> = mapOf(
        "maxReceiptsPerCycle" to maxReceiptsPerCycle,
        "rolloverEnabled" to rolloverEnabled,
        "overageReceiptPriceMinor" to overageReceiptPriceMinor,
        "storageLimitBytes" to storageLimitBytes,
        "storageOverLimitAction" to storageOverLimitAction,
        "overageStoragePricePerGbMinor" to overageStoragePricePerGbMinor,
        "maxStaffAccounts" to maxStaffAccounts,
        "maxOcrPerCycle" to maxOcrPerCycle,
        "maxCustomers" to maxCustomers,
        "allowedTemplates" to allowedTemplates,
        "analyticsEnabled" to analyticsEnabled,
        "reportsExportEnabled" to reportsExportEnabled,
        "qrVerificationEnabled" to qrVerificationEnabled,
        "whatsappSharingEnabled" to whatsappSharingEnabled,
        "emailSharingEnabled" to emailSharingEnabled,
        "prioritySupport" to prioritySupport,
        "warningThresholds" to warningThresholds
    )

    companion object {
        fun fromSnapshotMap(planId: String, raw: Map<String, Any?>): PlanEntitlement {
            val templates = (raw["allowedTemplates"] as? List<*>)?.filterIsInstance<String>()
                ?.takeIf { it.isNotEmpty() }
                ?: ReceiptTemplateType.entries.map { it.name }
            val thresholds = (raw["warningThresholds"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toInt() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(50, 75, 90, 100)
            return PlanEntitlement(
                planId = planId,
                maxReceiptsPerCycle = (raw["maxReceiptsPerCycle"] as? Number)?.toInt() ?: 300,
                rolloverEnabled = raw["rolloverEnabled"] as? Boolean ?: false,
                overageReceiptPriceMinor = (raw["overageReceiptPriceMinor"] as? Number)?.toLong() ?: 0L,
                storageLimitBytes = (raw["storageLimitBytes"] as? Number)?.toLong() ?: (500L * 1024L * 1024L),
                storageOverLimitAction = (raw["storageOverLimitAction"] as? String)
                    ?: StorageOverLimitAction.BLOCK_NEW_UPLOADS.name,
                overageStoragePricePerGbMinor = (raw["overageStoragePricePerGbMinor"] as? Number)?.toLong() ?: 0L,
                maxStaffAccounts = (raw["maxStaffAccounts"] as? Number)?.toInt() ?: 5,
                maxOcrPerCycle = (raw["maxOcrPerCycle"] as? Number)?.toInt() ?: 300,
                maxCustomers = (raw["maxCustomers"] as? Number)?.toInt() ?: 1000,
                allowedTemplates = templates,
                analyticsEnabled = raw["analyticsEnabled"] as? Boolean ?: true,
                reportsExportEnabled = raw["reportsExportEnabled"] as? Boolean ?: true,
                qrVerificationEnabled = raw["qrVerificationEnabled"] as? Boolean ?: true,
                whatsappSharingEnabled = raw["whatsappSharingEnabled"] as? Boolean ?: true,
                emailSharingEnabled = raw["emailSharingEnabled"] as? Boolean ?: true,
                prioritySupport = raw["prioritySupport"] as? Boolean ?: false,
                warningThresholds = thresholds
            )
        }
    }
}

@IgnoreExtraProperties
data class BusinessSubscription(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val planId: String = "",
    val planCode: String = "",
    val planNameSnapshot: String = "",
    val agreedPriceMinor: Long = 0L,
    val agreedCurrency: String = "NGN",
    val agreedBillingFrequency: String = BillingFrequency.MONTHLY.name,
    val agreedIntervalDays: Int = 30,
    val agreedEntitlementsSnapshot: Map<String, Any?> = emptyMap(),
    val status: String = SubscriptionStatus.AWAITING_SUBSCRIPTION.name,
    val cycleStartMillis: Long = 0L,
    val cycleEndMillis: Long = 0L,
    val gracePeriodEndMillis: Long = 0L,
    val trialEndMillis: Long = 0L,
    val autoRenew: Boolean = true,
    val cancelAtPeriodEnd: Boolean = false,
    val cancelledAtMillis: Long = 0L,
    val pendingUpgradePlanId: String = "",
    val creditBalanceMinor: Long = 0L,
    val lastPaymentId: String = "",
    val lastInvoiceId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun snapshotEntitlement(): PlanEntitlement? =
        if (agreedEntitlementsSnapshot.isEmpty()) null
        else PlanEntitlement.fromSnapshotMap(planId, agreedEntitlementsSnapshot)
}

@IgnoreExtraProperties
data class SubscriptionInvoice(
    @DocumentId val id: String = "",
    val invoiceNumber: String = "",
    val businessId: String = "",
    val businessName: String = "",
    val ownerUid: String = "",
    val ownerEmail: String = "",
    val subscriptionId: String = "",
    val paymentId: String = "",
    val planId: String = "",
    val planCode: String = "",
    val planNameSnapshot: String = "",
    val billingFrequencySnapshot: String = "",
    val subtotalMinor: Long = 0L,
    val prorationCreditMinor: Long = 0L,
    val taxMinor: Long = 0L,
    val totalMinor: Long = 0L,
    val currency: String = "NGN",
    val status: String = "PAID",
    val cycleStartMillis: Long = 0L,
    val cycleEndMillis: Long = 0L,
    val issuedAtMillis: Long = 0L,
    val paidAtMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UsageRecord(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val usageType: String = UsageType.RECEIPT_GENERATED.name,
    val quantity: Long = 1L,
    val bytesDelta: Long = 0L,
    val idempotencyKey: String = "",
    val resourceId: String = "",
    val cycleKey: String = "",
    val actorUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UsageQuota(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val cycleKey: String = "",
    val cycleStartMillis: Long = 0L,
    val cycleEndMillis: Long = 0L,
    val receiptsAllocated: Int = 300,
    val receiptsUsed: Int = 0,
    val rolloverReceipts: Int = 0,
    val overageReceiptsUsed: Int = 0,
    val ocrAllocated: Int = 300,
    val ocrUsed: Int = 0,
    val warningsSentPct: List<Int> = emptyList(),
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val isUnlimitedReceipts: Boolean get() = receiptsAllocated < 0
    val isUnlimitedOcr: Boolean get() = ocrAllocated < 0
    val totalReceiptAllowance: Int get() = if (isUnlimitedReceipts) Int.MAX_VALUE else receiptsAllocated + rolloverReceipts
    val receiptsRemaining: Int get() = if (isUnlimitedReceipts) Int.MAX_VALUE else max(0, totalReceiptAllowance - receiptsUsed)
    val receiptUsagePct: Int
        get() = if (isUnlimitedReceipts || totalReceiptAllowance <= 0) 0
        else ((receiptsUsed * 100.0) / totalReceiptAllowance).toInt().coerceAtMost(999)
    val ocrRemaining: Int get() = if (isUnlimitedOcr) Int.MAX_VALUE else max(0, ocrAllocated - ocrUsed)
    val ocrUsagePct: Int
        get() = if (isUnlimitedOcr || ocrAllocated <= 0) 0
        else ((ocrUsed * 100.0) / ocrAllocated).toInt().coerceAtMost(999)
}

@IgnoreExtraProperties
data class StorageUsage(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val allocatedBytes: Long = 500L * 1024L * 1024L,
    val usedBytes: Long = 0L,
    val bankReceiptsBytes: Long = 0L,
    val generatedReceiptsBytes: Long = 0L,
    val brandingBytes: Long = 0L,
    val otherDocsBytes: Long = 0L,
    val limitBehavior: String = StorageOverLimitAction.BLOCK_NEW_UPLOADS.name,
    val warningThresholdsSent: List<Int> = emptyList(),
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val isUnlimited: Boolean get() = allocatedBytes < 0L
    val remainingBytes: Long get() = if (isUnlimited) Long.MAX_VALUE else max(0L, allocatedBytes - usedBytes)
    val usagePct: Int
        get() = if (isUnlimited || allocatedBytes <= 0L) 0
        else ((usedBytes * 100.0) / allocatedBytes).toInt().coerceAtMost(999)
}

@IgnoreExtraProperties
data class SubscriptionEvent(
    @DocumentId val id: String = "",
    val actorUid: String = "",
    val actorEmail: String = "",
    val actorRole: String = "",
    val businessId: String = "",
    val entityType: String = "",
    val entityId: String = "",
    val action: String = "",
    val previousValue: String = "",
    val newValue: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PlanChangeHistory(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val businessName: String = "",
    val ownerUid: String = "",
    val fromPlanId: String = "",
    val fromPlanName: String = "",
    val toPlanId: String = "",
    val toPlanName: String = "",
    val changeType: String = PlanChangeType.NEW.name,
    val prorationCreditMinor: Long = 0L,
    val chargedMinor: Long = 0L,
    val currency: String = "NGN",
    val actorUid: String = "",
    val actorEmail: String = "",
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CustomBusinessEntitlement(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val overrideReceiptsPerCycle: Int? = null,
    val overrideStorageLimitBytes: Long? = null,
    val overrideMaxStaff: Int? = null,
    val overrideMaxOcr: Int? = null,
    val overrideMaxCustomers: Int? = null,
    val overrideRolloverEnabled: Boolean? = null,
    val overrideNotes: String = "",
    val updatedByUid: String = "",
    val updatedByEmail: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class EffectiveEntitlements(
    val planId: String,
    val planCode: String,
    val planName: String,
    val maxReceiptsPerCycle: Int,
    val rolloverReceipts: Int,
    val receiptsUsed: Int,
    val rolloverEnabled: Boolean,
    val overageReceiptPriceMinor: Long,
    val storageLimitBytes: Long,
    val storageUsedBytes: Long,
    val storageOverLimitAction: StorageOverLimitAction,
    val maxStaffAccounts: Int,
    val maxOcrPerCycle: Int,
    val ocrUsed: Int,
    val maxCustomers: Int,
    val allowedTemplates: List<ReceiptTemplateType>,
    val analyticsEnabled: Boolean,
    val reportsExportEnabled: Boolean,
    val qrVerificationEnabled: Boolean,
    val whatsappSharingEnabled: Boolean,
    val emailSharingEnabled: Boolean,
    val prioritySupport: Boolean,
    val warningThresholds: List<Int>,
    val cycleEndMillis: Long,
    val gracePeriodEndMillis: Long,
    val hasCustomOverride: Boolean,
    val overLimitWarnings: List<String> = emptyList()
) {
    val totalReceiptAllowance: Int
        get() = if (maxReceiptsPerCycle < 0) Int.MAX_VALUE else maxReceiptsPerCycle + rolloverReceipts
    val receiptsRemaining: Int
        get() = if (maxReceiptsPerCycle < 0) Int.MAX_VALUE else max(0, totalReceiptAllowance - receiptsUsed)
    val isReceiptQuotaExceeded: Boolean
        get() = maxReceiptsPerCycle >= 0 && receiptsUsed >= totalReceiptAllowance
    val isOcrQuotaExceeded: Boolean
        get() = maxOcrPerCycle >= 0 && ocrUsed >= maxOcrPerCycle
    val isStorageLimitExceeded: Boolean
        get() = storageLimitBytes >= 0L && storageUsedBytes >= storageLimitBytes
    val shouldBlockNewUploads: Boolean
        get() = isStorageLimitExceeded && storageOverLimitAction == StorageOverLimitAction.BLOCK_NEW_UPLOADS
}

data class ProrationQuote(
    val changeType: PlanChangeType,
    val targetPlanPriceMinor: Long,
    val prorationCreditMinor: Long,
    val netPayableMinor: Long,
    val remainingCreditMinor: Long,
    val explanation: String
)

data class MrrArrSummary(
    val mrrMinor: Long,
    val arrMinor: Long,
    val activeSubscribers: Int,
    val graceSubscribers: Int,
    val expiringSoonSubscribers: Int,
    val expiredSubscribers: Int,
    val subscriberCountByPlan: Map<String, Int>
)

object BillingCalculator {
    const val LAUNCH_MONTHLY_PLAN_ID = "plan_monthly_1500"
    const val LAUNCH_FREE_TRIAL_PLAN_ID = "plan_trial_90d"
    const val FREE_TRIAL_DAYS = 90
    const val FREE_TRIAL_MILLIS = 90L * 24L * 60L * 60L * 1000L
    const val LAUNCH_ANNUAL_PLAN_ID = "plan_annual_18000"
    const val LAUNCH_MONTHLY_PRICE_MINOR = 150_000L // ₦1,500
    const val LAUNCH_ANNUAL_PRICE_MINOR = 1_800_000L // ₦18,000

    fun freeTrialEntitlement(): PlanEntitlement = PlanEntitlement(
        id = LAUNCH_FREE_TRIAL_PLAN_ID,
        planId = LAUNCH_FREE_TRIAL_PLAN_ID,
        maxReceiptsPerCycle = 300,
        rolloverEnabled = false,
        overageReceiptPriceMinor = 0L,
        storageLimitBytes = 500L * 1024L * 1024L,
        storageOverLimitAction = StorageOverLimitAction.BLOCK_NEW_UPLOADS.name,
        overageStoragePricePerGbMinor = 0L,
        maxStaffAccounts = 5,
        maxOcrPerCycle = 300,
        maxCustomers = 1000,
        allowedTemplates = ReceiptTemplateType.entries.map { it.name },
        analyticsEnabled = true,
        reportsExportEnabled = true,
        qrVerificationEnabled = true,
        whatsappSharingEnabled = true,
        emailSharingEnabled = true,
        prioritySupport = false,
        warningThresholds = listOf(50, 75, 90, 100)
    )

    fun formatMinorCurrency(amountMinor: Long, currency: String = "NGN"): String {
        val major = amountMinor / 100.0
        val symbol = if (currency.equals("NGN", ignoreCase = true)) "₦" else "$currency "
        return if (amountMinor % 100L == 0L) {
            "$symbol${"%,.0f".format(Locale.US, major)}"
        } else {
            "$symbol${"%,.2f".format(Locale.US, major)}"
        }
    }

    fun calculateCycleEndMillis(
        startMillis: Long,
        frequency: BillingFrequency,
        intervalDays: Int
    ): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Africa/Lagos")).apply {
            timeInMillis = startMillis
        }
        when (frequency) {
            BillingFrequency.MONTHLY -> cal.add(Calendar.MONTH, 1)
            BillingFrequency.QUARTERLY -> cal.add(Calendar.MONTH, 3)
            BillingFrequency.BIANNUAL -> cal.add(Calendar.MONTH, 6)
            BillingFrequency.ANNUAL -> cal.add(Calendar.YEAR, 1)
            BillingFrequency.CUSTOM -> cal.add(Calendar.DAY_OF_YEAR, max(1, intervalDays))
        }
        return cal.timeInMillis
    }

    fun calculateProrationQuote(
        currentSub: BusinessSubscription?,
        targetPlan: SubscriptionPlan,
        nowMillis: Long,
        prorationEnabled: Boolean
    ): ProrationQuote {
        val targetPrice = targetPlan.priceMinor
        if (currentSub == null ||
            currentSub.planId.isBlank() ||
            currentSub.planId == LAUNCH_FREE_TRIAL_PLAN_ID ||
            currentSub.agreedPriceMinor <= 0L ||
            currentSub.cycleEndMillis <= nowMillis ||
            currentSub.status !in setOf(
                SubscriptionStatus.ACTIVE.name,
                SubscriptionStatus.EXPIRING_SOON.name,
                SubscriptionStatus.GRACE_PERIOD.name
            )
        ) {
            return ProrationQuote(
                changeType = PlanChangeType.NEW,
                targetPlanPriceMinor = targetPrice,
                prorationCreditMinor = 0L,
                netPayableMinor = targetPrice,
                remainingCreditMinor = 0L,
                explanation = "New ${targetPlan.name} billing cycle starts on verification."
            )
        }

        if (currentSub.planId == targetPlan.planId) {
            return ProrationQuote(
                changeType = PlanChangeType.RENEWAL,
                targetPlanPriceMinor = targetPrice,
                prorationCreditMinor = 0L,
                netPayableMinor = targetPrice,
                remainingCreditMinor = 0L,
                explanation = "Extends your current ${targetPlan.name} subscription cycle."
            )
        }

        val currentDailyRate = if (currentSub.agreedIntervalDays > 0) {
            currentSub.agreedPriceMinor.toDouble() / currentSub.agreedIntervalDays
        } else 0.0
        val targetDailyRate = if (targetPlan.intervalDays > 0) {
            targetPlan.priceMinor.toDouble() / targetPlan.intervalDays
        } else 0.0

        val isUpgrade = targetPrice > currentSub.agreedPriceMinor || targetDailyRate > currentDailyRate
        val changeType = if (isUpgrade) PlanChangeType.UPGRADE else PlanChangeType.DOWNGRADE

        if (!prorationEnabled || currentSub.agreedPriceMinor <= 0L) {
            return ProrationQuote(
                changeType = changeType,
                targetPlanPriceMinor = targetPrice,
                prorationCreditMinor = 0L,
                netPayableMinor = targetPrice,
                remainingCreditMinor = 0L,
                explanation = "${changeType.label} to ${targetPlan.name} without proration adjustment."
            )
        }

        val totalWindow = max(1L, currentSub.cycleEndMillis - currentSub.cycleStartMillis)
        val remainingWindow = max(0L, currentSub.cycleEndMillis - nowMillis)
        val unusedCreditMinor = ((currentSub.agreedPriceMinor.toDouble() * remainingWindow.toDouble()) / totalWindow.toDouble())
            .roundToLong()
            .coerceIn(0L, currentSub.agreedPriceMinor)

        val totalCredit = unusedCreditMinor + currentSub.creditBalanceMinor
        val appliedCredit = minOf(targetPrice, totalCredit)
        val netPayable = max(0L, targetPrice - appliedCredit)
        val leftoverCredit = max(0L, totalCredit - appliedCredit)

        return ProrationQuote(
            changeType = changeType,
            targetPlanPriceMinor = targetPrice,
            prorationCreditMinor = appliedCredit,
            netPayableMinor = netPayable,
            remainingCreditMinor = leftoverCredit,
            explanation = "Includes ${formatMinorCurrency(appliedCredit, targetPlan.currency)} prorated credit from unused ${currentSub.planNameSnapshot} days."
        )
    }

    fun evaluateNewlyTriggeredThresholds(
        used: Long,
        allocated: Long,
        alreadySent: List<Int>,
        configuredThresholds: List<Int> = listOf(50, 75, 90, 100)
    ): List<Int> {
        if (allocated <= 0L) return emptyList()
        val pct = ((used * 100L) / allocated).toInt()
        return configuredThresholds.filter { threshold ->
            pct >= threshold && threshold !in alreadySent
        }.sorted()
    }

    fun resolveEffectiveEntitlements(
        business: Business?,
        subscription: BusinessSubscription?,
        plans: List<SubscriptionPlan>,
        entitlements: List<PlanEntitlement>,
        quota: UsageQuota?,
        storage: StorageUsage?,
        custom: CustomBusinessEntitlement?,
        currentStaffCount: Int = 0,
        currentCustomerCount: Int = 0
    ): EffectiveEntitlements {
        val resolvedPlanId = subscription?.planId?.ifBlank { null }
            ?: business?.subscriptionPlanId?.ifBlank { null }
            ?: plans.firstOrNull { it.planCode == business?.subscriptionPlanCode }?.planId
            ?: ""

        val matchedPlan = plans.firstOrNull { it.planId == resolvedPlanId || it.planCode == business?.subscriptionPlanCode }
        val livePlanEnt = entitlements.firstOrNull { it.planId == resolvedPlanId }
        val snapshotEnt = subscription?.snapshotEntitlement()

        // Prefer agreed snapshot for an active cycle so editing a plan doesn't alter active subscribers;
        // fall back to live plan entitlement if no snapshot exists yet.
        val baseEnt = snapshotEnt ?: livePlanEnt
            ?: (if (resolvedPlanId == LAUNCH_FREE_TRIAL_PLAN_ID) freeTrialEntitlement() else null)
            ?: defaultLaunchEntitlements().firstOrNull { it.planId == resolvedPlanId }
            ?: PlanEntitlement(planId = resolvedPlanId)

        val maxReceipts = custom?.overrideReceiptsPerCycle ?: quota?.receiptsAllocated ?: baseEnt.maxReceiptsPerCycle
        val rollover = quota?.rolloverReceipts ?: 0
        val receiptsUsed = quota?.receiptsUsed ?: 0
        val storageLimit = custom?.overrideStorageLimitBytes ?: storage?.allocatedBytes ?: baseEnt.storageLimitBytes
        val storageUsed = storage?.usedBytes ?: business?.storageUsedBytes ?: 0L
        val maxStaff = custom?.overrideMaxStaff ?: baseEnt.maxStaffAccounts
        val maxOcr = custom?.overrideMaxOcr ?: quota?.ocrAllocated ?: baseEnt.maxOcrPerCycle
        val ocrUsed = quota?.ocrUsed ?: 0
        val maxCustomers = custom?.overrideMaxCustomers ?: baseEnt.maxCustomers
        val rolloverEnabled = custom?.overrideRolloverEnabled ?: baseEnt.rolloverEnabled

        val legacyDefaultNames = setOf(
            ReceiptTemplateType.STANDARD.name,
            ReceiptTemplateType.PREMIUM_CORPORATE.name,
            ReceiptTemplateType.COMPACT.name
        )
        val parsedStored = baseEnt.allowedTemplates.mapNotNull { name ->
            runCatching { ReceiptTemplateType.valueOf(name) }.getOrNull()
        }
        val templates = when {
            parsedStored.isEmpty() -> ReceiptTemplateType.entries
            baseEnt.allowedTemplates.toSet() == legacyDefaultNames -> ReceiptTemplateType.entries
            else -> parsedStored
        }

        val storageAction = runCatching { StorageOverLimitAction.valueOf(storage?.limitBehavior ?: baseEnt.storageOverLimitAction) }
            .getOrDefault(StorageOverLimitAction.BLOCK_NEW_UPLOADS)

        // Check for non-destructive over-limit warnings (e.g., after a downgrade)
        val warnings = mutableListOf<String>()
        if (maxReceipts >= 0 && receiptsUsed > maxReceipts + rollover) {
            warnings += "Receipt usage ($receiptsUsed) exceeds current plan allowance (${maxReceipts + rollover}). Existing receipts remain safe; upgrade to issue new ones."
        }
        if (storageLimit >= 0L && storageUsed > storageLimit) {
            warnings += "Storage (${formatBytes(storageUsed)}) exceeds current allowance (${formatBytes(storageLimit)}). No files were deleted; free space or upgrade to upload new documents."
        }
        if (maxStaff >= 0 && currentStaffCount > maxStaff) {
            warnings += "Active staff ($currentStaffCount) exceeds current limit ($maxStaff). Existing staff stay intact, but new invites are paused."
        }
        if (maxCustomers >= 0 && currentCustomerCount > maxCustomers) {
            warnings += "Customer records ($currentCustomerCount) exceed current limit ($maxCustomers). Existing customers stay safe; new customer creation requires an upgrade."
        }

        return EffectiveEntitlements(
            planId = resolvedPlanId,
            planCode = subscription?.planCode?.ifBlank { null } ?: business?.subscriptionPlanCode.orEmpty(),
            planName = subscription?.planNameSnapshot?.ifBlank { null }
                ?: matchedPlan?.name
                ?: (if (resolvedPlanId == LAUNCH_FREE_TRIAL_PLAN_ID) "90-Day Free Trial" else null)
                ?: business?.subscriptionPlanCode.orEmpty().ifBlank { "No Active Plan" },
            maxReceiptsPerCycle = maxReceipts,
            rolloverReceipts = rollover,
            receiptsUsed = receiptsUsed,
            rolloverEnabled = rolloverEnabled,
            overageReceiptPriceMinor = baseEnt.overageReceiptPriceMinor,
            storageLimitBytes = storageLimit,
            storageUsedBytes = storageUsed,
            storageOverLimitAction = storageAction,
            maxStaffAccounts = maxStaff,
            maxOcrPerCycle = maxOcr,
            ocrUsed = ocrUsed,
            maxCustomers = maxCustomers,
            allowedTemplates = templates,
            analyticsEnabled = baseEnt.analyticsEnabled,
            reportsExportEnabled = baseEnt.reportsExportEnabled,
            qrVerificationEnabled = baseEnt.qrVerificationEnabled,
            whatsappSharingEnabled = baseEnt.whatsappSharingEnabled,
            emailSharingEnabled = baseEnt.emailSharingEnabled,
            prioritySupport = baseEnt.prioritySupport,
            warningThresholds = baseEnt.warningThresholds,
            cycleEndMillis = subscription?.cycleEndMillis ?: business?.subscriptionExpiryMillis ?: 0L,
            gracePeriodEndMillis = subscription?.gracePeriodEndMillis ?: 0L,
            hasCustomOverride = custom != null,
            overLimitWarnings = warnings
        )
    }

    fun computeMrrAndArr(
        subscriptions: List<BusinessSubscription>,
        plans: List<SubscriptionPlan>,
        nowMillis: Long = System.currentTimeMillis()
    ): MrrArrSummary {
        var mrrMinor = 0L
        var active = 0
        var grace = 0
        var expiring = 0
        var expired = 0
        val countByPlan = mutableMapOf<String, Int>()
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L

        subscriptions.forEach { sub ->
            val effectiveStatus = when {
                sub.status == SubscriptionStatus.SUSPENDED.name -> SubscriptionStatus.SUSPENDED
                sub.status == SubscriptionStatus.CANCELLED.name -> SubscriptionStatus.CANCELLED
                sub.cycleEndMillis in 1 until nowMillis -> {
                    if (sub.status != SubscriptionStatus.TRIALING.name &&
                        sub.gracePeriodEndMillis > sub.cycleEndMillis &&
                        nowMillis <= sub.gracePeriodEndMillis
                    ) {
                        SubscriptionStatus.GRACE_PERIOD
                    } else SubscriptionStatus.EXPIRED
                }
                sub.status in listOf(SubscriptionStatus.ACTIVE.name, SubscriptionStatus.EXPIRING_SOON.name) &&
                    sub.cycleEndMillis > 0L && (sub.cycleEndMillis - nowMillis) <= sevenDaysMs -> SubscriptionStatus.EXPIRING_SOON
                else -> runCatching { SubscriptionStatus.valueOf(sub.status) }.getOrDefault(SubscriptionStatus.AWAITING_SUBSCRIPTION)
            }

            when (effectiveStatus) {
                SubscriptionStatus.ACTIVE -> if (sub.agreedPriceMinor > 0L) active++
                SubscriptionStatus.EXPIRING_SOON -> {
                    if (sub.agreedPriceMinor > 0L) active++
                    expiring++
                }
                SubscriptionStatus.GRACE_PERIOD -> grace++
                SubscriptionStatus.EXPIRED, SubscriptionStatus.PAST_DUE -> expired++
                else -> {}
            }

            if (sub.agreedPriceMinor > 0L && effectiveStatus in listOf(
                    SubscriptionStatus.ACTIVE,
                    SubscriptionStatus.EXPIRING_SOON,
                    SubscriptionStatus.GRACE_PERIOD
                )
            ) {
                val planLabel = sub.planNameSnapshot.ifBlank {
                    plans.firstOrNull { it.planId == sub.planId }?.name ?: sub.planCode.ifBlank { "Custom" }
                }
                countByPlan[planLabel] = (countByPlan[planLabel] ?: 0) + 1

                val days = max(1, sub.agreedIntervalDays)
                val monthlyNormalized = when (sub.agreedBillingFrequency) {
                    BillingFrequency.MONTHLY.name -> sub.agreedPriceMinor
                    BillingFrequency.ANNUAL.name -> (sub.agreedPriceMinor / 12L)
                    BillingFrequency.QUARTERLY.name -> (sub.agreedPriceMinor / 3L)
                    BillingFrequency.BIANNUAL.name -> (sub.agreedPriceMinor / 6L)
                    else -> ((sub.agreedPriceMinor * 30.0) / days).roundToLong()
                }
                mrrMinor += monthlyNormalized
            }
        }

        return MrrArrSummary(
            mrrMinor = mrrMinor,
            arrMinor = mrrMinor * 12L,
            activeSubscribers = active,
            graceSubscribers = grace,
            expiringSoonSubscribers = expiring,
            expiredSubscribers = expired,
            subscriberCountByPlan = countByPlan
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Unlimited"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) "${"%.2f".format(Locale.US, mb / 1024.0)} GB"
        else "${"%.1f".format(Locale.US, mb)} MB"
    }

    fun defaultLaunchPlans(): List<SubscriptionPlan> = listOf(
        SubscriptionPlan(
            id = LAUNCH_MONTHLY_PLAN_ID,
            planId = LAUNCH_MONTHLY_PLAN_ID,
            planCode = "MONTHLY",
            name = "Monthly",
            description = "Full ReceiptsNow business suite billed monthly.",
            priceMinor = LAUNCH_MONTHLY_PRICE_MINOR,
            currency = "NGN",
            billingFrequency = BillingFrequency.MONTHLY.name,
            intervalDays = 30,
            trialDays = 0,
            visible = true,
            status = PlanStatus.ACTIVE.name,
            sortOrder = 1,
            paystackPaymentUrl = "https://paystack.shop/pay/1803reciepts"
        ),
        SubscriptionPlan(
            id = LAUNCH_ANNUAL_PLAN_ID,
            planId = LAUNCH_ANNUAL_PLAN_ID,
            planCode = "ANNUAL",
            name = "Yearly",
            description = "Full ReceiptsNow business suite billed yearly (₦1,500 × 12 months).",
            priceMinor = LAUNCH_ANNUAL_PRICE_MINOR,
            currency = "NGN",
            billingFrequency = BillingFrequency.ANNUAL.name,
            intervalDays = 365,
            trialDays = 0,
            visible = true,
            status = PlanStatus.ACTIVE.name,
            sortOrder = 2,
            paystackPaymentUrl = "https://paystack.shop/pay/1803receipt"
        )
    )

    fun defaultLaunchEntitlements(): List<PlanEntitlement> = listOf(
        PlanEntitlement(
            id = LAUNCH_MONTHLY_PLAN_ID,
            planId = LAUNCH_MONTHLY_PLAN_ID,
            maxReceiptsPerCycle = 300,
            rolloverEnabled = false,
            overageReceiptPriceMinor = 0L,
            storageLimitBytes = 500L * 1024L * 1024L,
            storageOverLimitAction = StorageOverLimitAction.BLOCK_NEW_UPLOADS.name,
            overageStoragePricePerGbMinor = 0L,
            maxStaffAccounts = 5,
            maxOcrPerCycle = 300,
            maxCustomers = 1000,
            allowedTemplates = ReceiptTemplateType.entries.map { it.name },
            analyticsEnabled = true,
            reportsExportEnabled = true,
            qrVerificationEnabled = true,
            whatsappSharingEnabled = true,
            emailSharingEnabled = true,
            prioritySupport = false,
            warningThresholds = listOf(50, 75, 90, 100)
        ),
        PlanEntitlement(
            id = LAUNCH_ANNUAL_PLAN_ID,
            planId = LAUNCH_ANNUAL_PLAN_ID,
            maxReceiptsPerCycle = 3600,
            rolloverEnabled = false,
            overageReceiptPriceMinor = 0L,
            storageLimitBytes = 2048L * 1024L * 1024L,
            storageOverLimitAction = StorageOverLimitAction.BLOCK_NEW_UPLOADS.name,
            overageStoragePricePerGbMinor = 0L,
            maxStaffAccounts = 20,
            maxOcrPerCycle = 3600,
            maxCustomers = 10000,
            allowedTemplates = ReceiptTemplateType.entries.map { it.name },
            analyticsEnabled = true,
            reportsExportEnabled = true,
            qrVerificationEnabled = true,
            whatsappSharingEnabled = true,
            emailSharingEnabled = true,
            prioritySupport = true,
            warningThresholds = listOf(50, 75, 90, 100)
        )
    )

    fun defaultLaunchFeatures(): List<PlanFeature> = listOf(
        PlanFeature(id = "feat_m_1", planId = LAUNCH_MONTHLY_PLAN_ID, featureKey = "receipts", label = "Up to 300 receipts / month", valueText = "300", included = true, sortOrder = 1),
        PlanFeature(id = "feat_m_2", planId = LAUNCH_MONTHLY_PLAN_ID, featureKey = "storage", label = "500 MB cloud document storage", valueText = "500 MB", included = true, sortOrder = 2),
        PlanFeature(id = "feat_m_3", planId = LAUNCH_MONTHLY_PLAN_ID, featureKey = "staff", label = "Up to 5 team members & RBAC", valueText = "5", included = true, sortOrder = 3),
        PlanFeature(id = "feat_m_4", planId = LAUNCH_MONTHLY_PLAN_ID, featureKey = "ocr_qr", label = "Smart OCR extraction & QR verification", valueText = "Included", included = true, sortOrder = 4),
        PlanFeature(id = "feat_a_1", planId = LAUNCH_ANNUAL_PLAN_ID, featureKey = "receipts", label = "Up to 3,600 receipts / year", valueText = "3,600", included = true, sortOrder = 1),
        PlanFeature(id = "feat_a_2", planId = LAUNCH_ANNUAL_PLAN_ID, featureKey = "storage", label = "2 GB cloud document storage", valueText = "2 GB", included = true, sortOrder = 2),
        PlanFeature(id = "feat_a_3", planId = LAUNCH_ANNUAL_PLAN_ID, featureKey = "staff", label = "Up to 20 team members & RBAC", valueText = "20", included = true, sortOrder = 3),
        PlanFeature(id = "feat_a_4", planId = LAUNCH_ANNUAL_PLAN_ID, featureKey = "support", label = "Priority ReceiptsNow support & full exports", valueText = "Priority", included = true, sortOrder = 4)
    )
}
