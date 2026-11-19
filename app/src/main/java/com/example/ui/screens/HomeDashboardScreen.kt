package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.OfflineDraftEntity
import com.example.data.model.BillingCalculator
import com.example.data.model.Business
import com.example.data.model.Customer
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.PlatformAnnouncement
import com.example.data.model.ReceiptRecord
import com.example.data.model.StaffRole
import com.example.data.model.VerificationStatus
import com.example.ui.components.BrandCrestBadge
import com.example.ui.components.KpiMetricCard
import com.example.ui.components.SubscriptionStatusBanner
import com.example.ui.components.VerificationStatusChip
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.ForestEmerald
import com.example.ui.theme.ForestEmeraldDeep
import com.example.ui.theme.ForestEmeraldMid
import com.example.ui.theme.LimeProgressEnd
import com.example.ui.theme.LimeProgressStart
import java.util.Calendar
import java.util.TimeZone

enum class DashboardTimeFilter(val label: String) {
    TODAY("Today"),
    WEEK("This Week"),
    MONTH("This Month"),
    ALL("All Time")
}

private data class QuickCategoryAction(
    val label: String,
    val icon: ImageVector,
    val testTag: String? = null,
    val onClick: () -> Unit
)

@Composable
fun HomeDashboardScreen(
    business: Business,
    role: StaffRole,
    receipts: List<ReceiptRecord>,
    customers: List<Customer>,
    announcements: List<PlatformAnnouncement>,
    pendingDrafts: List<OfflineDraftEntity>,
    isOnline: Boolean,
    effectiveEntitlements: EffectiveEntitlements? = null,
    onGenerateReceipt: () -> Unit,
    onOpenAiAssistant: () -> Unit = {},
    onOpenCalculator: () -> Unit = {},
    onOpenVerificationQueue: () -> Unit,
    onOpenCustomers: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenBranding: () -> Unit = {},
    onOpenBilling: () -> Unit,
    onOpenQrVerify: () -> Unit,
    onSyncOfflineDrafts: () -> Unit,
    onSelectReceipt: (ReceiptRecord) -> Unit
) {
    var timeFilter by remember { mutableStateOf(DashboardTimeFilter.ALL) }

    val filteredReceipts = remember(receipts, timeFilter) {
        val tz = TimeZone.getTimeZone("Africa/Lagos")
        val nowCal = Calendar.getInstance(tz)
        val cutoffMillis = when (timeFilter) {
            DashboardTimeFilter.TODAY -> {
                nowCal.set(Calendar.HOUR_OF_DAY, 0)
                nowCal.set(Calendar.MINUTE, 0)
                nowCal.set(Calendar.SECOND, 0)
                nowCal.set(Calendar.MILLISECOND, 0)
                nowCal.timeInMillis
            }
            DashboardTimeFilter.WEEK -> {
                nowCal.set(Calendar.HOUR_OF_DAY, 0)
                nowCal.set(Calendar.MINUTE, 0)
                nowCal.set(Calendar.SECOND, 0)
                nowCal.set(Calendar.MILLISECOND, 0)
                nowCal.add(Calendar.DAY_OF_YEAR, -7)
                nowCal.timeInMillis
            }
            DashboardTimeFilter.MONTH -> {
                nowCal.set(Calendar.DAY_OF_MONTH, 1)
                nowCal.set(Calendar.HOUR_OF_DAY, 0)
                nowCal.set(Calendar.MINUTE, 0)
                nowCal.set(Calendar.SECOND, 0)
                nowCal.set(Calendar.MILLISECOND, 0)
                nowCal.timeInMillis
            }
            DashboardTimeFilter.ALL -> 0L
        }
        if (cutoffMillis == 0L) receipts else receipts.filter {
            val ms = it.createdAt?.toDate()?.time ?: System.currentTimeMillis()
            ms >= cutoffMillis
        }
    }

    val verifiedReceipts = filteredReceipts.filter { it.verificationStatus == VerificationStatus.VERIFIED.name }
    val pendingReceipts = filteredReceipts.filter { it.verificationStatus == VerificationStatus.PENDING_VERIFICATION.name }
    val flaggedReceipts = filteredReceipts.filter {
        it.verificationStatus == VerificationStatus.REJECTED.name ||
            it.verificationStatus == VerificationStatus.REVERSED.name ||
            it.verificationStatus == VerificationStatus.CANCELLED.name
    }

    val verifiedRevenue = verifiedReceipts.sumOf { it.amountPaid }
    val pendingAmount = pendingReceipts.sumOf { it.amountPaid }

    val quickActions = remember(role, pendingReceipts.size) {
        buildList {
            if (role.canGenerateReceipts()) {
                add(QuickCategoryAction("Issue", Icons.Default.AddCircle, "home_tile_generate", onGenerateReceipt))
            }
            add(QuickCategoryAction("AI Bot", Icons.Default.AutoAwesome, "home_tile_ai_bot", onOpenAiAssistant))
            add(QuickCategoryAction("Day Tally", Icons.Default.Calculate, "home_tile_calculator", onOpenCalculator))
            if (role.canVerifyTransactions()) {
                add(QuickCategoryAction("Verify (${pendingReceipts.size})", Icons.Default.VerifiedUser, null, onOpenVerificationQueue))
            }
            add(QuickCategoryAction("QR Scan", Icons.Default.QrCodeScanner, null, onOpenQrVerify))
            if (role.canManageCustomers()) {
                add(QuickCategoryAction("Clients", Icons.Default.People, null, onOpenCustomers))
            }
            if (role.canViewAnalyticsAndReports()) {
                add(QuickCategoryAction("Reports", Icons.Default.Analytics, null, onOpenReports))
            }
            if (role.canManageStaffAndSubscription()) {
                add(QuickCategoryAction("Billing", Icons.Default.AccountBalance, null, onOpenBilling))
            }
            if (role.canManageSettingsAndBranding()) {
                add(QuickCategoryAction("Brand", Icons.Default.Palette, null, onOpenBranding))
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // EMERALD FINTECH HERO CARD
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(ForestEmeraldDeep, ForestEmerald, ForestEmeraldMid),
                            start = Offset.Zero,
                            end = Offset(900f, 700f)
                        )
                    )
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.06f),
                        radius = size.maxDimension * 0.42f,
                        center = Offset(size.width * 0.92f, size.height * 0.14f)
                    )
                    drawCircle(
                        color = Color(0xFF14D990).copy(alpha = 0.10f),
                        radius = size.maxDimension * 0.25f,
                        center = Offset(size.width * 0.10f, size.height * 0.92f)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BrandCrestBadge(business = business, sizeDp = 46)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = business.tradingName.ifBlank { business.registeredName },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${role.displayName}  •  ${business.receiptPrefix.ifBlank { "RCN" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            color = if (isOnline) Color(0xFF14D990).copy(alpha = 0.20f) else Color(0xFFDC2626).copy(alpha = 0.35f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isOnline) Color(0xFF34D399).copy(alpha = 0.55f) else Color(0xFFFCA5A5).copy(alpha = 0.55f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isOnline) Color(0xFF34D399) else Color(0xFFF87171))
                                )
                                Text(
                                    text = if (isOnline) "Live" else "Offline",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Verified Business Revenue",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "₦${"%,.2f".format(verifiedRevenue)}",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = business.defaultCurrency.ifBlank { "NGN" },
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Text(
                            text = "${verifiedReceipts.size} verified  •  ${pendingReceipts.size} pending  •  ${receipts.size} total receipts",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.80f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            onClick = onOpenAiAssistant,
                            color = Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("home_ai_assistant_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "AI Fast Bot",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (role.canGenerateReceipts()) {
                            Button(
                                onClick = onGenerateReceipt,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = ForestEmeraldDeep
                                ),
                                modifier = Modifier
                                    .weight(1.1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("home_generate_receipt_button")
                            ) {
                                Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Issue Receipt",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }

        // QUICK CATEGORIES TILE GRID (4-column rounded mint tiles matching reference UI)
        if (quickActions.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Quick Actions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    quickActions.chunked(4).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { item ->
                                QuickTileCell(
                                    action = item,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(4 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        item {
            SubscriptionStatusBanner(
                business = business,
                gracePeriodEndMillis = effectiveEntitlements?.gracePeriodEndMillis ?: 0L,
                onManageBilling = onOpenBilling
            )
        }

        if (effectiveEntitlements != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${effectiveEntitlements.planName} Plan Usage",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                val rSummary = if (effectiveEntitlements.maxReceiptsPerCycle < 0) {
                                    "${effectiveEntitlements.receiptsUsed} receipts issued (Unlimited)"
                                } else {
                                    "${effectiveEntitlements.receiptsUsed} / ${effectiveEntitlements.totalReceiptAllowance} receipts • ${effectiveEntitlements.receiptsRemaining} left"
                                }
                                Text(
                                    rSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (effectiveEntitlements.isReceiptQuotaExceeded) AlertCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                onClick = onOpenBilling,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = "Manage",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        if (effectiveEntitlements.maxReceiptsPerCycle > 0) {
                            val ratio = (effectiveEntitlements.receiptsUsed.toFloat() / effectiveEntitlements.totalReceiptAllowance.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                            GradientCapsuleProgressBar(fraction = ratio)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val ocrSummary = if (effectiveEntitlements.maxOcrPerCycle < 0) {
                                "OCR: Unlimited"
                            } else {
                                "OCR: ${effectiveEntitlements.ocrUsed}/${effectiveEntitlements.maxOcrPerCycle}"
                            }
                            Text(
                                ocrSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Storage: ${BillingCalculator.formatBytes(effectiveEntitlements.storageUsedBytes)} / ${BillingCalculator.formatBytes(effectiveEntitlements.storageLimitBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (effectiveEntitlements.overLimitWarnings.isNotEmpty()) AlertCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (pendingDrafts.any { it.syncStatus != "SYNCED" }) {
            val unsynced = pendingDrafts.count { it.syncStatus != "SYNCED" }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    border = BorderStroke(1.dp, BurnishedGold),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudSync, contentDescription = "Offline Sync", tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Column {
                                Text("$unsynced Offline Draft(s) Pending Sync", fontWeight = FontWeight.Bold)
                                Text("Will auto-sync when online or tap Sync Now.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = onSyncOfflineDrafts) {
                            Text("Sync Now", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        announcements.firstOrNull { it.status == "PUBLISHED" }?.let { ann ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Campaign, contentDescription = "Platform Announcement", tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(ann.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(ann.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DashboardTimeFilter.entries.forEach { f ->
                    FilterChip(
                        selected = timeFilter == f,
                        onClick = { timeFilter = f },
                        shape = RoundedCornerShape(14.dp),
                        label = { Text(f.label) }
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    KpiMetricCard(
                        title = "Verified Revenue",
                        value = "₦${"%,.2f".format(verifiedRevenue)}",
                        subtitle = "${verifiedReceipts.size} verified receipts",
                        accentColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    KpiMetricCard(
                        title = "Pending Check",
                        value = "₦${"%,.2f".format(pendingAmount)}",
                        subtitle = "${pendingReceipts.size} awaiting check",
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    KpiMetricCard(
                        title = "Customers (CRM)",
                        value = customers.size.toString(),
                        subtitle = "Active business directory",
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    KpiMetricCard(
                        title = "Rejected / Rev",
                        value = flaggedReceipts.size.toString(),
                        subtitle = "Excluded from revenue",
                        accentColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Receipt Verification Mix", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val totalCount = filteredReceipts.size.coerceAtLeast(1)
                    val trackColor = MaterialTheme.colorScheme.surfaceVariant
                    val verifiedColor = MaterialTheme.colorScheme.secondary
                    val pendingColor = MaterialTheme.colorScheme.tertiary
                    val flaggedColor = MaterialTheme.colorScheme.error
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(999.dp))
                    ) {
                        val vW = size.width * (verifiedReceipts.size.toFloat() / totalCount)
                        val pW = size.width * (pendingReceipts.size.toFloat() / totalCount)
                        val fW = size.width * (flaggedReceipts.size.toFloat() / totalCount)
                        drawRoundRect(trackColor, cornerRadius = CornerRadius(24f, 24f))
                        if (vW > 0f) drawRect(verifiedColor, Offset.Zero, Size(vW, size.height))
                        if (pW > 0f) drawRect(pendingColor, Offset(vW, 0f), Size(pW, size.height))
                        if (fW > 0f) drawRect(flaggedColor, Offset(vW + pW, 0f), Size(fW, size.height))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LegendDot("Verified (${verifiedReceipts.size})", verifiedColor)
                        LegendDot("Pending (${pendingReceipts.size})", pendingColor)
                        LegendDot("Flagged (${flaggedReceipts.size})", flaggedColor)
                    }
                }
            }
        }

        item {
            Text("Recent Receipts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (filteredReceipts.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("No receipts issued yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Upload a Nigerian bank transfer proof or POS slip to extract transaction details with AI and issue your first branded receipt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredReceipts.take(12), key = { it.id }) { receipt ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectReceipt(receipt) }
                        .testTag("dashboard_receipt_${receipt.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(receipt.customerName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "${receipt.receiptNumber}  •  ${receipt.paymentPurpose}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "₦${"%,.2f".format(receipt.amountPaid)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            VerificationStatusChip(receipt.verificationStatus)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun QuickTileCell(
    action: QuickCategoryAction,
    modifier: Modifier = Modifier
) {
    val tileModifier = modifier
        .let { m -> if (action.testTag != null) m.testTag(action.testTag) else m }
    Column(
        modifier = tileModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            onClick = action.onClick,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(vertical = 14.dp)
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GradientCapsuleProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(999.dp))
    ) {
        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
        )
        if (clamped > 0f) {
            val fillWidth = (size.width * clamped).coerceAtLeast(size.height)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(LimeProgressStart, LimeProgressEnd)
                ),
                size = Size(fillWidth, size.height),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
            )
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(color = color, shape = RoundedCornerShape(4.dp), modifier = Modifier.size(10.dp)) {}
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
