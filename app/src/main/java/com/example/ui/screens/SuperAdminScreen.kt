package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.billing.PaystackPaymentService
import com.example.data.model.BillingCalculator
import com.example.data.model.Business
import com.example.data.model.BusinessSubscription
import com.example.data.model.CustomBusinessEntitlement
import com.example.data.model.MailNotification
import com.example.data.model.MrrArrSummary
import com.example.data.model.PlatformSetting
import com.example.data.model.PlanChangeHistory
import com.example.data.model.PlanEntitlement
import com.example.data.model.PlanFeature
import com.example.data.model.PlanStatus
import com.example.data.model.StorageUsage
import com.example.data.model.SubscriptionEvent
import com.example.data.model.SubscriptionInvoice
import com.example.data.model.SubscriptionPayment
import com.example.data.model.SubscriptionPlan
import com.example.data.model.SupportTicket
import com.example.data.model.UsageQuota
import com.example.ui.components.KpiMetricCard
import com.example.ui.components.SubscriptionReceiptPreviewDialog
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.NigerianEmerald
import com.example.ui.theme.SovereignNavy
import com.example.ui.viewmodel.EmailRelayTestStatus
import com.example.util.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val APPS_SCRIPT_RELAY_TEMPLATE = """function doGet(e) {
  return ContentService
    .createTextOutput(JSON.stringify({ ok: true, status: "ReceiptsNow Gmail Relay active. Use HTTP POST." }))
    .setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  try {
    var raw = (e && e.postData && e.postData.contents) ? e.postData.contents : "";
    if (!raw) {
      return ContentService
        .createTextOutput(JSON.stringify({ ok: false, error: "Empty POST payload" }))
        .setMimeType(ContentService.MimeType.JSON);
    }
    var data = JSON.parse(raw);
    var to = data.toEmail || "info.1803consult@gmail.com";
    var subject = data.subject || ("New ReceiptsNow Transfer - " + (data.businessName || "Subscriber"));
    var body = data.body || ("Business: " + (data.businessName || "") + "\nOwner: " + (data.ownerName || "") + " (" + (data.ownerEmail || "") + ")\nPlan: " + (data.planCode || "") + "\nAmount: NGN " + (data.amountNgn || 0) + "\nRef: " + (data.txRef || ""));
    var opts = { replyTo: data.ownerEmail || to };
    if (data.receiptImageBase64) {
      var cleanB64 = String(data.receiptImageBase64).replace(/^data:[^;]+;base64,/, "");
      var blob = Utilities.newBlob(Utilities.base64Decode(cleanB64), data.receiptMimeType || "image/jpeg", data.receiptFileName || "receipt.jpg");
      opts.attachments = [blob];
    }
    MailApp.sendEmail(to, subject, body, opts);
    return ContentService
      .createTextOutput(JSON.stringify({ ok: true, toEmail: to }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService
      .createTextOutput(JSON.stringify({ ok: false, error: (err && err.message) ? err.message : String(err) }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}"""

