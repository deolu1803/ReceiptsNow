package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class StaffRole(val displayName: String, val description: String) {
    OWNER("Business Owner", "Full administrative control over workspace, staff, billing, and receipts"),
    BUSINESS_MANAGER("Business Manager", "Manage operations, customers, receipts, and supervise staff"),
    RECEIPT_OFFICER("Receipt Officer", "Upload bank receipts, extract OCR, enter customer details, generate receipts"),
    VERIFICATION_OFFICER("Verification Officer", "Verify payments against bank records, reject or flag suspicious claims"),
    ACCOUNTANT("Accountant / Report Viewer", "View financial reports, transaction history, and export reports");

    fun canGenerateReceipts(): Boolean =
        this == OWNER || this == BUSINESS_MANAGER || this == RECEIPT_OFFICER

    fun canVerifyTransactions(): Boolean =
        this == OWNER || this == BUSINESS_MANAGER || this == VERIFICATION_OFFICER

    fun canViewAnalyticsAndReports(): Boolean =
        this == OWNER || this == BUSINESS_MANAGER || this == ACCOUNTANT || this == VERIFICATION_OFFICER

    fun canExportReports(): Boolean =
        this == OWNER || this == BUSINESS_MANAGER || this == ACCOUNTANT

    fun canManageCustomers(): Boolean =
        this == OWNER || this == BUSINESS_MANAGER || this == RECEIPT_OFFICER

    fun canManageSettingsAndBranding(): Boolean =
        this == OWNER || this == BUSINESS_MANAGER

    fun canManageStaffAndSubscription(): Boolean =
        this == OWNER
}

enum class SubscriptionStatus(val label: String) {
    AWAITING_SUBSCRIPTION("Awaiting Subscription"),
    TRIALING("Trial Active"),
    PENDING_PAYMENT("Pending Payment"),
    ACTIVE("Active"),
    EXPIRING_SOON("Expiring Soon"),
    GRACE_PERIOD("Grace Period"),
    PAST_DUE("Past Due"),
    EXPIRED("Expired"),
    CANCELLED("Cancelled"),
    SUSPENDED("Suspended")
}

enum class VerificationStatus(val label: String) {
    PENDING_VERIFICATION("Pending Verification"),
    VERIFIED("Verified"),
    REJECTED("Rejected"),
    REVERSED("Reversed"),
    CANCELLED("Cancelled")
}

