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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.billing.PaystackPaymentService
import com.example.data.model.BillingCalculator
import com.example.data.model.MailNotification
import com.example.data.model.PlatformSetting
import com.example.data.model.SubscriptionPayment
import com.example.ui.components.KpiMetricCard
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.NigerianEmerald
import com.example.util.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun SuperAdminPaymentsTab(
    allPayments: List<SubscriptionPayment>,
    mailMap: Map<String, MailNotification>,
    platformSetting: PlatformSetting,
    onInspectPayment: (SubscriptionPayment) -> Unit,
    onVerifyPayment: (SubscriptionPayment, Boolean) -> Unit,
    onMarkPaymentStatus: (SubscriptionPayment, String, String) -> Unit,
    onRejectPayment: (SubscriptionPayment, String) -> Unit,
    onResendPaymentNotification: (SubscriptionPayment) -> Unit,
    onShowRelayGuide: () -> Unit,
    onOpenSettingsTab: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var methodFilter by remember { mutableStateOf("ALL") } // ALL | PAYSTACK | LEGACY
    var statusActionTarget by remember { mutableStateOf<Pair<SubscriptionPayment, String>?>(null) }

    val dateFormatter = remember {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Africa/Lagos")
        }
    }

    val verifiedPaystackPayments = remember(allPayments) {
        allPayments.filter { it.paymentStatus == "VERIFIED" && it.isPaystack() }
    }
    val verifiedPaystackRevenueMinor = remember(verifiedPaystackPayments) {
        verifiedPaystackPayments.sumOf { it.effectiveAmountMinor() }
    }
    val apiConfirmedCount = remember(verifiedPaystackPayments) {
        verifiedPaystackPayments.count { it.verificationSource == PaystackPaymentService.SOURCE_PAYSTACK_API }
    }
    val checkoutConfirmedCount = remember(verifiedPaystackPayments) {
        verifiedPaystackPayments.count { it.verificationSource == PaystackPaymentService.SOURCE_CHECKOUT_CONFIRMED }
    }
    val monthlyPaystackCount = remember(verifiedPaystackPayments) {
        verifiedPaystackPayments.count {
            it.planCode.contains("MONTH", ignoreCase = true) ||
                it.planName.contains("MONTH", ignoreCase = true)
        }
    }
    val annualPaystackCount = remember(verifiedPaystackPayments) {
        verifiedPaystackPayments.count {
            it.planCode.contains("ANNUAL", ignoreCase = true) ||
                it.planCode.contains("YEAR", ignoreCase = true) ||
                it.planName.contains("ANNUAL", ignoreCase = true)
        }
    }
    val pendingCount = remember(allPayments) { allPayments.count { it.paymentStatus == "PENDING" } }
    val issueCount = remember(allPayments) {
        allPayments.count { it.paymentStatus in listOf("FAILED", "REFUNDED", "REJECTED") }
    }
    val unconfiguredMailCount = remember(allPayments, mailMap) {
        allPayments.count { p ->
            val st = mailMap[p.id]?.deliveryStatus ?: p.emailNotificationStatus
            st != "SENT"
        }
    }

    val filteredPayments = remember(allPayments, searchQuery, statusFilter, methodFilter) {
        val q = searchQuery.trim().lowercase(Locale.US)
        allPayments.filter { pay ->
            val matchesStatus = statusFilter == "ALL" || pay.paymentStatus.equals(statusFilter, ignoreCase = true)
            val matchesMethod = when (methodFilter) {
                "PAYSTACK" -> pay.isPaystack()
                "LEGACY" -> !pay.isPaystack()
                else -> true
            }
            val matchesQuery = q.isBlank() ||
                pay.businessName.lowercase(Locale.US).contains(q) ||
                pay.ownerName.lowercase(Locale.US).contains(q) ||
                pay.ownerEmail.lowercase(Locale.US).contains(q) ||
                pay.paystackCustomerEmail.lowercase(Locale.US).contains(q) ||
                pay.effectiveReference().lowercase(Locale.US).contains(q) ||
                pay.planName.lowercase(Locale.US).contains(q) ||
                pay.planCode.lowercase(Locale.US).contains(q)
            matchesStatus && matchesMethod && matchesQuery
        }.sortedWith(
            compareBy<SubscriptionPayment> { if (it.paymentStatus == "PENDING") 0 else 1 }
                .thenByDescending {
                    maxOf(
                        it.paidAtMillis,
                        it.verifiedAtMillis,
                        (it.createdAt?.seconds ?: 0L) * 1000L
                    )
                }
        )
    }

    statusActionTarget?.let { (targetPayment, targetStatus) ->
        var reasonInput by remember(targetPayment, targetStatus) { mutableStateOf("") }
        val actionTitle = when (targetStatus) {
            "REFUNDED" -> "Mark Paystack Payment Refunded"
            "FAILED" -> "Mark Payment Failed"
            else -> "Reject Payment Reference"
        }
        AlertDialog(
            onDismissRequest = { statusActionTarget = null },
            title = { Text("$actionTitle • ${targetPayment.businessName.ifBlank { targetPayment.businessId }}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Ref: ${targetPayment.effectiveReference()} • ${BillingCalculator.formatMinorCurrency(targetPayment.effectiveAmountMinor(), targetPayment.currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (targetStatus == "REFUNDED") {
                        Text(
                            "Note: Issue the refund in your Paystack Dashboard as well if not already refunded there.",
                            style = MaterialTheme.typography.labelSmall,
                            color = BurnishedGold
                        )
                    }
                    OutlinedTextField(
                        value = reasonInput,
                        onValueChange = { reasonInput = it },
                        label = { Text("Admin reason / note") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (targetStatus == "REJECTED") {
                            onRejectPayment(targetPayment, reasonInput)
                        } else {
                            onMarkPaymentStatus(targetPayment, targetStatus, reasonInput)
                        }
                        statusActionTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertCrimson)
                ) {
                    Text("Confirm $targetStatus")
                }
            },
            dismissButton = {
                TextButton(onClick = { statusActionTarget = null }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)),
                border = BorderStroke(1.dp, NigerianEmerald.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Payments, contentDescription = null, tint = NigerianEmerald)
                            Column {
                                Text(
                                    "Paystack Confirmed Payments Console",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Automatic checkout activations & Paystack API verification audit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        ReportExporter.exportXlsxAndShare(
                                            context = context,
                                            filePrefix = "ReceiptsNow_Paystack_Payments",
                                            sheetTitle = "Paystack Payments",
                                            headers = listOf(
                                                "Business",
                                                "Owner Email",
                                                "Method",
                                                "Plan",
                                                "Amount NGN",
                                                "Paystack Reference",
                                                "Channel",
                                                "Card / Bank",
                                                "Verification Source",
                                                "Gateway Response",
                                                "Status"
                                            ),
                                            rows = filteredPayments.map { p ->
                                                listOf(
                                                    p.businessName.ifBlank { p.businessId },
                                                    p.ownerEmail,
                                                    p.paymentMethod,
                                                    p.planName.ifBlank { p.planCode },
                                                    "%.2f".format(Locale.US, p.effectiveAmountMinor() / 100.0),
                                                    p.effectiveReference(),
                                                    p.paystackChannel.ifBlank { "-" },
                                                    listOf(p.paystackCardSummary, p.paystackBank).filter { it.isNotBlank() }.joinToString(" • "),
                                                    p.verificationSource.ifBlank { "-" },
                                                    p.paystackGatewayResponse.ifBlank { p.transferNote },
                                                    p.paymentStatus
                                                )
                                            }
                                        )
                                    }
                                    res.onFailure {
                                        Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.testTag("admin_export_paystack_xlsx_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export XLSX (${filteredPayments.size})")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        ReportExporter.exportCsvAndShare(
                                            context = context,
                                            filePrefix = "ReceiptsNow_Paystack_Payments",
                                            headers = listOf(
                                                "Business",
                                                "Owner Email",
                                                "Method",
                                                "Plan",
                                                "Amount NGN",
                                                "Paystack Reference",
                                                "Channel",
                                                "Verification Source",
                                                "Status"
                                            ),
                                            rows = filteredPayments.map { p ->
                                                listOf(
                                                    p.businessName.ifBlank { p.businessId },
                                                    p.ownerEmail,
                                                    p.paymentMethod,
                                                    p.planName.ifBlank { p.planCode },
                                                    "%.2f".format(Locale.US, p.effectiveAmountMinor() / 100.0),
                                                    p.effectiveReference(),
                                                    p.paystackChannel,
                                                    p.verificationSource,
                                                    p.paymentStatus
                                                )
                                            }
                                        )
                                    }
                                    res.onFailure {
                                        Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.testTag("admin_export_paystack_csv_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export CSV")
                        }
                    }
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                KpiMetricCard(
                    title = "Confirmed Paystack",
                    value = BillingCalculator.formatMinorCurrency(verifiedPaystackRevenueMinor, "NGN"),
                    subtitle = "${verifiedPaystackPayments.size} paid ($apiConfirmedCount API • $checkoutConfirmedCount Web)",
                    accentColor = NigerianEmerald,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    title = "Plan Split & Review",
                    value = "$monthlyPaystackCount Mo / $annualPaystackCount Yr",
                    subtitle = "$pendingCount pending • $issueCount failed/refund",
                    accentColor = if (pendingCount > 0) BurnishedGold else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (platformSetting.emailNotificationWebhookUrl.isBlank() && unconfiguredMailCount > 0) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    border = BorderStroke(1.dp, AlertCrimson.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Email Relay Not Configured ($unconfiguredMailCount payment alerts queued)",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Paystack payments arrive automatically here, and configuring your Email Relay Webhook URL sends instant receipts to ${platformSetting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onShowRelayGuide) {
                                Text("Setup Gmail Relay")
                            }
                            Button(onClick = onOpenSettingsTab) {
                                Text("Open Settings")
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Payments") },
                label = { Text("Search business, payer email, plan, or Paystack ref...") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_payments_search_input")
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    val statuses = listOf(
                        "ALL" to "All (${allPayments.size})",
                        "VERIFIED" to "Verified (${allPayments.count { it.paymentStatus == "VERIFIED" }})",
                        "PENDING" to "Pending ($pendingCount)",
                        "FAILED" to "Failed (${allPayments.count { it.paymentStatus == "FAILED" }})",
                        "REFUNDED" to "Refunded (${allPayments.count { it.paymentStatus == "REFUNDED" }})",
                        "REJECTED" to "Rejected (${allPayments.count { it.paymentStatus == "REJECTED" }})"
                    )
                    statuses.forEach { (key, label) ->
                        FilterChip(
                            selected = statusFilter == key,
                            onClick = { statusFilter = key },
                            label = { Text(label) }
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        "ALL" to "All Gateways",
                        "PAYSTACK" to "Paystack Only (${allPayments.count { it.isPaystack() }})",
                        "LEGACY" to "Legacy Transfers (${allPayments.count { !it.isPaystack() }})"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = methodFilter == key,
                            onClick = { methodFilter = key },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        if (filteredPayments.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Payments,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "No subscription payments match your filters.",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Paystack checkout completions appear here immediately upon subscriber payment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredPayments, key = { it.id }) { pay ->
                PaystackAdminTransactionCard(
                    payment = pay,
                    mailRecord = mailMap[pay.id],
                    platformSetting = platformSetting,
                    dateFormatter = dateFormatter,
                    onCopyReference = { ref ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Paystack Reference", ref))
                        Toast.makeText(context, "Reference copied: $ref", Toast.LENGTH_SHORT).show()
                    },
                    onInspect = { onInspectPayment(pay) },
                    onVerifyViaApi = { onVerifyPayment(pay, true) },
                    onApproveManual = { onVerifyPayment(pay, false) },
                    onResendEmail = { onResendPaymentNotification(pay) },
                    onPromptStatusChange = { targetStatus ->
                        statusActionTarget = pay to targetStatus
                    }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PaystackAdminTransactionCard(
    payment: SubscriptionPayment,
    mailRecord: MailNotification?,
    platformSetting: PlatformSetting,
    dateFormatter: SimpleDateFormat,
    onCopyReference: (String) -> Unit,
    onInspect: () -> Unit,
    onVerifyViaApi: () -> Unit,
    onApproveManual: () -> Unit,
    onResendEmail: () -> Unit,
    onPromptStatusChange: (String) -> Unit
) {
    val mailStatus = mailRecord?.deliveryStatus ?: payment.emailNotificationStatus
    val statusColor = when (payment.paymentStatus) {
        "VERIFIED" -> NigerianEmerald
        "PENDING" -> BurnishedGold
        else -> AlertCrimson
    }
    val ref = payment.effectiveReference()
    val timestampMs = listOf(
        payment.paidAtMillis,
        payment.verifiedAtMillis,
        (payment.createdAt?.seconds ?: 0L) * 1000L
    ).firstOrNull { it > 0L } ?: 0L

    val sourceBadgeLabel = when (payment.verificationSource) {
        PaystackPaymentService.SOURCE_PAYSTACK_API -> "Paystack API"
        PaystackPaymentService.SOURCE_CHECKOUT_CONFIRMED -> "Checkout Auto-Confirmed"
        PaystackPaymentService.SOURCE_ADMIN_VERIFIED -> "Admin Confirmed"
        PaystackPaymentService.SOURCE_MANUAL_UNVERIFIED -> "Manual Ref Review"
        else -> if (payment.isPaystack()) "Paystack" else "Bank Transfer"
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = if (payment.paymentStatus == "PENDING") 1.5.dp else 1.dp,
            color = if (payment.paymentStatus == "PENDING") BurnishedGold else statusColor.copy(alpha = 0.35f)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = payment.businessName.ifBlank { payment.businessId },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${payment.ownerName.ifBlank { "Owner" }} • ${payment.paystackCustomerEmail.ifBlank { payment.ownerEmail }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = BillingCalculator.formatMinorCurrency(payment.effectiveAmountMinor(), payment.currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (payment.paymentStatus == "VERIFIED") NigerianEmerald else MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Surface(
                    color = if (payment.isPaystack()) NigerianEmerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(99.dp)
                ) {
                    Text(
                        text = if (payment.isPaystack()) "PAYSTACK" else "LEGACY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (payment.isPaystack()) NigerianEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Surface(
                    color = statusColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(99.dp)
                ) {
                    Text(
                        text = payment.paymentStatus,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(99.dp)
                ) {
                    Text(
                        text = sourceBadgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Plan: ${payment.planName.ifBlank { payment.planCode }}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Ref: ${ref.ifBlank { "-" }}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (ref.isNotBlank()) {
                            IconButton(
                                onClick = { onCopyReference(ref) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy Paystack Reference",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (payment.isPaystack()) {
                        val channelMeta = listOfNotNull(
                            payment.paystackChannel.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
                            payment.paystackCardSummary.takeIf { it.isNotBlank() },
                            payment.paystackBank.takeIf { it.isNotBlank() }
                        ).joinToString(" • ")
                        if (channelMeta.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.CreditCard,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(channelMeta, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (payment.paystackTransactionId.isNotBlank()) {
                            Text(
                                text = "Paystack Tx ID: ${payment.paystackTransactionId}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (payment.paystackGatewayResponse.isNotBlank()) {
                            Text(
                                text = "Gateway: ${payment.paystackGatewayResponse}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "Sender: ${payment.senderName.ifBlank { "-" }} (${payment.senderBank.ifBlank { "-" }}) -> ${payment.bankName} ${payment.bankAccountNumber}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (timestampMs > 0L) {
                        Text(
                            text = "Timestamp: ${dateFormatter.format(Date(timestampMs))} WAT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (payment.rejectionReason.isNotBlank()) {
                Text(
                    text = "Admin Note / Reason: ${payment.rejectionReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertCrimson
                )
            }

            val mailColor = when (mailStatus) {
                "SENT" -> NigerianEmerald
                "FAILED", "PENDING_SMTP_CONFIG" -> AlertCrimson
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = "Email Alert (${platformSetting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }}): $mailStatus" +
                    if (!mailRecord?.lastError.isNullOrBlank()) " — ${mailRecord?.lastError}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = mailColor
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                if (payment.paymentStatus != "VERIFIED") {
                    Button(
                        onClick = if (payment.isPaystack()) onVerifyViaApi else onApproveManual,
                        colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald),
                        modifier = Modifier.testTag("verify_payment_${payment.id}")
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (payment.isPaystack()) "Verify & Activate" else "Approve")
                    }
                    if (payment.isPaystack()) {
                        OutlinedButton(
                            onClick = onApproveManual,
                            modifier = Modifier.testTag("manual_activate_payment_${payment.id}")
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Force Activate")
                        }
                    }
                } else if (payment.isPaystack() && payment.verificationSource != PaystackPaymentService.SOURCE_PAYSTACK_API) {
                    OutlinedButton(
                        onClick = onVerifyViaApi,
                        modifier = Modifier.testTag("api_recheck_payment_${payment.id}")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Check Paystack API")
                    }
                }

                OutlinedButton(onClick = onInspect) {
                    Icon(
                        if (payment.isPaystack()) Icons.Default.Info else Icons.Default.Receipt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (payment.isPaystack()) "Inspect Details" else "View Receipt")
                }

                if (platformSetting.emailNotificationWebhookUrl.isNotBlank() && mailStatus != "SENT") {
                    OutlinedButton(onClick = onResendEmail) {
                        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Send Email")
                    }
                }

                if (payment.paymentStatus == "PENDING") {
                    OutlinedButton(onClick = { onPromptStatusChange(if (payment.isPaystack()) "FAILED" else "REJECTED") }) {
                        Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp), tint = AlertCrimson)
                        Spacer(Modifier.width(4.dp))
                        Text(if (payment.isPaystack()) "Mark Failed" else "Reject", color = AlertCrimson)
                    }
                } else if (payment.paymentStatus == "VERIFIED") {
                    OutlinedButton(onClick = { onPromptStatusChange("REFUNDED") }) {
                        Text("Refund", color = AlertCrimson)
                    }
                }
            }
        }
    }
}