@Composable
fun SuperAdminScreen(
    allBusinesses: List<Business>,
    allPayments: List<SubscriptionPayment>,
    allMailNotifications: List<MailNotification>,
    allTickets: List<SupportTicket>,
    platformSetting: PlatformSetting,
    plans: List<SubscriptionPlan> = emptyList(),
    planEntitlements: List<PlanEntitlement> = emptyList(),
    planFeatures: List<PlanFeature> = emptyList(),
    allSubscriptions: List<BusinessSubscription> = emptyList(),
    allInvoices: List<SubscriptionInvoice> = emptyList(),
    allQuotas: List<UsageQuota> = emptyList(),
    allStorageUsages: List<StorageUsage> = emptyList(),
    allPlanChanges: List<PlanChangeHistory> = emptyList(),
    allEvents: List<SubscriptionEvent> = emptyList(),
    allCustomEntitlements: List<CustomBusinessEntitlement> = emptyList(),
    mrrArrSummary: MrrArrSummary = MrrArrSummary(0L, 0L, 0, 0, 0, 0, emptyMap()),
    emailRelayTestStatus: EmailRelayTestStatus? = null,
    onSavePlatformSettings: (PlatformSetting) -> Unit,
    onTestEmailRelay: (PlatformSetting) -> Unit,
    onClearEmailRelayTestStatus: () -> Unit = {},
    onResendPaymentNotification: (SubscriptionPayment) -> Unit,
    onVerifyPayment: (SubscriptionPayment, Boolean) -> Unit,
    onMarkPaymentStatus: (SubscriptionPayment, String, String) -> Unit = { _, _, _ -> },
    onRejectPayment: (SubscriptionPayment, String) -> Unit,
    onSetBusinessStatus: (businessId: String, accountStatus: String) -> Unit,
    onPublishAnnouncement: (title: String, message: String, category: String) -> Unit,
    onReplyTicket: (ticketId: String, status: String, reply: String) -> Unit,
    onResendTicketNotification: (SupportTicket) -> Unit = {},
    onSavePlan: (SubscriptionPlan, PlanEntitlement, List<PlanFeature>) -> Unit = { _, _, _ -> },
    onSetPlanStatus: (SubscriptionPlan, PlanStatus, Boolean) -> Unit = { _, _, _ -> },
    onSaveCustomEntitlement: (Business, CustomBusinessEntitlement) -> Unit = { _, _ -> },
    onSuspendOrRestoreSubscription: (Business, Boolean, String) -> Unit = { _, _, _ -> },
    onRunExpirySweep: () -> Unit = {},
    onPublishLiveUpdate: (versionName: String, versionCode: Int, releaseNotes: String, downloadUrl: String, forceUpdate: Boolean, broadcastAnnouncement: Boolean) -> Unit = { _, _, _, _, _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var searchBiz by remember { mutableStateOf("") }
    var inspectingPayment by remember { mutableStateOf<SubscriptionPayment?>(null) }
    var replyingTicket by remember { mutableStateOf<SupportTicket?>(null) }
    var overridingBusiness by remember { mutableStateOf<Business?>(null) }
    var showRelayGuideDialog by remember { mutableStateOf(false) }

    val activeBizCount = allBusinesses.count { it.isSubscriptionActive() }
    val suspendedCount = allBusinesses.count { it.accountStatus == "SUSPENDED" }
    val pendingPaymentsCount = allPayments.count { it.paymentStatus == "PENDING" }
    val confirmedPaystackCount = allPayments.count { it.paymentStatus == "VERIFIED" && it.isPaystack() }
    val verifiedRevenue = allPayments.filter { it.paymentStatus == "VERIFIED" }.sumOf { it.amountNgn }
    val mailMap = remember(allMailNotifications) { allMailNotifications.associateBy { it.paymentId.ifBlank { it.id } } }

    overridingBusiness?.let { targetBiz ->
        val existingOverride = allCustomEntitlements.firstOrNull { it.businessId == targetBiz.id }
        CustomBusinessOverrideDialog(
            business = targetBiz,
            existing = existingOverride,
            onSave = { ov ->
                onSaveCustomEntitlement(targetBiz, ov)
                overridingBusiness = null
            },
            onDismiss = { overridingBusiness = null }
        )
    }

    if (showRelayGuideDialog) {
        AlertDialog(
            onDismissRequest = { showRelayGuideDialog = false },
            title = { Text("Setup Free Auto-Email to info.1803consult@gmail.com") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "To receive automatic emails with the uploaded receipt image attached whenever a subscriber pays (with zero server cost):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "1. Open script.google.com while signed in as info.1803consult@gmail.com\n" +
                            "2. Create a New Project and paste the script below (includes doGet & doPost).\n" +
                            "3. Click Deploy → New deployment → Web app → Execute as: Me → Who has access: Anyone (required so HTTP 401/403 is avoided).\n" +
                            "4. Authorize Gmail sending when prompted, then copy the Web app URL ending in /exec (do NOT use /edit or /dev).\n" +
                            "5. Paste the /exec URL into 'Email Relay Webhook URL' in Settings, Save, and tap 'Test Email Relay'.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            APPS_SCRIPT_RELAY_TEMPLATE,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Apps Script Email Relay", APPS_SCRIPT_RELAY_TEMPLATE))
                    Toast.makeText(context, "Apps Script code copied!", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Script")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRelayGuideDialog = false }) { Text("Close") }
            }
        )
    }

    inspectingPayment?.let { pay ->
        SubscriptionReceiptPreviewDialog(payment = pay, onDismiss = { inspectingPayment = null })
    }

    replyingTicket?.let { tkt ->
        var replyText by remember(tkt) { mutableStateOf(tkt.adminReply) }
        var ticketStatus by remember(tkt) { mutableStateOf(if (tkt.status == "OPEN") "RESOLVED" else tkt.status) }
        AlertDialog(
            onDismissRequest = { replyingTicket = null },
            title = { Text("Reply: ${tkt.subject}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${tkt.businessName} (${tkt.ownerEmail})", style = MaterialTheme.typography.bodySmall)
                    Text(tkt.description, style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("IN_PROGRESS", "RESOLVED", "CLOSED").forEach { st ->
                            FilterChip(selected = ticketStatus == st, onClick = { ticketStatus = st }, label = { Text(st) })
                        }
                    }
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        label = { Text("Support Reply") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onReplyTicket(tkt.id, ticketStatus, replyText)
                    replyingTicket = null
                }) { Text("Send Reply") }
            },
            dismissButton = { TextButton(onClick = { replyingTicket = null }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(
                        "ReceiptsNow Super Admin Console",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Verified Owner Portal (info.1803consult@gmail.com)",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Overview") })
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(if (pendingPaymentsCount > 0) "Payments ($pendingPaymentsCount)" else "Payments") }
            )
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Plans & Subscriptions") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Businesses") })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Settings") })
            Tab(selected = tab == 5, onClick = { tab = 5 }, text = { Text("Tickets") })
        }

        when (tab) {
            0 -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        KpiMetricCard("Registered Businesses", allBusinesses.size.toString(), "$activeBizCount active • $suspendedCount suspended", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                        KpiMetricCard("Paystack & SaaS Revenue", "₦${"%,.0f".format(verifiedRevenue)}", "$confirmedPaystackCount Paystack confirmed • MRR ${BillingCalculator.formatMinorCurrency(mrrArrSummary.mrrMinor)}", NigerianEmerald, Modifier.weight(1f))
                    }
                }
                if (pendingPaymentsCount > 0) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            border = BorderStroke(1.dp, BurnishedGold)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("$pendingPaymentsCount Paystack Reference(s) Pending Check", fontWeight = FontWeight.Bold)
                                    Text("Tap Review to verify via Paystack API or confirm subscription activation.", style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { tab = 1 }) { Text("Review") }
                            }
                        }
                    }
                }
                item {
                    var annTitle by remember { mutableStateOf("") }
                    var annMsg by remember { mutableStateOf("") }
                    Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Publish Platform Announcement", fontWeight = FontWeight.Bold)
                            }
                            OutlinedTextField(value = annTitle, onValueChange = { annTitle = it }, label = { Text("Announcement Title") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = annMsg, onValueChange = { annMsg = it }, label = { Text("Message to All Businesses") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                            Button(
                                onClick = {
                                    if (annTitle.isNotBlank() && annMsg.isNotBlank()) {
                                        onPublishAnnouncement(annTitle, annMsg, "GENERAL")
                                        annTitle = ""
                                        annMsg = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Publish Announcement") }
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    ReportExporter.exportCsvAndShare(
                                        context = context,
                                        filePrefix = "ReceiptsNow_Businesses",
                                        headers = listOf("ID", "Business", "Owner", "Email", "Sub Status", "Account Status", "Receipts"),
                                        rows = allBusinesses.map { listOf(it.id, it.tradingName, it.ownerName, it.ownerEmail, it.subscriptionStatus, it.accountStatus, it.receiptsCount.toString()) }
                                    )
                                }
                                res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show() }
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Export Businesses CSV")
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    ReportExporter.exportXlsxAndShare(
                                        context = context,
                                        filePrefix = "ReceiptsNow_Paystack_Payments",
                                        sheetTitle = "Paystack Payments",
                                        headers = listOf("Business", "Owner Email", "Method", "Plan", "Amount NGN", "Paystack Ref", "Channel", "Verification Source", "Gateway Response", "Status"),
                                        rows = allPayments.map {
                                            listOf(
                                                it.businessName.ifBlank { it.businessId },
                                                it.ownerEmail,
                                                it.paymentMethod,
                                                it.planName.ifBlank { it.planCode },
                                                it.amountNgn.toString(),
                                                it.effectiveReference(),
                                                it.paystackChannel,
                                                it.verificationSource,
                                                it.paystackGatewayResponse,
                                                it.paymentStatus
                                            )
                                        }
                                    )
                                }
                                res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show() }
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Export Payments XLSX")
                        }
                    }
                }
            }

            1 -> SuperAdminPaymentsTab(
                allPayments = allPayments,
                mailMap = mailMap,
                platformSetting = platformSetting,
                onInspectPayment = { inspectingPayment = it },
                onVerifyPayment = onVerifyPayment,
                onMarkPaymentStatus = onMarkPaymentStatus,
                onRejectPayment = onRejectPayment,
                onResendPaymentNotification = onResendPaymentNotification,
                onShowRelayGuide = { showRelayGuideDialog = true },
                onOpenSettingsTab = { tab = 4 }
            )

            2 -> SuperAdminSubscriptionsTab(
                plans = plans.ifEmpty { BillingCalculator.defaultLaunchPlans() },
                planEntitlements = planEntitlements.ifEmpty { BillingCalculator.defaultLaunchEntitlements() },
                planFeatures = planFeatures.ifEmpty { BillingCalculator.defaultLaunchFeatures() },
                allBusinesses = allBusinesses,
                allSubscriptions = allSubscriptions,
                allInvoices = allInvoices,
                allQuotas = allQuotas,
                allStorageUsages = allStorageUsages,
                allPlanChanges = allPlanChanges,
                allEvents = allEvents,
                mrrArrSummary = mrrArrSummary,
                onSavePlan = onSavePlan,
                onSetPlanStatus = onSetPlanStatus,
                onRunExpirySweep = onRunExpirySweep
            )

            3 -> {
                val quotaByBiz = remember(allQuotas) { allQuotas.associateBy { it.businessId } }
                val storeByBiz = remember(allStorageUsages) { allStorageUsages.associateBy { it.businessId } }
                val subByBiz = remember(allSubscriptions) { allSubscriptions.associateBy { it.businessId } }
                val customByBiz = remember(allCustomEntitlements) { allCustomEntitlements.associateBy { it.businessId } }
                val filtered = remember(allBusinesses, searchBiz) {
                    val q = searchBiz.trim().lowercase()
                    if (q.isBlank()) allBusinesses
                    else allBusinesses.filter {
                        it.tradingName.lowercase().contains(q) ||
                            it.registeredName.lowercase().contains(q) ||
                            it.ownerName.lowercase().contains(q) ||
                            it.ownerEmail.lowercase().contains(q) ||
                            it.id.lowercase().contains(q)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = searchBiz,
                            onValueChange = { searchBiz = it },
                            label = { Text("Search business, owner, or email...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    items(filtered, key = { it.id }) { biz ->
                        val quota = quotaByBiz[biz.id]
                        val store = storeByBiz[biz.id]
                        val sub = subByBiz[biz.id]
                        val hasCustom = customByBiz.containsKey(biz.id)
                        Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(biz.tradingName.ifBlank { biz.registeredName }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    if (hasCustom) {
                                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                                            Text("Custom Override", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                Text("${biz.ownerName} • ${biz.ownerEmail}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Plan: ${sub?.planNameSnapshot?.ifBlank { biz.subscriptionPlanCode } ?: biz.subscriptionPlanCode.ifBlank { "None" }} (${biz.effectiveSubscriptionStatus(sub?.gracePeriodEndMillis ?: 0L).label})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "Receipts: ${quota?.receiptsUsed ?: 0}/${quota?.totalReceiptAllowance ?: "-"} • OCR: ${quota?.ocrUsed ?: 0}/${quota?.ocrAllocated ?: "-"} • Storage: ${BillingCalculator.formatBytes(store?.usedBytes ?: biz.storageUsedBytes)}/${BillingCalculator.formatBytes(store?.allocatedBytes ?: biz.storageLimitBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                ) {
                                    OutlinedButton(onClick = { overridingBusiness = biz }) {
                                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Custom Quotas")
                                    }
                                    if (biz.accountStatus == "ACTIVE" && sub?.status != "SUSPENDED") {
                                        OutlinedButton(onClick = {
                                            onSetBusinessStatus(biz.id, "SUSPENDED")
                                            onSuspendOrRestoreSubscription(biz, true, "Suspended by Super Admin")
                                        }) {
                                            Text("Suspend", color = AlertCrimson)
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                onSetBusinessStatus(biz.id, "ACTIVE")
                                                onSuspendOrRestoreSubscription(biz, false, "Restored by Super Admin")
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald)
                                        ) { Text("Restore") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            4 -> {
                var monthly by remember(platformSetting) { mutableStateOf(platformSetting.monthlyPriceNgn.toString()) }
                var annual by remember(platformSetting) { mutableStateOf(platformSetting.annualPriceNgn.toString()) }
                var storageMb by remember(platformSetting) { mutableStateOf(platformSetting.storageLimitMb.toString()) }
                var graceDays by remember(platformSetting) { mutableStateOf(platformSetting.gracePeriodDays.toString()) }
                var prorationEnabled by remember(platformSetting) { mutableStateOf(platformSetting.prorationEnabled) }
                var defaultRollover by remember(platformSetting) { mutableStateOf(platformSetting.defaultRolloverEnabled) }
                var paystackMonthlyUrl by remember(platformSetting) {
                    mutableStateOf(platformSetting.paystackMonthlyUrl.ifBlank { PaystackPaymentService.DEFAULT_PAYSTACK_MONTHLY_URL })
                }
                var paystackAnnualUrl by remember(platformSetting) {
                    mutableStateOf(platformSetting.paystackAnnualUrl.ifBlank { PaystackPaymentService.DEFAULT_PAYSTACK_ANNUAL_URL })
                }
                var paystackPublicKey by remember(platformSetting) { mutableStateOf(platformSetting.paystackPublicKey) }
                var paystackVerifyEndpoint by remember(platformSetting) { mutableStateOf(platformSetting.paystackVerifyEndpoint) }
                var showLegacyBankFields by remember { mutableStateOf(false) }
                var bankAccName by remember(platformSetting) { mutableStateOf(platformSetting.bankAccountName) }
                var bankAccNumber by remember(platformSetting) { mutableStateOf(platformSetting.bankAccountNumber) }
                var bankName by remember(platformSetting) { mutableStateOf(platformSetting.bankName) }
                var notifEmail by remember(platformSetting) { mutableStateOf(platformSetting.notificationEmail) }
                var emailWebhook by remember(platformSetting) { mutableStateOf(platformSetting.emailNotificationWebhookUrl) }
                var supEmail by remember(platformSetting) { mutableStateOf(platformSetting.supportEmail) }
                var releaseVerName by remember(platformSetting) { mutableStateOf(platformSetting.latestVersionName.ifBlank { BuildConfig.VERSION_NAME }) }
                var releaseVerCode by remember(platformSetting) { mutableStateOf(platformSetting.latestVersionCode.coerceAtLeast(BuildConfig.VERSION_CODE).toString()) }
                var releaseNotesText by remember(platformSetting) { mutableStateOf(platformSetting.releaseNotes) }
                var releaseDownloadUrl by remember(platformSetting) { mutableStateOf(platformSetting.updateDownloadUrl) }
                var releaseForceUpdate by remember(platformSetting) { mutableStateOf(platformSetting.forceUpdate) }
                var releaseBroadcast by remember { mutableStateOf(true) }
                var settingsError by remember { mutableStateOf<String?>(null) }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.NewReleases, contentDescription = null, tint = NigerianEmerald)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Publish Live App Update & Release",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Installed Build: v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE}) • Live Rev #${platformSetting.configRevision}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    "Publishing pushes live pricing, plans, and version metadata immediately and shows the 'Update to Version X.XX' pop-up when users log in. Add a Play Store / APK download link below if shipping a new app binary.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = releaseVerName,
                                        onValueChange = { releaseVerName = it },
                                        label = { Text("Version (e.g. 1.30)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("admin_release_version_name_input")
                                    )
                                    OutlinedTextField(
                                        value = releaseVerCode,
                                        onValueChange = { releaseVerCode = it.filter(Char::isDigit) },
                                        label = { Text("Version Code") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("admin_release_version_code_input")
                                    )
                                }
                                OutlinedTextField(
                                    value = releaseNotesText,
                                    onValueChange = { releaseNotesText = it },
                                    label = { Text("What's New / Release Notes") },
                                    minLines = 2,
                                    modifier = Modifier.fillMaxWidth().testTag("admin_release_notes_input")
                                )
                                OutlinedTextField(
                                    value = releaseDownloadUrl,
                                    onValueChange = { releaseDownloadUrl = it },
                                    label = { Text("Play Store / APK Download URL (Optional)") },
                                    placeholder = { Text("https://play.google.com/store/apps/details?id=...") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("admin_release_url_input")
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Require update before continuing (Force Update)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Switch(checked = releaseForceUpdate, onCheckedChange = { releaseForceUpdate = it })
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Broadcast in-app update announcement", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Switch(checked = releaseBroadcast, onCheckedChange = { releaseBroadcast = it })
                                }
                                Button(
                                    onClick = {
                                        val code = releaseVerCode.toIntOrNull() ?: (BuildConfig.VERSION_CODE + 1)
                                        onPublishLiveUpdate(
                                            releaseVerName,
                                            code,
                                            releaseNotesText,
                                            releaseDownloadUrl,
                                            releaseForceUpdate,
                                            releaseBroadcast
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("admin_publish_live_update_button")
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Publish Live Update", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
                            border = BorderStroke(1.dp, NigerianEmerald.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Payments, contentDescription = null, tint = NigerianEmerald)
                                    Text("Paystack Checkout & Verification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "Paystack shop links used for instant in-app subscription checkout and automatic activation. Configure a server-side verify endpoint for production Paystack API validation.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = paystackMonthlyUrl,
                                    onValueChange = { paystackMonthlyUrl = it },
                                    label = { Text("Paystack Monthly Payment URL") },
                                    placeholder = { Text(PaystackPaymentService.DEFAULT_PAYSTACK_MONTHLY_URL) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("admin_paystack_monthly_url_input")
                                )
                                OutlinedTextField(
                                    value = paystackAnnualUrl,
                                    onValueChange = { paystackAnnualUrl = it },
                                    label = { Text("Paystack Yearly / Annual Payment URL") },
                                    placeholder = { Text(PaystackPaymentService.DEFAULT_PAYSTACK_ANNUAL_URL) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("admin_paystack_annual_url_input")
                                )
                                OutlinedTextField(
                                    value = paystackVerifyEndpoint,
                                    onValueChange = { paystackVerifyEndpoint = it },
                                    label = { Text("Server Paystack Verify Endpoint (Recommended)") },
                                    placeholder = { Text("https://us-central1-...cloudfunctions.net/verifyPaystack") },
                                    supportingText = { Text("Recommended over client-side secret keys so Paystack secret keys never ship in the APK.") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("admin_paystack_verify_endpoint_input")
                                )
                                OutlinedTextField(
                                    value = paystackPublicKey,
                                    onValueChange = { paystackPublicKey = it },
                                    label = { Text("Paystack Public Key (Optional)") },
                                    placeholder = { Text("pk_live_...") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("admin_paystack_public_key_input")
                                )
                            }
                        }
                    }
                    item { Text("Central Plan Pricing, Lifecycle & Legacy Fallback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = monthly, onValueChange = { monthly = it }, label = { Text("Launch Monthly (NGN)") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = annual, onValueChange = { annual = it }, label = { Text("Launch Yearly (NGN)") }, modifier = Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = storageMb, onValueChange = { storageMb = it }, label = { Text("Default Storage (MB)") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = graceDays, onValueChange = { graceDays = it }, label = { Text("Grace Period (Days)") }, modifier = Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable Upgrade/Switch Proration", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = prorationEnabled, onCheckedChange = { prorationEnabled = it })
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Default Rollover Unused Receipts", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = defaultRollover, onCheckedChange = { defaultRollover = it })
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = { showLegacyBankFields = !showLegacyBankFields },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (showLegacyBankFields) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (showLegacyBankFields) "Hide Legacy Bank Details" else "Legacy Bank Account Metadata (Optional)")
                        }
                    }
                    item {
                        AnimatedVisibility(visible = showLegacyBankFields) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = bankAccName, onValueChange = { bankAccName = it }, label = { Text("Legacy Bank Account Name") }, modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = bankAccNumber, onValueChange = { bankAccNumber = it }, label = { Text("Legacy Account Number") }, modifier = Modifier.weight(1f))
                                    OutlinedTextField(value = bankName, onValueChange = { bankName = it }, label = { Text("Legacy Bank Name") }, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(value = notifEmail, onValueChange = { notifEmail = it }, label = { Text("Notification Email") }, modifier = Modifier.fillMaxWidth()) }
                    item {
                        OutlinedTextField(
                            value = emailWebhook,
                            onValueChange = {
                                emailWebhook = it
                                onClearEmailRelayTestStatus()
                            },
                            label = { Text("Email Relay Webhook URL (Apps Script /exec or Cloud Function)") },
                            placeholder = { Text("https://script.google.com/macros/s/.../exec") },
                            supportingText = {
                                Text("Must be a POST-enabled /exec Web App URL ('Who has access: Anyone') or public Cloud Function URL.")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    onTestEmailRelay(
                                        platformSetting.copy(
                                            notificationEmail = notifEmail.trim(),
                                            emailNotificationWebhookUrl = emailWebhook.trim()
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f).testTag("admin_test_email_relay_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Test Email Relay")
                            }
                            OutlinedButton(
                                onClick = { showRelayGuideDialog = true },
                                modifier = Modifier.weight(1f).testTag("admin_email_relay_guide_button")
                            ) {
                                Icon(Icons.Default.MarkEmailRead, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Setup Gmail Relay")
                            }
                        }
                    }
                    if (emailRelayTestStatus != null) {
                        item {
                            val containerColor = when (emailRelayTestStatus.isSuccess) {
                                true -> NigerianEmerald.copy(alpha = 0.14f)
                                false -> MaterialTheme.colorScheme.errorContainer
                                null -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            val textColor = when (emailRelayTestStatus.isSuccess) {
                                true -> NigerianEmerald
                                false -> MaterialTheme.colorScheme.onErrorContainer
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = containerColor),
                                border = BorderStroke(1.dp, textColor.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth().testTag("admin_email_relay_status_card")
                            ) {
                                Text(
                                    text = emailRelayTestStatus.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textColor,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                    item { OutlinedTextField(value = supEmail, onValueChange = { supEmail = it }, label = { Text("Support Desk Email") }, modifier = Modifier.fillMaxWidth()) }
                    if (!settingsError.isNullOrBlank()) {
                        item {
                            Text(
                                text = settingsError!!,
                                color = AlertCrimson,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                val monthlyVal = monthly.replace(",", "").trim().toDoubleOrNull()
                                val annualVal = annual.replace(",", "").trim().toDoubleOrNull()
                                val storageVal = storageMb.trim().toLongOrNull()
                                val graceVal = graceDays.trim().toIntOrNull()
                                if (monthlyVal == null || monthlyVal < 0.0 || annualVal == null || annualVal < 0.0) {
                                    settingsError = "Enter valid non-negative monthly and annual pricing."
                                    return@Button
                                }
                                if (storageVal == null || storageVal < 1L) {
                                    settingsError = "Default storage limit must be at least 1 MB."
                                    return@Button
                                }
                                if (graceVal == null || graceVal !in 0..60) {
                                    settingsError = "Grace period must be between 0 and 60 days."
                                    return@Button
                                }
                                settingsError = null
                                onSavePlatformSettings(
                                    platformSetting.copy(
                                        monthlyPriceNgn = monthlyVal,
                                        annualPriceNgn = annualVal,
                                        storageLimitMb = storageVal,
                                        gracePeriodDays = graceVal,
                                        prorationEnabled = prorationEnabled,
                                        defaultRolloverEnabled = defaultRollover,
                                        paystackMonthlyUrl = paystackMonthlyUrl.trim().ifBlank { PaystackPaymentService.DEFAULT_PAYSTACK_MONTHLY_URL },
                                        paystackAnnualUrl = paystackAnnualUrl.trim().ifBlank { PaystackPaymentService.DEFAULT_PAYSTACK_ANNUAL_URL },
                                        paystackPublicKey = paystackPublicKey.trim(),
                                        paystackVerifyEndpoint = paystackVerifyEndpoint.trim(),
                                        bankAccountName = bankAccName.trim(),
                                        bankAccountNumber = bankAccNumber.trim(),
                                        bankName = bankName.trim(),
                                        notificationEmail = notifEmail.trim(),
                                        emailNotificationWebhookUrl = emailWebhook.trim(),
                                        supportEmail = supEmail.trim()
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("admin_save_settings_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Save Platform Settings")
                        }
                    }
                }
            }

            5 -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(allTickets, key = { it.id }) { t ->
                    val ticketMailRecord = allMailNotifications.firstOrNull { it.paymentId == t.id || it.id == t.id }
                    val ticketMailStatus = (ticketMailRecord?.deliveryStatus ?: t.emailNotificationStatus).ifBlank { "QUEUED" }
                    val ticketMailError = ticketMailRecord?.lastError?.ifBlank { t.emailNotificationError } ?: t.emailNotificationError
                    val mailColor = when (ticketMailStatus) {
                        "SENT" -> NigerianEmerald
                        "FAILED", "PENDING_SMTP_CONFIG" -> AlertCrimson
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${t.subject} (${t.status})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                    Text(
                                        text = "${t.category.ifBlank { "TECHNICAL" }} • ${t.source.ifBlank { "HELP_DESK" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text("${t.businessName} • ${t.ownerName.ifBlank { "User" }} (${t.ownerEmail})", style = MaterialTheme.typography.bodySmall)
                            Text(t.description, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = "Email Alert (${platformSetting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }}): $ticketMailStatus" +
                                    if (ticketMailError.isNotBlank()) " — $ticketMailError" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = mailColor
                            )
                            if (t.adminReply.isNotBlank()) {
                                Text("Admin Reply: ${t.adminReply}", style = MaterialTheme.typography.bodySmall, color = NigerianEmerald)
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            ) {
                                OutlinedButton(onClick = { replyingTicket = t }) { Text("Respond") }
                                if (ticketMailStatus != "SENT" && platformSetting.emailNotificationWebhookUrl.isNotBlank()) {
                                    OutlinedButton(onClick = { onResendTicketNotification(t) }) {
                                        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Send Email")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