enum class ReceiptTemplateType(val label: String, val subtitle: String) {
    STANDARD("Standard Business", "Clean conventional landscape receipt for SMEs, retailers, and consultants"),
    PREMIUM_CORPORATE("Premium Corporate", "Executive branded layout with dark left pillar, structured audit grid, and formal seal"),
    COMPACT("Compact Express", "High-contrast express receipt highlighting amount, customer, and reference"),
    MODERN_CLASSIC("Modern Classic", "Crisp editorial card with top accent rule, centered crest badge, and rounded summary pills"),
    ROYAL_CERTIFICATE("Royal Certificate", "Classic double-bordered financial certificate frame with boxed audit table grid"),
    FINTECH_WAVE("Modern Fintech", "Sweeping curved header wave, floating amount card, and sleek traceability chips")
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val country: String = "Nigeria",
    val state: String = "",
    val city: String = "",
    val address: String = "",
    val activeBusinessId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PlatformSetting(
    @DocumentId val id: String = "",
    val settingKey: String = "global",
    val monthlyPriceNgn: Double = 1500.0,
    val annualPriceNgn: Double = 18000.0,
    val storageLimitMb: Long = 500L,
    val gracePeriodDays: Int = 3,
    val prorationEnabled: Boolean = true,
    val defaultRolloverEnabled: Boolean = false,
    val bankAccountName: String = "ReceiptsNow",
    val bankAccountNumber: String = "3001823009",
    val bankName: String = "KUDA",
    val notificationEmail: String = "info.1803consult@gmail.com",
    val emailNotificationWebhookUrl: String = "",
    val paystackMonthlyUrl: String = "https://paystack.shop/pay/1803reciepts",
    val paystackAnnualUrl: String = "https://paystack.shop/pay/1803receipt",
    val paystackPublicKey: String = "",
    val paystackVerifyEndpoint: String = "",
    val flutterwavePublicKey: String = "",
    val flutterwaveEnvironment: String = "LIVE",
    val flutterwaveCurrency: String = "NGN",
    val flutterwaveVerifyEndpoint: String = "",
    val flutterwaveWebhookUrl: String = "",
    val supportEmail: String = "info.1803consult@gmail.com",
    val supportPhone: String = "",
    val latestVersionCode: Int = 1,
    val latestVersionName: String = "1.0",
    val updateTitle: String = "",
    val releaseNotes: String = "",
    val updateDownloadUrl: String = "",
    val forceUpdate: Boolean = false,
    val configRevision: Long = 0L,
    val publishedAtMillis: Long = 0L,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isUpdateAvailable(
        currentVersionCode: Int,
        currentVersionName: String
    ): Boolean {
        if (latestVersionCode > currentVersionCode) return true
        if (latestVersionName.isBlank()) return false
        return compareVersionNames(latestVersionName, currentVersionName) > 0
    }

    companion object {
        fun compareVersionNames(target: String, current: String): Int {
            val targetParts = target.trim().removePrefix("v").removePrefix("V")
                .split(".")
                .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val currentParts = current.trim().removePrefix("v").removePrefix("V")
                .split(".")
                .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val maxLen = maxOf(targetParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val t = targetParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (t != c) return t.compareTo(c)
            }
            return 0
        }
    }
}

@IgnoreExtraProperties
data class PlatformAnnouncement(
    @DocumentId val id: String = "",
    val title: String = "",
    val message: String = "",
    val category: String = "GENERAL",
    val status: String = "PUBLISHED",
    val targetAudience: String = "ALL",
    val createdByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Business(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val ownerName: String = "",
    val ownerEmail: String = "",
    val ownerPhone: String = "",
    val registeredName: String = "",
    val tradingName: String = "",
    val category: String = "",
    val description: String = "",
    val structure: String = "Business Name",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val cacNumber: String = "",
    val socialMedia: String = "",
    val authorisedRepName: String = "",
    val logoBase64: String = "",
    val signatureBase64: String = "",
    val stampText: String = "",
    val primaryColorHex: String = "#054230",
    val secondaryColorHex: String = "#10B981",
    val accentColorHex: String = "#C89B3C",
    val backgroundColorHex: String = "#FFFFFF",
    val textColorHex: String = "#0F172A",
    val defaultCurrency: String = "NGN",
    val receiptPrefix: String = "RCN",
    val numberingFormat: String = "{PREFIX}/RCT/{YEAR}/{SEQ}",
    val defaultAcknowledgement: String = "Received with thanks from the above-named customer the sum stated herein for the service or goods described in this receipt.",
    val businessSlogan: String = "",
    val defaultTemplate: String = ReceiptTemplateType.STANDARD.name,
    val publicVerificationEnabled: Boolean = true,
    val maskAccountNumbers: Boolean = true,
    val showSignature: Boolean = true,
    val showQrCode: Boolean = true,
    val subscriptionStatus: String = SubscriptionStatus.AWAITING_SUBSCRIPTION.name,
    val subscriptionPlanCode: String = "",
    val subscriptionPlanId: String = "",
    val subscriptionExpiryMillis: Long = 0L,
    val subscriptionActivatedMillis: Long = 0L,
    val accountStatus: String = "ACTIVE",
    val storageUsedBytes: Long = 0L,
    val storageLimitBytes: Long = 500L * 1024L * 1024L,
    val receiptsCount: Long = 0L,
    val uploadedDocsCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun effectiveSubscriptionStatus(
        nowMillis: Long = System.currentTimeMillis(),
        gracePeriodEndMillis: Long = 0L
    ): SubscriptionStatus {
        if (accountStatus == "SUSPENDED") return SubscriptionStatus.SUSPENDED
        val raw = runCatching { SubscriptionStatus.valueOf(subscriptionStatus) }
            .getOrDefault(SubscriptionStatus.AWAITING_SUBSCRIPTION)
        if (raw == SubscriptionStatus.ACTIVE ||
            raw == SubscriptionStatus.EXPIRING_SOON ||
            raw == SubscriptionStatus.TRIALING ||
            raw == SubscriptionStatus.GRACE_PERIOD
        ) {
            if (raw == SubscriptionStatus.TRIALING && subscriptionExpiryMillis <= 0L) {
                return SubscriptionStatus.EXPIRED
            }
            if (subscriptionExpiryMillis > 0L && nowMillis > subscriptionExpiryMillis) {
                return if (raw != SubscriptionStatus.TRIALING &&
                    gracePeriodEndMillis > subscriptionExpiryMillis &&
                    nowMillis <= gracePeriodEndMillis
                ) {
                    SubscriptionStatus.GRACE_PERIOD
                } else {
                    SubscriptionStatus.EXPIRED
                }
            }
            val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
            if (raw != SubscriptionStatus.TRIALING &&
                subscriptionExpiryMillis > 0L &&
                (subscriptionExpiryMillis - nowMillis) <= sevenDaysMs
            ) {
                return SubscriptionStatus.EXPIRING_SOON
            }
        }
        return raw
    }

    fun isSubscriptionActive(
        nowMillis: Long = System.currentTimeMillis(),
        gracePeriodEndMillis: Long = 0L
    ): Boolean {
        val status = effectiveSubscriptionStatus(nowMillis, gracePeriodEndMillis)
        return accountStatus == "ACTIVE" && (
            status == SubscriptionStatus.ACTIVE ||
                status == SubscriptionStatus.EXPIRING_SOON ||
                status == SubscriptionStatus.TRIALING ||
                status == SubscriptionStatus.GRACE_PERIOD
            )
    }

    fun trialDaysRemaining(nowMillis: Long = System.currentTimeMillis()): Int {
        if (effectiveSubscriptionStatus(nowMillis) != SubscriptionStatus.TRIALING) return 0
        val remainingMs = (subscriptionExpiryMillis - nowMillis).coerceAtLeast(0L)
        return ((remainingMs + 86_399_999L) / 86_400_000L).toInt().coerceIn(0, BillingCalculator.FREE_TRIAL_DAYS)
    }

    fun isTrialExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return subscriptionStatus == SubscriptionStatus.TRIALING.name &&
            effectiveSubscriptionStatus(nowMillis) == SubscriptionStatus.EXPIRED
    }
}

@IgnoreExtraProperties
data class Customer(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val fullName: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val customerCode: String = "",
    val totalVerifiedAmount: Double = 0.0,
    val receiptCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CatalogItem(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val name: String = "",
    val description: String = "",
    val unitPrice: Double = 0.0,
    val category: String = "",
    val active: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BankTransactionRecord(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val uploadedByUid: String = "",
    val uploadedByName: String = "",
    val documentType: String = "IMAGE",
    val documentName: String = "",
    val documentSizeBytes: Long = 0L,
    val thumbnailBase64: String = "",
    val amount: Double = 0.0,
    val currency: String = "NGN",
    val transactionDate: String = "",
    val transactionTime: String = "",
    val transactionReference: String = "",
    val sessionId: String = "",
    val transactionId: String = "",
    val bankReceiptNumber: String = "",
    val paymentNarration: String = "",
    val transferChannel: String = "",
    val bankTransactionStatus: String = "",
    val transferFee: Double = 0.0,
    val senderName: String = "",
    val senderAccountName: String = "",
    val senderBank: String = "",
    val senderAccountNumber: String = "",
    val beneficiaryName: String = "",
    val beneficiaryAccountName: String = "",
    val beneficiaryBank: String = "",
    val beneficiaryAccountNumber: String = "",
    val ocrConfidenceScore: Double = 0.0,
    val humanReviewed: Boolean = false,
    val verificationStatus: String = VerificationStatus.PENDING_VERIFICATION.name,
    val verificationComment: String = "",
    val verifiedByUid: String = "",
    val verifiedByName: String = "",
    val receiptId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ReceiptRecord(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val receiptNumber: String = "",
    val verificationCode: String = "",
    val transactionRecordId: String = "",
    val customerId: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    val customerEmail: String = "",
    val customerAddress: String = "",
    val paymentPurpose: String = "",
    val invoiceNumber: String = "",
    val orderNumber: String = "",
    val notes: String = "",
    val amountPaid: Double = 0.0,
    val discountAmount: Double = 0.0,
    val currency: String = "NGN",
    val amountInWords: String = "",
    val templateType: String = ReceiptTemplateType.STANDARD.name,
    val verificationStatus: String = VerificationStatus.PENDING_VERIFICATION.name,
    val issuedByUid: String = "",
    val issuedByName: String = "",
    val issuedByRole: String = "",
    val amendedFromReceiptId: String = "",
    val cancellationReason: String = "",
    val businessNameSnapshot: String = "",
    val businessPrimaryHexSnapshot: String = "#054230",
    val businessAccentHexSnapshot: String = "#D99B26",
    val senderBankSnapshot: String = "",
    val senderAccountNameSnapshot: String = "",
    val beneficiaryBankSnapshot: String = "",
    val transactionRefSnapshot: String = "",
    val sessionIdSnapshot: String = "",
    val transactionDateSnapshot: String = "",
    val transactionTimeSnapshot: String = "",
    val transferChannelSnapshot: String = "",
    val narrationSnapshot: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class StaffMember(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val staffUid: String = "",
    val fullName: String = "",
    val email: String = "",
    val role: String = StaffRole.RECEIPT_OFFICER.name,
    val active: Boolean = true,
    val inviteId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun staffRole(): StaffRole =
        runCatching { StaffRole.valueOf(role) }.getOrDefault(StaffRole.RECEIPT_OFFICER)
}

@IgnoreExtraProperties
data class StaffInvite(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val businessName: String = "",
    val ownerUid: String = "",
    val invitedEmail: String = "",
    val invitedName: String = "",
    val role: String = StaffRole.RECEIPT_OFFICER.name,
    val status: String = "PENDING",
    val acceptedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val actorUid: String = "",
    val actorName: String = "",
    val actorRole: String = "",
    val action: String = "",
    val targetType: String = "",
    val targetId: String = "",
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AppNotification(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val title: String = "",
    val body: String = "",
    val category: String = "SYSTEM",
    val read: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SubscriptionPayment(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val businessName: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val ownerEmail: String = "",
    val ownerPhone: String = "",
    val planId: String = "",
    val planCode: String = "MONTHLY",
    val planName: String = "",
    val amountNgn: Double = 0.0,
    val amountMinor: Long = 0L,
    val prorationCreditMinor: Long = 0L,
    val currency: String = "NGN",
    val txRef: String = "",
    val idempotencyKey: String = "",
    val flwTransactionId: String = "",
    val paymentMethod: String = "PAYSTACK",
    val paystackReference: String = "",
    val paystackTransactionId: String = "",
    val paystackChannel: String = "",
    val paystackBank: String = "",
    val paystackCardSummary: String = "",
    val paystackCustomerEmail: String = "",
    val paystackGatewayResponse: String = "",
    val paystackPaymentUrl: String = "",
    val verificationSource: String = "",
    val paidAtMillis: Long = 0L,
    val bankAccountName: String = "ReceiptsNow",
    val bankAccountNumber: String = "3001823009",
    val bankName: String = "KUDA",
    val senderName: String = "",
    val senderBank: String = "",
    val transferNote: String = "",
    val receiptImageBase64: String = "",
    val receiptFileName: String = "",
    val receiptMimeType: String = "image/jpeg",
    val paymentStatus: String = "PENDING",
    val verifiedByUid: String = "",
    val verifiedByEmail: String = "",
    val verifiedAtMillis: Long = 0L,
    val cycleStartMillis: Long = 0L,
    val cycleEndMillis: Long = 0L,
    val invoiceId: String = "",
    val rejectionReason: String = "",
    val emailNotificationStatus: String = "QUEUED",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun effectiveAmountMinor(): Long = if (amountMinor > 0L) amountMinor else kotlin.math.round(amountNgn * 100.0).toLong()
    fun effectiveReference(): String = paystackReference.ifBlank { txRef }
    fun isPaystack(): Boolean = paymentMethod.equals("PAYSTACK", ignoreCase = true) || paystackReference.isNotBlank()
}

@IgnoreExtraProperties
data class MailNotification(
    @DocumentId val id: String = "",
    val paymentId: String = "",
    val businessId: String = "",
    val businessName: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val ownerEmail: String = "",
    val ownerPhone: String = "",
    val planCode: String = "",
    val amountNgn: Double = 0.0,
    val txRef: String = "",
    val senderName: String = "",
    val toEmail: String = "info.1803consult@gmail.com",
    val subject: String = "",
    val body: String = "",
    val deliveryStatus: String = "QUEUED",
    val lastError: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SupportTicket(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val businessName: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val ownerEmail: String = "",
    val subject: String = "",
    val category: String = "TECHNICAL",
    val source: String = "HELP_DESK",
    val description: String = "",
    val status: String = "OPEN",
    val adminReply: String = "",
    val emailNotificationStatus: String = "QUEUED",
    val emailNotificationError: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PublicVerification(
    @DocumentId val id: String = "",
    val verificationCode: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val businessName: String = "",
    val receiptNumber: String = "",
    val receiptDateText: String = "",
    val customerDisplayName: String = "",
    val amountFormatted: String = "",
    val purposeSummary: String = "",
    val verificationStatus: String = VerificationStatus.PENDING_VERIFICATION.name,
    val publicVerificationEnabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class ReceiptLineItem(
    val description: String = "",
    val quantity: Int = 1,
    val unitPrice: Double = 0.0
) {
    val lineTotal: Double get() = quantity * unitPrice
}

data class ExtractedBankReceipt(
    val amount: Double? = null,
    val currency: String = "NGN",
    val transactionDate: String = "",
    val transactionTime: String = "",
    val transactionReference: String = "",
    val sessionId: String = "",
    val transactionId: String = "",
    val bankReceiptNumber: String = "",
    val paymentNarration: String = "",
    val transferChannel: String = "",
    val transactionStatus: String = "",
    val transferFee: Double? = null,
    val senderName: String = "",
    val senderAccountName: String = "",
    val senderBank: String = "",
    val senderAccountNumber: String = "",
    val beneficiaryName: String = "",
    val beneficiaryAccountName: String = "",
    val beneficiaryBank: String = "",
    val beneficiaryAccountNumber: String = "",
    val confidenceScore: Double = 0.0,
    val uncertainFields: List<String> = emptyList(),
    val extractionNotes: String = ""
)

enum class LedgerCategory(
    val label: String,
    val shortLabel: String,
    val isInflow: Boolean
) {
    SALES_INCOME("Sales / Income", "Sales", true),
    PURCHASE("Purchases / Stock", "Purchase", false),
    EXPENDITURE("Expenditure / Bills", "Expense", false),
    REFUND_REVERSAL("Refund / Reversal", "Refund", false),
    OTHER("Other / Misc", "Other", false)
}

@IgnoreExtraProperties
data class LedgerEntryItem(
    val id: String = "",
    val documentName: String = "",
    val merchantOrParty: String = "",
    val category: String = LedgerCategory.SALES_INCOME.name,
    val amount: Double = 0.0,
    val currency: String = "NGN",
    val paymentMethod: String = "",
    val reference: String = "",
    val transactionDate: String = "",
    val notes: String = "",
    val confidenceScore: Double = 1.0,
    val sourceType: String = "UPLOADED_RECEIPT"
) {
    fun categoryEnum(): LedgerCategory =
        runCatching { LedgerCategory.valueOf(category.uppercase()) }
            .getOrDefault(LedgerCategory.OTHER)
}

data class LedgerCalculationSummary(
    val totalSales: Double,
    val totalPurchases: Double,
    val totalExpenditure: Double,
    val totalRefunds: Double,
    val totalOther: Double,
    val grossProfit: Double,
    val netProfit: Double,
    val profitMarginPercent: Double,
    val entryCount: Int,
    val itemCountByCategory: Map<LedgerCategory, Int>,
    val amountByCategory: Map<LedgerCategory, Double>
) {
    val totalOutflow: Double
        get() = totalPurchases + totalExpenditure + totalRefunds + totalOther
}

@IgnoreExtraProperties
data class DailyLedgerSheet(
    @DocumentId val id: String = "",
    val businessId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val title: String = "",
    val ledgerDate: String = "",
    val currency: String = "NGN",
    val totalSales: Double = 0.0,
    val totalPurchases: Double = 0.0,
    val totalExpenditure: Double = 0.0,
    val totalRefunds: Double = 0.0,
    val totalOther: Double = 0.0,
    val grossProfit: Double = 0.0,
    val netProfit: Double = 0.0,
    val entryCount: Int = 0,
    val entries: List<LedgerEntryItem> = emptyList(),
    val createdByUid: String = "",
    val createdByName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

object LedgerCalculator {
    fun summarize(entries: List<LedgerEntryItem>): LedgerCalculationSummary {
        var sales = 0.0
        var purchases = 0.0
        var expenditure = 0.0
        var refunds = 0.0
        var other = 0.0

        val countMap = LedgerCategory.entries.associateWith { 0 }.toMutableMap()
        val amountMap = LedgerCategory.entries.associateWith { 0.0 }.toMutableMap()

        for (item in entries) {
            val amt = item.amount.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val cat = item.categoryEnum()
            countMap[cat] = (countMap[cat] ?: 0) + 1
            amountMap[cat] = (amountMap[cat] ?: 0.0) + amt
            when (cat) {
                LedgerCategory.SALES_INCOME -> sales += amt
                LedgerCategory.PURCHASE -> purchases += amt
                LedgerCategory.EXPENDITURE -> expenditure += amt
                LedgerCategory.REFUND_REVERSAL -> refunds += amt
                LedgerCategory.OTHER -> other += amt
            }
        }

        val grossProfit = sales - purchases
        val netProfit = sales - purchases - expenditure - refunds - other
        val marginPct = if (sales > 0.0) (netProfit / sales) * 100.0 else 0.0

        return LedgerCalculationSummary(
            totalSales = sales,
            totalPurchases = purchases,
            totalExpenditure = expenditure,
            totalRefunds = refunds,
            totalOther = other,
            grossProfit = grossProfit,
            netProfit = netProfit,
            profitMarginPercent = marginPct,
            entryCount = entries.size,
            itemCountByCategory = countMap,
            amountByCategory = amountMap
        )
    }
}

