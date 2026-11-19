package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.BillingCalculator
import com.example.data.model.BillingFrequency
import com.example.data.model.Business
import com.example.data.model.BusinessSubscription
import com.example.data.model.CustomBusinessEntitlement
import com.example.data.model.MrrArrSummary
import com.example.data.model.PlanChangeHistory
import com.example.data.model.PlanEntitlement
import com.example.data.model.PlanFeature
import com.example.data.model.PlanStatus
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StorageOverLimitAction
import com.example.data.model.StorageUsage
import com.example.data.model.SubscriptionEvent
import com.example.data.model.SubscriptionInvoice
import com.example.data.model.SubscriptionPlan
import com.example.data.model.UsageQuota
import com.example.ui.components.KpiMetricCard
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.NigerianEmerald
import com.example.data.billing.PaystackPaymentService
import com.example.util.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun SuperAdminSubscriptionsTab(
    plans: List<SubscriptionPlan>,
    planEntitlements: List<PlanEntitlement>,
    planFeatures: List<PlanFeature>,
    allBusinesses: List<Business>,
    allSubscriptions: List<BusinessSubscription>,
    allInvoices: List<SubscriptionInvoice>,
    allQuotas: List<UsageQuota>,
    allStorageUsages: List<StorageUsage>,
    allPlanChanges: List<PlanChangeHistory>,
    allEvents: List<SubscriptionEvent>,
    mrrArrSummary: MrrArrSummary,
    onSavePlan: (SubscriptionPlan, PlanEntitlement, List<PlanFeature>) -> Unit,
    onSetPlanStatus: (SubscriptionPlan, PlanStatus, Boolean) -> Unit,
    onRunExpirySweep: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingPlanPair by remember { mutableStateOf<Pair<SubscriptionPlan, PlanEntitlement?>?>(null) }
    var subSection by remember { mutableStateOf("PLANS") } // PLANS | USAGE | HISTORY | AUDIT

    editingPlanPair?.let { (initialPlan, initialEnt) ->
        val existingFeatures = planFeatures.filter { it.planId == initialPlan.planId }.sortedBy { it.sortOrder }
        PlanEditorDialog(
            initialPlan = initialPlan,
            initialEntitlement = initialEnt ?: PlanEntitlement(planId = initialPlan.planId),
            initialFeatures = existingFeatures,
            onSave = { p, e, feats ->
                onSavePlan(p, e, feats)
                editingPlanPair = null
            },
            onDismiss = { editingPlanPair = null }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                KpiMetricCard(
                    title = "Monthly Recurring (MRR)",
                    value = BillingCalculator.formatMinorCurrency(mrrArrSummary.mrrMinor),
                    subtitle = "${mrrArrSummary.activeSubscribers} active • ${mrrArrSummary.graceSubscribers} grace",
                    accentColor = NigerianEmerald,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    title = "Annual Run Rate (ARR)",
                    value = BillingCalculator.formatMinorCurrency(mrrArrSummary.arrMinor),
                    subtitle = "${mrrArrSummary.expiringSoonSubscribers} expiring • ${mrrArrSummary.expiredSubscribers} expired",
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (mrrArrSummary.subscriberCountByPlan.isNotEmpty()) {
            item {
                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Subscribers by Plan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        mrrArrSummary.subscriberCountByPlan.forEach { (planName, count) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(planName, style = MaterialTheme.typography.bodySmall)
                                Text("$count active workspace(s)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                Button(
                    onClick = {
                        val newId = "plan_${System.currentTimeMillis()}"
                        editingPlanPair = SubscriptionPlan(
                            id = newId,
                            planId = newId,
                            planCode = "",
                            name = "",
                            priceMinor = 500_000L,
                            currency = "NGN",
                            billingFrequency = BillingFrequency.MONTHLY.name,
                            intervalDays = 30,
                            visible = true,
                            status = PlanStatus.ACTIVE.name,
                            sortOrder = plans.size + 1
                        ) to PlanEntitlement(id = newId, planId = newId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald),
                    modifier = Modifier.testTag("admin_create_plan_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Create Plan")
                }
                OutlinedButton(
                    onClick = onRunExpirySweep,
                    modifier = Modifier.testTag("admin_run_expiry_sweep_button")
                ) {
                    Text("Run Expiry / Grace Sweep")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                ReportExporter.exportAdminSubscriptionsCsvAndShare(
                                    context = context,
                                    businesses = allBusinesses,
                                    subscriptions = allSubscriptions,
                                    invoices = allInvoices,
                                    quotas = allQuotas,
                                    storageUsages = allStorageUsages
                                )
                            }
                            res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show() }
                        }
                    },
                    modifier = Modifier.testTag("admin_export_subscriptions_csv_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Export CSV")
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(
                    "PLANS" to "Plans (${plans.size})",
                    "USAGE" to "Quotas & Storage (${allQuotas.size})",
                    "HISTORY" to "Changes (${allPlanChanges.size})",
                    "AUDIT" to "Audit Log (${allEvents.size})"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = subSection == key,
                        onClick = { subSection = key },
                        label = { Text(label) }
                    )
                }
            }
        }

        when (subSection) {
            "PLANS" -> {
                val entMap = planEntitlements.associateBy { it.planId }
                items(plans, key = { it.planId }) { plan ->
                    val ent = entMap[plan.planId]
                    val statusColor = when (plan.status) {
                        PlanStatus.ACTIVE.name -> NigerianEmerald
                        PlanStatus.ARCHIVED.name -> AlertCrimson
                        else -> BurnishedGold
                    }
                    Card(
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${plan.name} (${plan.planCode})", fontWeight = FontWeight.Bold)
                                    Text(
                                        "${plan.formattedPrice()} / ${plan.frequencyEnum().label} (${plan.intervalDays}d) • Visible: ${plan.visible}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(plan.status, color = statusColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                            if (ent != null) {
                                val rTxt = if (ent.maxReceiptsPerCycle < 0) "∞" else ent.maxReceiptsPerCycle.toString()
                                val oTxt = if (ent.maxOcrPerCycle < 0) "∞" else ent.maxOcrPerCycle.toString()
                                Text(
                                    "Receipts/cycle: $rTxt (rollover=${ent.rolloverEnabled}) • OCR: $oTxt • Storage: ${BillingCalculator.formatBytes(ent.storageLimitBytes)} • Staff: ${ent.maxStaffAccounts}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val resolvedPaystack = plan.paystackPaymentUrl.ifBlank {
                                if (plan.frequencyEnum() == BillingFrequency.ANNUAL || plan.intervalDays >= 300) {
                                    PaystackPaymentService.DEFAULT_PAYSTACK_ANNUAL_URL
                                } else {
                                    PaystackPaymentService.DEFAULT_PAYSTACK_MONTHLY_URL
                                }
                            }
                            Text(
                                "Paystack Link: $resolvedPaystack",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = NigerianEmerald
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                OutlinedButton(
                                    onClick = { editingPlanPair = plan to ent },
                                    modifier = Modifier.testTag("admin_edit_plan_${plan.planId}")
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Edit")
                                }
                                if (plan.status != PlanStatus.ACTIVE.name) {
                                    OutlinedButton(onClick = { onSetPlanStatus(plan, PlanStatus.ACTIVE, true) }) {
                                        Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Activate")
                                    }
                                } else {
                                    OutlinedButton(onClick = { onSetPlanStatus(plan, PlanStatus.INACTIVE, false) }) {
                                        Icon(Icons.Default.PauseCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Deactivate")
                                    }
                                }
                                if (plan.status != PlanStatus.ARCHIVED.name) {
                                    OutlinedButton(onClick = { onSetPlanStatus(plan, PlanStatus.ARCHIVED, false) }) {
                                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Archive")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "USAGE" -> {
                val bizMap = allBusinesses.associateBy { it.id }
                val storageMap = allStorageUsages.associateBy { it.businessId }
                items(allQuotas, key = { it.id }) { q ->
                    val biz = bizMap[q.businessId]
                    val st = storageMap[q.businessId]
                    val pct = q.receiptUsagePct
                    val pctColor = when {
                        pct >= 90 -> AlertCrimson
                        pct >= 75 -> BurnishedGold
                        else -> NigerianEmerald
                    }
                    Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(biz?.tradingName?.ifBlank { biz.registeredName } ?: q.businessId, fontWeight = FontWeight.Bold)
                                Text("$pct% Quota Used", color = pctColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                            if (!q.isUnlimitedReceipts && q.totalReceiptAllowance > 0) {
                                LinearProgressIndicator(
                                    progress = { (q.receiptsUsed.toFloat() / q.totalReceiptAllowance.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp)),
                                    color = pctColor
                                )
                            }
                            Text(
                                "Receipts: ${q.receiptsUsed}/${if (q.isUnlimitedReceipts) "∞" else q.totalReceiptAllowance} (Rollover: ${q.rolloverReceipts}) • OCR: ${q.ocrUsed}/${if (q.isUnlimitedOcr) "∞" else q.ocrAllocated}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Storage: ${BillingCalculator.formatBytes(st?.usedBytes ?: biz?.storageUsedBytes ?: 0L)} / ${BillingCalculator.formatBytes(st?.allocatedBytes ?: biz?.storageLimitBytes ?: 0L)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            "HISTORY" -> {
                items(allPlanChanges, key = { it.id }) { ch ->
                    Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${ch.businessName.ifBlank { ch.businessId }} • ${ch.changeType}", fontWeight = FontWeight.Bold)
                                Text("${ch.fromPlanName.ifBlank { "None" }} → ${ch.toPlanName}", style = MaterialTheme.typography.bodySmall)
                                if (ch.notes.isNotBlank()) Text(ch.notes, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(BillingCalculator.formatMinorCurrency(ch.chargedMinor, ch.currency), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            "AUDIT" -> {
                items(allEvents, key = { it.id }) { ev ->
                    Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${ev.action} (${ev.entityType}:${ev.entityId})", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("Actor: ${ev.actorEmail.ifBlank { ev.actorUid }} (${ev.actorRole})", style = MaterialTheme.typography.bodySmall)
                            if (ev.previousValue.isNotBlank()) Text("Prev: ${ev.previousValue}", style = MaterialTheme.typography.labelSmall)
                            if (ev.newValue.isNotBlank()) Text("New: ${ev.newValue}", style = MaterialTheme.typography.labelSmall, color = NigerianEmerald)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun PlanEditorDialog(
    initialPlan: SubscriptionPlan,
    initialEntitlement: PlanEntitlement,
    initialFeatures: List<PlanFeature>,
    onSave: (SubscriptionPlan, PlanEntitlement, List<PlanFeature>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialPlan.name) }
    var planCode by remember { mutableStateOf(initialPlan.planCode) }
    var description by remember { mutableStateOf(initialPlan.description) }
    var priceNgnStr by remember {
        mutableStateOf(
            if (initialPlan.priceMinor % 100L == 0L) (initialPlan.priceMinor / 100L).toString()
            else "%.2f".format(Locale.US, initialPlan.priceMinor / 100.0)
        )
    }
    var currency by remember { mutableStateOf(initialPlan.currency.ifBlank { "NGN" }) }
    var frequency by remember { mutableStateOf(initialPlan.frequencyEnum()) }
    var intervalDaysStr by remember { mutableStateOf(initialPlan.intervalDays.toString()) }
    var trialDaysStr by remember { mutableStateOf(initialPlan.trialDays.toString()) }
    var visible by remember { mutableStateOf(initialPlan.visible) }
    var status by remember { mutableStateOf(initialPlan.statusEnum()) }
    var paystackPaymentUrl by remember { mutableStateOf(initialPlan.paystackPaymentUrl) }

    var maxReceiptsStr by remember { mutableStateOf(initialEntitlement.maxReceiptsPerCycle.toString()) }
    var rolloverEnabled by remember { mutableStateOf(initialEntitlement.rolloverEnabled) }
    var overageReceiptPriceNgnStr by remember { mutableStateOf((initialEntitlement.overageReceiptPriceMinor / 100.0).toString()) }
    var storageMbStr by remember {
        mutableStateOf(
            if (initialEntitlement.storageLimitBytes < 0L) "-1"
            else (initialEntitlement.storageLimitBytes / (1024L * 1024L)).toString()
        )
    }
    var storageAction by remember {
        mutableStateOf(
            runCatching { StorageOverLimitAction.valueOf(initialEntitlement.storageOverLimitAction) }
                .getOrDefault(StorageOverLimitAction.BLOCK_NEW_UPLOADS)
        )
    }
    var maxStaffStr by remember { mutableStateOf(initialEntitlement.maxStaffAccounts.toString()) }
    var maxOcrStr by remember { mutableStateOf(initialEntitlement.maxOcrPerCycle.toString()) }
    var maxCustomersStr by remember { mutableStateOf(initialEntitlement.maxCustomers.toString()) }
    var analyticsEnabled by remember { mutableStateOf(initialEntitlement.analyticsEnabled) }
    var reportsExportEnabled by remember { mutableStateOf(initialEntitlement.reportsExportEnabled) }
    var qrEnabled by remember { mutableStateOf(initialEntitlement.qrVerificationEnabled) }
    var whatsappEnabled by remember { mutableStateOf(initialEntitlement.whatsappSharingEnabled) }
    var emailEnabled by remember { mutableStateOf(initialEntitlement.emailSharingEnabled) }
    var prioritySupport by remember { mutableStateOf(initialEntitlement.prioritySupport) }
    var selectedTemplates by remember { mutableStateOf(initialEntitlement.allowedTemplates.toSet()) }
    var thresholdsStr by remember { mutableStateOf(initialEntitlement.warningThresholds.joinToString(",")) }
    var featuresLines by remember {
        mutableStateOf(initialFeatures.joinToString("\n") { it.label })
    }
    var formError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialPlan.name.isBlank()) "Create Subscription Plan" else "Edit ${initialPlan.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Plan Name *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = planCode, onValueChange = { planCode = it.uppercase() }, label = { Text("Unique Plan Code * (e.g. PRO_QUARTERLY)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = priceNgnStr, onValueChange = { priceNgnStr = it }, label = { Text("Price (NGN)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = currency, onValueChange = { currency = it.uppercase() }, label = { Text("Currency") }, modifier = Modifier.weight(0.6f))
                }
                OutlinedTextField(
                    value = paystackPaymentUrl,
                    onValueChange = { paystackPaymentUrl = it },
                    label = { Text("Paystack Payment Link URL") },
                    placeholder = { Text("https://paystack.shop/pay/...") },
                    supportingText = { Text("Leave blank to use the default Monthly/Yearly Paystack link from Settings.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("plan_editor_paystack_url_input")
                )
                Text("Billing Frequency:", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BillingFrequency.entries.forEach { freq ->
                        FilterChip(
                            selected = frequency == freq,
                            onClick = {
                                frequency = freq
                                if (freq != BillingFrequency.CUSTOM) intervalDaysStr = freq.defaultDays.toString()
                            },
                            label = { Text(freq.label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = intervalDaysStr, onValueChange = { intervalDaysStr = it }, label = { Text("Interval Days") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = trialDaysStr, onValueChange = { trialDaysStr = it }, label = { Text("Trial Days") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = maxReceiptsStr, onValueChange = { maxReceiptsStr = it }, label = { Text("Receipts/Cycle (-1=∞)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = maxOcrStr, onValueChange = { maxOcrStr = it }, label = { Text("OCR/Cycle (-1=∞)") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = storageMbStr, onValueChange = { storageMbStr = it }, label = { Text("Storage MB (-1=∞)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = maxStaffStr, onValueChange = { maxStaffStr = it }, label = { Text("Staff Limit (-1=∞)") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = maxCustomersStr, onValueChange = { maxCustomersStr = it }, label = { Text("Customers (-1=∞)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = overageReceiptPriceNgnStr, onValueChange = { overageReceiptPriceNgnStr = it }, label = { Text("Overage ₦/Receipt") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = thresholdsStr, onValueChange = { thresholdsStr = it }, label = { Text("Warning Thresholds (%)") }, modifier = Modifier.fillMaxWidth())

                Text("Allowed Receipt Templates:", style = MaterialTheme.typography.labelMedium)
                ReceiptTemplateType.entries.forEach { tpl ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = tpl.name in selectedTemplates,
                            onCheckedChange = { checked ->
                                selectedTemplates = if (checked) selectedTemplates + tpl.name else selectedTemplates - tpl.name
                            }
                        )
                        Text(tpl.label, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text("Storage Limit Action:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StorageOverLimitAction.entries.forEach { act ->
                        FilterChip(selected = storageAction == act, onClick = { storageAction = act }, label = { Text(act.label) })
                    }
                }

                @Composable
                fun FlagSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        Switch(checked = checked, onCheckedChange = onChange)
                    }
                }
                FlagSwitch("Plan Visible to Subscribers", visible) { visible = it }
                FlagSwitch("Rollover Unused Receipts", rolloverEnabled) { rolloverEnabled = it }
                FlagSwitch("Analytics & Reporting", analyticsEnabled) { analyticsEnabled = it }
                FlagSwitch("Export CSV / XLSX / PDF", reportsExportEnabled) { reportsExportEnabled = it }
                FlagSwitch("QR Verification", qrEnabled) { qrEnabled = it }
                FlagSwitch("WhatsApp Sharing", whatsappEnabled) { whatsappEnabled = it }
                FlagSwitch("Email Sharing", emailEnabled) { emailEnabled = it }
                FlagSwitch("Priority ReceiptsNow Support", prioritySupport) { prioritySupport = it }

                OutlinedTextField(
                    value = featuresLines,
                    onValueChange = { featuresLines = it },
                    label = { Text("Plan Highlights (one feature per line)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                if (formError != null) {
                    Text(formError!!, color = AlertCrimson, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim()
                    val cleanCode = planCode.trim().uppercase().replace(" ", "_")
                    val priceMajor = priceNgnStr.replace(",", "").trim().toDoubleOrNull()
                    if (cleanName.isBlank() || cleanCode.isBlank() || priceMajor == null || priceMajor < 0.0) {
                        formError = "Enter valid plan name, code, and non-negative price."
                        return@Button
                    }
                    val overageMajor = overageReceiptPriceNgnStr.replace(",", "").trim().toDoubleOrNull()
                    if (overageMajor == null || overageMajor < 0.0) {
                        formError = "Enter a valid non-negative overage receipt price."
                        return@Button
                    }
                    val intervalDays = intervalDaysStr.trim().toIntOrNull()
                    if (intervalDays == null || intervalDays < 1) {
                        formError = "Interval days must be 1 or greater."
                        return@Button
                    }
                    val trialDays = trialDaysStr.trim().toIntOrNull()
                    if (trialDays == null || trialDays < 0) {
                        formError = "Trial days cannot be negative."
                        return@Button
                    }
                    val maxReceipts = maxReceiptsStr.trim().toIntOrNull()
                    val maxOcr = maxOcrStr.trim().toIntOrNull()
                    val maxStaff = maxStaffStr.trim().toIntOrNull()
                    val maxCust = maxCustomersStr.trim().toIntOrNull()
                    val storageMb = storageMbStr.trim().toLongOrNull()
                    if (maxReceipts == null || maxOcr == null || maxStaff == null || maxCust == null || storageMb == null) {
                        formError = "Enter valid whole numbers for quotas and storage (-1 for unlimited)."
                        return@Button
                    }
                    formError = null
                    val priceMinor = (priceMajor * 100.0).roundToLong()
                    val overageMinor = (overageMajor * 100.0).roundToLong()
                    val storageBytes = if (storageMb < 0L) -1L else storageMb * 1024L * 1024L
                    val thresholds = thresholdsStr.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(50, 75, 90, 100) }
                    val templates = selectedTemplates.toList().ifEmpty { listOf(ReceiptTemplateType.STANDARD.name) }

                    val savedPlan = initialPlan.copy(
                        planCode = cleanCode,
                        name = cleanName,
                        description = description.trim(),
                        priceMinor = priceMinor,
                        currency = currency.ifBlank { "NGN" },
                        billingFrequency = frequency.name,
                        intervalDays = intervalDays,
                        trialDays = trialDays,
                        visible = visible,
                        status = status.name,
                        paystackPaymentUrl = paystackPaymentUrl.trim()
                    )
                    val savedEnt = initialEntitlement.copy(
                        planId = savedPlan.planId,
                        maxReceiptsPerCycle = maxReceipts,
                        rolloverEnabled = rolloverEnabled,
                        overageReceiptPriceMinor = overageMinor,
                        storageLimitBytes = storageBytes,
                        storageOverLimitAction = storageAction.name,
                        maxStaffAccounts = maxStaff,
                        maxOcrPerCycle = maxOcr,
                        maxCustomers = maxCust,
                        allowedTemplates = templates,
                        analyticsEnabled = analyticsEnabled,
                        reportsExportEnabled = reportsExportEnabled,
                        qrVerificationEnabled = qrEnabled,
                        whatsappSharingEnabled = whatsappEnabled,
                        emailSharingEnabled = emailEnabled,
                        prioritySupport = prioritySupport,
                        warningThresholds = thresholds
                    )
                    val savedFeats = featuresLines.lines().map { it.trim() }.filter { it.isNotBlank() }.mapIndexed { idx, line ->
                        PlanFeature(
                            id = "${savedPlan.planId}_f_${idx + 1}",
                            planId = savedPlan.planId,
                            featureKey = "feat_${idx + 1}",
                            label = line,
                            valueText = "Included",
                            included = true,
                            sortOrder = idx + 1
                        )
                    }
                    onSave(savedPlan, savedEnt, savedFeats)
                },
                colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald),
                modifier = Modifier.testTag("save_plan_editor_button")
            ) { Text("Save Plan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CustomBusinessOverrideDialog(
    business: Business,
    existing: CustomBusinessEntitlement?,
    onSave: (CustomBusinessEntitlement) -> Unit,
    onDismiss: () -> Unit
) {
    var receiptsStr by remember { mutableStateOf(existing?.overrideReceiptsPerCycle?.toString().orEmpty()) }
    var storageMbStr by remember {
        mutableStateOf(
            existing?.overrideStorageLimitBytes?.let { if (it < 0L) "-1" else (it / (1024L * 1024L)).toString() }.orEmpty()
        )
    }
    var staffStr by remember { mutableStateOf(existing?.overrideMaxStaff?.toString().orEmpty()) }
    var ocrStr by remember { mutableStateOf(existing?.overrideMaxOcr?.toString().orEmpty()) }
    var customersStr by remember { mutableStateOf(existing?.overrideMaxCustomers?.toString().orEmpty()) }
    var rolloverOverride by remember { mutableStateOf(existing?.overrideRolloverEnabled) }
    var notes by remember { mutableStateOf(existing?.overrideNotes.orEmpty()) }
    var formError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Allowances • ${business.tradingName.ifBlank { business.registeredName }}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Leave any field blank to inherit from the business's subscribed plan. Use -1 for Unlimited.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = receiptsStr, onValueChange = { receiptsStr = it; formError = null }, label = { Text("Receipts / Cycle Override") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = storageMbStr, onValueChange = { storageMbStr = it; formError = null }, label = { Text("Storage Limit (MB) Override") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = staffStr, onValueChange = { staffStr = it; formError = null }, label = { Text("Max Staff Override") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ocrStr, onValueChange = { ocrStr = it; formError = null }, label = { Text("OCR Allowance Override") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = customersStr, onValueChange = { customersStr = it; formError = null }, label = { Text("Max Customers Override") }, modifier = Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Rollover:", style = MaterialTheme.typography.bodySmall)
                    FilterChip(selected = rolloverOverride == null, onClick = { rolloverOverride = null }, label = { Text("Plan Default") })
                    FilterChip(selected = rolloverOverride == true, onClick = { rolloverOverride = true }, label = { Text("Allow") })
                    FilterChip(selected = rolloverOverride == false, onClick = { rolloverOverride = false }, label = { Text("Expire") })
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Admin Reason / Note") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (!formError.isNullOrBlank()) {
                    Text(formError!!, color = AlertCrimson, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val receiptsVal = if (receiptsStr.trim().isBlank()) null else receiptsStr.trim().toIntOrNull()
                    val storageMbVal = if (storageMbStr.trim().isBlank()) null else storageMbStr.trim().toLongOrNull()
                    val staffVal = if (staffStr.trim().isBlank()) null else staffStr.trim().toIntOrNull()
                    val ocrVal = if (ocrStr.trim().isBlank()) null else ocrStr.trim().toIntOrNull()
                    val customersVal = if (customersStr.trim().isBlank()) null else customersStr.trim().toIntOrNull()
                    if ((receiptsStr.isNotBlank() && receiptsVal == null) ||
                        (storageMbStr.isNotBlank() && storageMbVal == null) ||
                        (staffStr.isNotBlank() && staffVal == null) ||
                        (ocrStr.isNotBlank() && ocrVal == null) ||
                        (customersStr.isNotBlank() && customersVal == null)
                    ) {
                        formError = "Enter valid whole numbers (or -1 for Unlimited) or leave blank."
                        return@Button
                    }
                    formError = null
                    val storageBytes = storageMbVal?.let { mb ->
                        if (mb < 0L) -1L else mb * 1024L * 1024L
                    }
                    onSave(
                        CustomBusinessEntitlement(
                            id = business.id,
                            businessId = business.id,
                            overrideReceiptsPerCycle = receiptsVal,
                            overrideStorageLimitBytes = storageBytes,
                            overrideMaxStaff = staffVal,
                            overrideMaxOcr = ocrVal,
                            overrideMaxCustomers = customersVal,
                            overrideRolloverEnabled = rolloverOverride,
                            overrideNotes = notes.trim()
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald),
                modifier = Modifier.testTag("save_custom_override_button")
            ) { Text("Apply Override") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
