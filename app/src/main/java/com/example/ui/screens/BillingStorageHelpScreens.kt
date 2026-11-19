package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.billing.PaystackPaymentService
import com.example.data.local.OfflineDraftEntity
import com.example.data.model.AppNotification
import com.example.data.model.BillingCalculator
import com.example.data.model.Business
import com.example.data.model.BusinessSubscription
import com.example.data.model.Customer
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.PlatformAnnouncement
import com.example.data.model.PlatformSetting
import com.example.data.model.PlanChangeHistory
import com.example.data.model.PlanEntitlement
import com.example.data.model.PlanFeature
import com.example.data.model.PlanStatus
import com.example.data.model.ProrationQuote
import com.example.data.model.ReceiptRecord
import com.example.data.model.StorageUsage
import com.example.data.model.SubscriptionInvoice
import com.example.data.model.SubscriptionPayment
import com.example.data.model.SubscriptionPlan
import com.example.data.model.SubscriptionStatus
import com.example.data.model.SupportTicket
import com.example.data.model.UsageQuota
import com.example.ui.components.PaystackBillingOverviewCard
import com.example.ui.components.PaystackCheckoutDialog
import com.example.ui.components.SubscriptionReceiptPreviewDialog
import com.example.ui.components.SubscriptionStatusBanner
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.NigerianEmerald
import com.example.ui.theme.SovereignNavy
import com.example.ui.viewmodel.PaymentSubmissionOutcome
import com.example.util.ReportExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun BillingSubscriptionScreen(
    business: Business,
    platformSetting: PlatformSetting,
    payments: List<SubscriptionPayment>,
    plans: List<SubscriptionPlan> = emptyList(),
    planFeatures: List<PlanFeature> = emptyList(),
    planEntitlements: List<PlanEntitlement> = emptyList(),
    activeSubscription: BusinessSubscription? = null,
    usageQuota: UsageQuota? = null,
    storageUsage: StorageUsage? = null,
    effectiveEntitlements: EffectiveEntitlements? = null,
    invoices: List<SubscriptionInvoice> = emptyList(),
    planHistory: List<PlanChangeHistory> = emptyList(),
    isSubmitting: Boolean = false,
    onUpdateAutoRenewOrCancel: (autoRenew: Boolean, cancelAtPeriodEnd: Boolean, cancelImmediately: Boolean) -> Unit = { _, _, _ -> },
    onVerifyPaystackPayment: (
        selectedPlan: SubscriptionPlan,
        reference: String,
        checkoutConfirmedInWebView: Boolean,
        detectedChannel: String,
        paymentUrl: String,
        prorationQuote: ProrationQuote?,
        onError: (String) -> Unit,
        onDone: (PaymentSubmissionOutcome) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPlanForCheckout by remember { mutableStateOf<SubscriptionPlan?>(null) }
    var paystackVerificationError by remember { mutableStateOf<String?>(null) }
    var inspectingPayment by remember { mutableStateOf<SubscriptionPayment?>(null) }
    var submissionOutcome by remember { mutableStateOf<PaymentSubmissionOutcome?>(null) }
    var confirmImmediateCancel by remember { mutableStateOf(false) }

    val availablePlans = remember(plans) {
        plans.ifEmpty { BillingCalculator.defaultLaunchPlans() }
            .filter { it.visible && it.status == PlanStatus.ACTIVE.name }
            .sortedBy { it.sortOrder }
    }
    val entitlementsMap = remember(planEntitlements) {
        planEntitlements.ifEmpty { BillingCalculator.defaultLaunchEntitlements() }.associateBy { it.planId }
    }
    val featuresByPlan = remember(planFeatures) {
        planFeatures.ifEmpty { BillingCalculator.defaultLaunchFeatures() }.groupBy { it.planId }
    }

    val cycleEndMillis = effectiveEntitlements?.cycleEndMillis ?: business.subscriptionExpiryMillis
    val dateFmt = remember {
        SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Africa/Lagos")
        }
    }
    val expiryText = remember(cycleEndMillis, business.subscriptionStatus, business.subscriptionPlanCode) {
        when {
            cycleEndMillis <= 0L -> "No active renewal date"
            business.subscriptionPlanCode.equals("FREE_TRIAL", ignoreCase = true) ||
                business.effectiveSubscriptionStatus(activeSubscription?.gracePeriodEndMillis ?: 0L) == SubscriptionStatus.TRIALING ->
                "90-Day Trial Ends: ${dateFmt.format(Date(cycleEndMillis))}"
            else -> "Next Reset / Renewal: ${dateFmt.format(Date(cycleEndMillis))}"
        }
    }

    inspectingPayment?.let { pay ->
        SubscriptionReceiptPreviewDialog(payment = pay, onDismiss = { inspectingPayment = null })
    }

    if (confirmImmediateCancel) {
        AlertDialog(
            onDismissRequest = { confirmImmediateCancel = false },
            title = { Text("Cancel Subscription Immediately?") },
            text = {
                Text(
                    "Your subscription will be cancelled right away, blocking new receipt generation until reactivated. " +
                        "All your existing receipts, bank proofs, and customer records will remain completely safe and accessible."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmImmediateCancel = false
                        onUpdateAutoRenewOrCancel(false, false, true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertCrimson)
                ) { Text("Confirm Cancel") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImmediateCancel = false }) { Text("Keep Active") }
            }
        )
    }

    submissionOutcome?.let { outcome ->
        AlertDialog(
            onDismissRequest = { submissionOutcome = null },
            shape = MaterialTheme.shapes.extraLarge,
            icon = {
                Icon(
                    imageVector = if (outcome.isAutoActivated) Icons.Default.Verified else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = NigerianEmerald,
                    modifier = Modifier.size(38.dp)
                )
            },
            title = {
                Text(
                    if (outcome.isAutoActivated) "Subscription Activated!" else "Paystack Reference Submitted",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (outcome.isAutoActivated) {
                            "Your ${outcome.planName} subscription (${outcome.amountFormatted}) has been confirmed via Paystack and activated automatically."
                        } else {
                            "Your Paystack payment reference (${outcome.paystackReference}) has been submitted for confirmation."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (outcome.paystackReference.isNotBlank()) {
                        Text(
                            "Reference: ${outcome.paystackReference}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (outcome.channelSummary.isNotBlank()) {
                        Text(
                            "Channel: ${outcome.channelSummary}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { submissionOutcome = null },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald),
                    modifier = Modifier.testTag("payment_uploaded_done_button")
                ) {
                    Text("Done")
                }
            }
        )
    }

    selectedPlanForCheckout?.let { targetPlan ->
        val quote = remember(activeSubscription, targetPlan, platformSetting.prorationEnabled) {
            BillingCalculator.calculateProrationQuote(
                currentSub = activeSubscription,
                targetPlan = targetPlan,
                nowMillis = System.currentTimeMillis(),
                prorationEnabled = platformSetting.prorationEnabled
            )
        }
        val checkoutUrl = remember(targetPlan, platformSetting) {
            PaystackPaymentService.resolvePlanUrl(targetPlan, platformSetting)
        }
        PaystackCheckoutDialog(
            plan = targetPlan,
            quote = quote,
            checkoutUrl = checkoutUrl,
            ownerEmail = business.ownerEmail.ifBlank { business.email },
            isVerifying = isSubmitting,
            verificationError = paystackVerificationError,
            onPaymentDetected = { ref, confirmedInWebView, channel ->
                paystackVerificationError = null
                onVerifyPaystackPayment(
                    targetPlan,
                    ref,
                    confirmedInWebView,
                    channel,
                    checkoutUrl,
                    quote,
                    { err -> paystackVerificationError = err },
                    { outcome ->
                        paystackVerificationError = null
                        selectedPlanForCheckout = null
                        submissionOutcome = outcome
                    }
                )
            },
            onDismiss = {
                paystackVerificationError = null
                selectedPlanForCheckout = null
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item { Text("Subscription, Quotas & Billing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            SubscriptionStatusBanner(
                business = business,
                gracePeriodEndMillis = activeSubscription?.gracePeriodEndMillis ?: 0L,
                onManageBilling = {}
            )
        }
        item {
            Text(
                "$expiryText  •  Active Plan: ${effectiveEntitlements?.planName ?: business.subscriptionPlanCode.ifBlank { "None" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Usage & Quota Meter Card
        if (effectiveEntitlements != null) {
            item {
                Card(
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cycle Usage & Allowances", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (effectiveEntitlements.hasCustomOverride) {
                                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(999.dp)) {
                                    Text(
                                        "Custom Plan Override",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        val receiptsAllowed = effectiveEntitlements.totalReceiptAllowance
                        val receiptRatio = if (effectiveEntitlements.maxReceiptsPerCycle < 0 || receiptsAllowed <= 0) 0f
                        else (effectiveEntitlements.receiptsUsed.toFloat() / receiptsAllowed.toFloat()).coerceIn(0f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Receipt Generation", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (effectiveEntitlements.maxReceiptsPerCycle < 0) "${effectiveEntitlements.receiptsUsed} used (Unlimited)"
                                    else "${effectiveEntitlements.receiptsUsed} / $receiptsAllowed (${effectiveEntitlements.receiptsRemaining} left)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (effectiveEntitlements.isReceiptQuotaExceeded) AlertCrimson else MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (effectiveEntitlements.maxReceiptsPerCycle >= 0) {
                                LinearProgressIndicator(
                                    progress = { receiptRatio },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                                    color = if (receiptRatio >= 0.9f) AlertCrimson else NigerianEmerald
                                )
                            }
                            if (effectiveEntitlements.rolloverReceipts > 0) {
                                Text(
                                    "Includes ${effectiveEntitlements.rolloverReceipts} rolled-over receipts from previous cycle.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NigerianEmerald
                                )
                            }
                        }

                        val ocrRatio = if (effectiveEntitlements.maxOcrPerCycle <= 0) 0f
                        else (effectiveEntitlements.ocrUsed.toFloat() / effectiveEntitlements.maxOcrPerCycle.toFloat()).coerceIn(0f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("AI OCR Scans", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (effectiveEntitlements.maxOcrPerCycle < 0) "${effectiveEntitlements.ocrUsed} used (Unlimited)"
                                    else "${effectiveEntitlements.ocrUsed} / ${effectiveEntitlements.maxOcrPerCycle}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (effectiveEntitlements.maxOcrPerCycle >= 0) {
                                LinearProgressIndicator(
                                    progress = { ocrRatio },
                                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp))
                                )
                            }
                        }

                        val stRatio = if (effectiveEntitlements.storageLimitBytes <= 0L) 0f
                        else (effectiveEntitlements.storageUsedBytes.toFloat() / effectiveEntitlements.storageLimitBytes.toFloat()).coerceIn(0f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Cloud Storage", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${BillingCalculator.formatBytes(effectiveEntitlements.storageUsedBytes)} / ${BillingCalculator.formatBytes(effectiveEntitlements.storageLimitBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (effectiveEntitlements.isStorageLimitExceeded) AlertCrimson else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (effectiveEntitlements.storageLimitBytes >= 0L) {
                                LinearProgressIndicator(
                                    progress = { stRatio },
                                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp)),
                                    color = if (stRatio >= 0.9f) AlertCrimson else MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        val thresholdsSent = usageQuota?.warningsSentPct.orEmpty()
                        if (thresholdsSent.isNotEmpty()) {
                            Text(
                                "Usage threshold alerts triggered: ${thresholdsSent.sorted().joinToString { "$it%" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (effectiveEntitlements.overLimitWarnings.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = AlertCrimson, modifier = Modifier.size(16.dp))
                                        Text("Plan Allowance Warnings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                    effectiveEntitlements.overLimitWarnings.forEach { warn ->
                                        Text("• $warn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val isOnTrialPlan = activeSubscription?.planId == BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID ||
            business.subscriptionPlanCode.equals("FREE_TRIAL", ignoreCase = true)
        if (isOnTrialPlan) {
            val daysLeft = business.trialDaysRemaining()
            val trialExpired = business.isTrialExpired()
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (trialExpired) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (trialExpired) AlertCrimson.copy(alpha = 0.6f) else NigerianEmerald.copy(alpha = 0.55f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (trialExpired) "90-Day Free Trial Expired" else "90-Day Free Trial ($daysLeft of ${BillingCalculator.FREE_TRIAL_DAYS} days left)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (trialExpired) AlertCrimson else NigerianEmerald
                        )
                        if (!trialExpired) {
                            LinearProgressIndicator(
                                progress = { (daysLeft.toFloat() / BillingCalculator.FREE_TRIAL_DAYS.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                                color = NigerianEmerald
                            )
                        }
                        Text(
                            if (trialExpired) {
                                "Your 90-day free trial has ended. Select the Monthly or Yearly plan below to continue issuing receipts."
                            } else {
                                "All core ReceiptsNow features are unlocked during your 90-day trial. Choose Monthly or Yearly below anytime to continue uninterrupted."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Lifecycle Controls: Auto-Renew, Cancel at Period End, Reactivate (paid plans)
        if (activeSubscription != null &&
            activeSubscription.planId.isNotBlank() &&
            activeSubscription.planId != BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID
        ) {
            item {
                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Subscription Lifecycle & Renewal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Renewal Reminders", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (activeSubscription.cancelAtPeriodEnd) "Cancellation scheduled for end of current cycle."
                                    else "Stay reminded ahead of next cycle reset.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = activeSubscription.autoRenew && !activeSubscription.cancelAtPeriodEnd,
                                onCheckedChange = { enabled ->
                                    onUpdateAutoRenewOrCancel(enabled, !enabled, false)
                                },
                                modifier = Modifier.testTag("auto_renew_switch")
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (activeSubscription.status == SubscriptionStatus.CANCELLED.name || activeSubscription.cancelAtPeriodEnd) {
                                Button(
                                    onClick = { onUpdateAutoRenewOrCancel(true, false, false) },
                                    colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald),
                                    modifier = Modifier.weight(1f).testTag("reactivate_subscription_button")
                                ) {
                                    Icon(Icons.Default.Autorenew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Reactivate")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onUpdateAutoRenewOrCancel(false, true, false) },
                                    modifier = Modifier.weight(1f).testTag("cancel_at_period_end_button")
                                ) {
                                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Cancel at Period End")
                                }
                                OutlinedButton(
                                    onClick = { confirmImmediateCancel = true },
                                    modifier = Modifier.weight(1f).testTag("cancel_immediately_button")
                                ) {
                                    Text("Cancel Now", color = AlertCrimson)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            PaystackBillingOverviewCard(
                setting = platformSetting,
                plans = availablePlans,
                onSelectPlan = { chosen ->
                    paystackVerificationError = null
                    selectedPlanForCheckout = chosen
                }
            )
        }

        item { Text("Available Subscription Plans", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }

        items(availablePlans, key = { it.planId }) { plan ->
            val ent = entitlementsMap[plan.planId]
            val feats = featuresByPlan[plan.planId].orEmpty().sortedBy { it.sortOrder }
            val isCurrent = activeSubscription?.planId == plan.planId ||
                (activeSubscription == null && business.subscriptionPlanCode.equals(plan.planCode, ignoreCase = true))
            val quote = BillingCalculator.calculateProrationQuote(
                currentSub = activeSubscription,
                targetPlan = plan,
                nowMillis = System.currentTimeMillis(),
                prorationEnabled = platformSetting.prorationEnabled
            )
            DynamicPlanCard(
                plan = plan,
                entitlement = ent,
                features = feats,
                isCurrentPlan = isCurrent,
                quote = quote,
                onSelect = {
                    paystackVerificationError = null
                    selectedPlanForCheckout = plan
                }
            )
        }

        item { Text("Paystack Payments & Billing History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (payments.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No subscription payments recorded yet. Choose the Monthly (₦1,500) or Yearly (₦18,000) plan above to pay securely with Paystack and activate immediately.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(payments, key = { it.id }) { pay ->
                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${pay.planName.ifBlank { pay.planCode }}", fontWeight = FontWeight.Bold)
                                    Surface(
                                        color = if (pay.isPaystack()) NigerianEmerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(999.dp)
                                    ) {
                                        Text(
                                            if (pay.isPaystack()) "PAYSTACK" else pay.paymentMethod,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (pay.isPaystack()) NigerianEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text("Ref: ${pay.effectiveReference()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                if (pay.paystackChannel.isNotBlank() || pay.paystackCardSummary.isNotBlank() || pay.paystackBank.isNotBlank()) {
                                    Text(
                                        "Channel: ${listOfNotNull(pay.paystackChannel.takeIf { it.isNotBlank() }?.uppercase(), pay.paystackCardSummary.takeIf { it.isNotBlank() }, pay.paystackBank.takeIf { it.isNotBlank() }).joinToString(" • ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (pay.senderName.isNotBlank()) {
                                    Text("Sender: ${pay.senderName} ${if (pay.senderBank.isNotBlank()) "(${pay.senderBank})" else ""}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(BillingCalculator.formatMinorCurrency(pay.effectiveAmountMinor()), fontWeight = FontWeight.Bold)
                                val statusColor = when (pay.paymentStatus) {
                                    "VERIFIED" -> NigerianEmerald
                                    "REJECTED", "FAILED" -> AlertCrimson
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                Text(pay.paymentStatus, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = statusColor)
                            }
                        }
                        if (pay.paymentStatus in listOf("REJECTED", "FAILED") && pay.rejectionReason.isNotBlank()) {
                            Text("Reason: ${pay.rejectionReason}", color = AlertCrimson, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pay.isPaystack() || pay.receiptImageBase64.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { inspectingPayment = pay },
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (pay.isPaystack()) "Payment Details" else "View Receipt")
                                }
                            }
                            if (pay.paymentStatus == "PENDING") {
                                val targetEmail = platformSetting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }
                                OutlinedButton(
                                    onClick = {
                                        val subject = "ReceiptsNow ${pay.planCode} Paystack Reference - ${pay.businessName.ifBlank { business.tradingName }}"
                                        val body = buildString {
                                            appendLine("Business: ${pay.businessName.ifBlank { business.tradingName }}")
                                            appendLine("Owner: ${pay.ownerName} (${pay.ownerEmail})")
                                            appendLine("Plan: ${pay.planCode} • NGN ${"%,.2f".format(pay.amountNgn)}")
                                            appendLine("Paystack Ref: ${pay.effectiveReference()}")
                                        }
                                        ReportExporter.shareTransferProofViaEmail(
                                            context = context,
                                            toEmail = targetEmail,
                                            subject = subject,
                                            body = body,
                                            receiptImageBase64 = pay.receiptImageBase64,
                                            receiptFileName = pay.receiptFileName.ifBlank { "paystack_reference.txt" }
                                        ).onFailure {
                                            Toast.makeText(context, it.localizedMessage ?: "No email app available.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Email Admin")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (invoices.isNotEmpty()) {
            item { Text("Invoices & Billing Statements", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(invoices, key = { it.id }) { inv ->
                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(inv.invoiceNumber, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(
                                "${inv.planNameSnapshot.ifBlank { inv.planCode }} • ${BillingCalculator.formatMinorCurrency(inv.totalMinor, inv.currency)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        ReportExporter.exportSubscriptionInvoicePdfAndShare(context, inv, business)
                                    }
                                    res.onFailure {
                                        Toast.makeText(context, it.localizedMessage ?: "Invoice export failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.testTag("download_invoice_${inv.id}")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("PDF")
                        }
                    }
                }
            }
        }

        if (planHistory.isNotEmpty()) {
            item { Text("Plan Upgrade & Change History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(planHistory.take(15), key = { it.id }) { hist ->
                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${hist.changeType}: ${hist.toPlanName.ifBlank { hist.toPlanId }}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            if (hist.fromPlanName.isNotBlank()) {
                                Text("From: ${hist.fromPlanName}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (hist.notes.isNotBlank()) {
                                Text(hist.notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(
                            BillingCalculator.formatMinorCurrency(hist.chargedMinor, hist.currency),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DynamicPlanCard(
    plan: SubscriptionPlan,
    entitlement: PlanEntitlement?,
    features: List<PlanFeature>,
    isCurrentPlan: Boolean,
    quote: ProrationQuote,
    onSelect: () -> Unit
) {
    val isHighlighted = plan.planCode.equals("ANNUAL", ignoreCase = true) ||
        plan.planCode.equals("YEARLY", ignoreCase = true) ||
        isCurrentPlan
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (isHighlighted) 2.dp else 1.dp, if (isCurrentPlan) NigerianEmerald else if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${plan.frequencyEnum().label} • ${plan.intervalDays} days${if (plan.trialDays > 0) " • ${plan.trialDays}d trial" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        plan.formattedPrice(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isCurrentPlan) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(999.dp)) {
                            Text("Current Plan", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (plan.description.isNotBlank()) {
                Text(plan.description, style = MaterialTheme.typography.bodySmall)
            }
            if (entitlement != null) {
                val rLabel = if (entitlement.maxReceiptsPerCycle < 0) "Unlimited receipts" else "${entitlement.maxReceiptsPerCycle} receipts/cycle"
                val sLabel = if (entitlement.storageLimitBytes < 0L) "Unlimited storage" else BillingCalculator.formatBytes(entitlement.storageLimitBytes)
                val staffLabel = if (entitlement.maxStaffAccounts < 0) "Unlimited staff" else "${entitlement.maxStaffAccounts} staff"
                Text(
                    "$rLabel  •  $sLabel  •  $staffLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = NigerianEmerald,
                    fontWeight = FontWeight.SemiBold
                )
            }
            features.filter { it.included }.forEach { feat ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = NigerianEmerald, modifier = Modifier.size(14.dp))
                    Text(feat.label, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (quote.prorationCreditMinor > 0L) {
                Text(
                    "${quote.changeType.label} total after credit: ${BillingCalculator.formatMinorCurrency(quote.netPayableMinor, plan.currency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("subscribe_plan_${plan.planCode.lowercase()}_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isHighlighted) NigerianEmerald else MaterialTheme.colorScheme.primary,
                    contentColor = if (isHighlighted) Color.White else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.CreditCard, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        isCurrentPlan -> "Renew via Paystack (${plan.formattedPrice()})"
                        quote.changeType == com.example.data.model.PlanChangeType.UPGRADE -> "Upgrade • Pay ${BillingCalculator.formatMinorCurrency(quote.netPayableMinor, plan.currency)}"
                        quote.changeType == com.example.data.model.PlanChangeType.DOWNGRADE -> "Switch Plan via Paystack"
                        else -> "Pay with Paystack (${plan.formattedPrice()})"
                    }
                )
            }
        }
    }
}

@Composable
fun StorageOfflineBackupScreen(
    business: Business,
    customers: List<Customer>,
    receipts: List<ReceiptRecord>,
    payments: List<SubscriptionPayment>,
    offlineDrafts: List<OfflineDraftEntity>,
    storageUsage: StorageUsage? = null,
    effectiveEntitlements: EffectiveEntitlements? = null,
    onSyncNow: () -> Unit,
    onDeleteDraft: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usedBytes = storageUsage?.usedBytes ?: effectiveEntitlements?.storageUsedBytes ?: business.storageUsedBytes
    val limitBytes = storageUsage?.allocatedBytes ?: effectiveEntitlements?.storageLimitBytes ?: business.storageLimitBytes
    val usedMb = usedBytes / (1024.0 * 1024.0)
    val limitMb = if (limitBytes <= 0L) 500.0 else (limitBytes / (1024.0 * 1024.0))
    val ratio = if (limitBytes < 0L) 0f else (usedMb / limitMb).toFloat().coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item { Text("Cloud Storage, Offline Drafts & Backup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Workspace Cloud Storage", fontWeight = FontWeight.Bold)
                    if (limitBytes >= 0L) {
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(99.dp)),
                            color = if (ratio >= 0.9f) AlertCrimson else MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "${BillingCalculator.formatBytes(usedBytes)} used of ${BillingCalculator.formatBytes(limitBytes)}  •  ${business.receiptsCount} receipts issued",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (storageUsage != null) {
                        Text(
                            "Breakdown: Bank Proofs ${BillingCalculator.formatBytes(storageUsage.bankReceiptsBytes)} • Receipts ${BillingCalculator.formatBytes(storageUsage.generatedReceiptsBytes)} • Branding ${BillingCalculator.formatBytes(storageUsage.brandingBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Continuity Guarantee: Historical receipts, bank proof uploads, and customer records are never deleted when storage limits or expiry dates are reached.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NigerianEmerald
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    ReportExporter.exportJsonBackupAndShare(context, business, customers, receipts, payments)
                                }
                                res.onFailure {
                                    Toast.makeText(context, it.localizedMessage ?: "Backup export failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("export_json_backup_button")
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export Complete Business JSON Backup")
                    }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Offline Receipt Drafts (${offlineDrafts.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Button(onClick = onSyncNow, modifier = Modifier.testTag("manual_sync_drafts_button")) {
                    Icon(Icons.Default.CloudSync, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Sync All")
                }
            }
        }
        if (offlineDrafts.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Text("No local offline drafts queued.", modifier = Modifier.padding(16.dp))
                }
            }
        } else {
            items(offlineDrafts, key = { it.draftId }) { draft ->
                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(draft.customerName.ifBlank { "Unnamed Draft" }, fontWeight = FontWeight.Bold)
                            Text("₦${"%,.2f".format(draft.amount)} • ${draft.syncStatus}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onDeleteDraft(draft.draftId) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Draft")
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun NotificationsScreen(
    notifications: List<AppNotification>,
    announcements: List<PlatformAnnouncement>,
    onMarkRead: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item { Text("Notifications & ReceiptsNow Updates", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (announcements.isNotEmpty()) {
            item { Text("Platform Announcements", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            items(announcements, key = { "ann_${it.id}" }) { ann ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ann.title, fontWeight = FontWeight.Bold)
                        Text(ann.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Text("Workspace Activity Alerts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
        if (notifications.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Text("No workspace notifications yet.", modifier = Modifier.padding(16.dp))
                }
            }
        } else {
            items(notifications, key = { it.id }) { n ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { if (!n.read) onMarkRead(n.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (n.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = if (n.read) Color.Gray else NigerianEmerald)
                        Column {
                            Text(n.title, fontWeight = FontWeight.Bold)
                            Text(n.body, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun HelpAndLegalScreen(
    tickets: List<SupportTicket>,
    platformSetting: PlatformSetting,
    onCreateTicket: (subject: String, category: String, description: String) -> Unit
) {
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item { Text("Help, Support & NDPA 2023 Compliance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.SupportAgent, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Submit Support Ticket to ReceiptsNow", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Text("Support Desk: ${platformSetting.supportEmail}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject *") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Describe your issue *") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            if (subject.isNotBlank() && description.isNotBlank()) {
                                onCreateTicket(subject, "TECHNICAL", description)
                                subject = ""
                                description = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Submit Ticket")
                    }
                }
            }
        }
        items(tickets, key = { it.id }) { t ->
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.subject, fontWeight = FontWeight.Bold)
                        Surface(color = BurnishedGold.copy(alpha = 0.2f), shape = RoundedCornerShape(999.dp)) {
                            Text(t.status, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(t.description, style = MaterialTheme.typography.bodySmall)
                    if (t.adminReply.isNotBlank()) {
                        Text("Support Reply: ${t.adminReply}", style = MaterialTheme.typography.bodySmall, color = NigerianEmerald, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Policy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("NDPA 2023 Privacy, Terms & Receipt Policy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "• Receipts issued through ReceiptsNow are official business-issued payment acknowledgements, not bank statements.\n" +
                            "• Under Nigeria Data Protection Act (NDPA 2023), tenant data is strictly isolated per business workspace.\n" +
                            "• Public QR verification masks sensitive bank account numbers and full customer personal identifiers.\n" +
                            "• To request account deletion or data export, export your JSON backup or contact ${platformSetting.supportEmail}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
