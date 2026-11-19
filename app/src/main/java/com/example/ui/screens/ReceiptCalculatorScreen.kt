package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Business
import com.example.data.model.DailyLedgerSheet
import com.example.data.model.LedgerCalculationSummary
import com.example.data.model.LedgerCategory
import com.example.data.model.LedgerEntryItem
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.ForestEmerald
import com.example.ui.theme.ForestEmeraldDeep
import com.example.ui.theme.ForestEmeraldMid
import com.example.ui.theme.MintVibrant
import com.example.ui.theme.NigerianEmerald
import com.example.util.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptCalculatorScreen(
    business: Business,
    title: String,
    ledgerDate: String,
    entries: List<LedgerEntryItem>,
    summary: LedgerCalculationSummary,
    savedSheets: List<DailyLedgerSheet>,
    isBusy: Boolean,
    progressText: String?,
    errorText: String?,
    onTitleChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onUploadBatchUris: (List<Uri>) -> Unit,
    onCaptureCameraBitmap: (Bitmap) -> Unit,
    onImportTodaysIssued: () -> Unit,
    onUpsertEntry: (LedgerEntryItem) -> Unit,
    onRemoveEntry: (String) -> Unit,
    onClearDraft: () -> Unit,
    onSaveSheet: () -> Unit,
    onLoadSheet: (DailyLedgerSheet) -> Unit,
    onDeleteSheet: (String) -> Unit,
    onDismissError: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedCategoryFilter by remember { mutableStateOf<LedgerCategory?>(null) }
    var editingEntry by remember { mutableStateOf<LedgerEntryItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var exportStatusMsg by remember { mutableStateOf<String?>(null) }

    val multiImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) onUploadBatchUris(uris)
    }

    val multiDocPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onUploadBatchUris(uris)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) onCaptureCameraBitmap(bitmap)
    }

    val filteredEntries = remember(entries, selectedCategoryFilter) {
        if (selectedCategoryFilter == null) entries
        else entries.filter { it.categoryEnum() == selectedCategoryFilter }
    }

    if (showAddDialog || editingEntry != null) {
        LedgerEntryEditorDialog(
            initial = editingEntry,
            defaultCurrency = business.defaultCurrency.ifBlank { "NGN" },
            defaultDate = ledgerDate,
            onSave = { item ->
                onUpsertEntry(item)
                editingEntry = null
                showAddDialog = false
            },
            onDismiss = {
                editingEntry = null
                showAddDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // HERO BREAKDOWN DASHBOARD CARD
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(ForestEmeraldDeep, ForestEmerald, ForestEmeraldMid)
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "End-of-Day Profit & Expense Tally",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "Upload bank, POS, fuel & supplier receipts — AI separates Sales, Purchases & Expenditure automatically.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.80f)
                            )
                        }
                        Surface(
                            color = MintVibrant.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                "${summary.entryCount} items",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                if (summary.netProfit >= 0.0) "Net Profit" else "Net Deficit / Loss",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                            Text(
                                text = "₦${"%,.2f".format(summary.netProfit)}",
                                fontSize = 27.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (summary.netProfit >= 0.0) Color(0xFF6EE7B7) else Color(0xFFFCA5A5)
                            )
                        }
                        Surface(
                            color = Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Margin", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.78f))
                                Text(
                                    "${"%.1f".format(summary.profitMarginPercent)}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // 4-Metric Mini Grid inside Hero
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HeroTallyPill("Sales", summary.totalSales, Color(0xFF34D399), Modifier.weight(1f))
                        HeroTallyPill("Purchases", summary.totalPurchases, Color(0xFF60A5FA), Modifier.weight(1f))
                        HeroTallyPill("Expenses", summary.totalExpenditure, Color(0xFFFBBF24), Modifier.weight(1f))
                        HeroTallyPill("Gross Profit", summary.grossProfit, Color.White, Modifier.weight(1f))
                    }
                }
            }
        }

        // BATCH UPLOAD & IMPORT CONTROLS
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "1. Upload All Today's Receipts",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                multiImagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("calculator_upload_images_button")
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Batch Images")
                        }
                        OutlinedButton(
                            onClick = { multiDocPicker.launch(arrayOf("application/pdf", "image/*")) },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("calculator_upload_pdfs_button")
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("PDFs / Docs")
                        }
                        OutlinedButton(
                            onClick = { cameraLauncher.launch(null) },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("calculator_camera_button")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Snap Slip")
                        }
                        OutlinedButton(
                            onClick = onImportTodaysIssued,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("calculator_import_issued_button")
                        ) {
                            Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Today's Receipts")
                        }
                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("calculator_add_manual_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Manual Item")
                        }
                    }

                    if (isBusy) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = progressText ?: "Analyzing",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (!errorText.isNullOrBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = errorText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissError) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }
        }

        // SHEET METADATA + SAVE & EXPORT ACTIONS
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("2. Breakdown Sheet Details & Export", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = onTitleChange,
                            label = { Text("Sheet Title") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1.5f)
                        )
                        OutlinedTextField(
                            value = ledgerDate,
                            onValueChange = onDateChange,
                            label = { Text("Date") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = onSaveSheet,
                            enabled = entries.isNotEmpty() && !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("calculator_save_sheet_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save Sheet")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        ReportExporter.exportDailyLedgerPdfAndShare(
                                            context = context,
                                            business = business,
                                            title = title,
                                            ledgerDate = ledgerDate,
                                            entries = entries,
                                            summary = summary
                                        )
                                    }
                                    res.onSuccess {
                                        exportStatusMsg = "PDF Breakdown Sheet generated and ready to share."
                                    }.onFailure { err ->
                                        exportStatusMsg = err.localizedMessage ?: "PDF export failed."
                                    }
                                }
                            },
                            enabled = entries.isNotEmpty(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("calculator_export_pdf_button")
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export PDF")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        ReportExporter.exportDailyLedgerXlsxAndShare(
                                            context = context,
                                            business = business,
                                            title = title,
                                            ledgerDate = ledgerDate,
                                            entries = entries,
                                            summary = summary
                                        )
                                    }
                                    res.onSuccess {
                                        exportStatusMsg = "Excel (.xlsx) Breakdown Sheet generated."
                                    }.onFailure { err ->
                                        exportStatusMsg = err.localizedMessage ?: "Excel export failed."
                                    }
                                }
                            },
                            enabled = entries.isNotEmpty(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("calculator_export_xlsx_button")
                        ) {
                            Icon(Icons.Default.TableView, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export Excel")
                        }
                        if (entries.isNotEmpty()) {
                            TextButton(onClick = onClearDraft) {
                                Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Clear")
                            }
                        }
                    }
                    if (!exportStatusMsg.isNullOrBlank()) {
                        Text(
                            text = exportStatusMsg!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = NigerianEmerald
                        )
                    }
                }
            }
        }

        // CATEGORY FILTER CHIPS
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategoryFilter == null,
                    onClick = { selectedCategoryFilter = null },
                    label = { Text("All (${summary.entryCount})") },
                    shape = RoundedCornerShape(14.dp)
                )
                LedgerCategory.entries.forEach { cat ->
                    val count = summary.itemCountByCategory[cat] ?: 0
                    val amt = summary.amountByCategory[cat] ?: 0.0
                    FilterChip(
                        selected = selectedCategoryFilter == cat,
                        onClick = {
                            selectedCategoryFilter = if (selectedCategoryFilter == cat) null else cat
                        },
                        label = {
                            Text("${cat.shortLabel} ($count) • ₦${"%,.0f".format(amt)}")
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
        }

        // ITEMIZED ENTRIES
        if (filteredEntries.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "No receipt entries yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Tap 'Batch Images', 'PDFs / Docs', or 'Today's Receipts' above to automatically calculate your Sales, Purchases, Expenditure, and Net Profit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredEntries, key = { it.id }) { item ->
                val cat = item.categoryEnum()
                val badgeColor = when (cat) {
                    LedgerCategory.SALES_INCOME -> NigerianEmerald
                    LedgerCategory.PURCHASE -> Color(0xFF0284C7)
                    LedgerCategory.EXPENDITURE -> BurnishedGold
                    LedgerCategory.REFUND_REVERSAL -> AlertCrimson
                    LedgerCategory.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = badgeColor.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = cat.shortLabel,
                                        color = badgeColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                                if (item.paymentMethod.isNotBlank()) {
                                    Text(
                                        text = item.paymentMethod,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                text = item.merchantOrParty.ifBlank { "Receipt Item" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.notes.isNotBlank() || item.reference.isNotBlank()) {
                                Text(
                                    text = listOf(item.notes, item.reference).filter { it.isNotBlank() }.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${if (cat.isInflow) "+" else "-"}₦${"%,.2f".format(item.amount)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (cat.isInflow) NigerianEmerald else AlertCrimson,
                                fontFamily = FontFamily.Monospace
                            )
                            Row {
                                IconButton(
                                    onClick = { editingEntry = item },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Entry", modifier = Modifier.size(17.dp))
                                }
                                IconButton(
                                    onClick = { onRemoveEntry(item.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove Entry", modifier = Modifier.size(17.dp), tint = AlertCrimson)
                                }
                            }
                        }
                    }
                }
            }
        }

        // SAVED BREAKDOWN SHEETS HISTORY
        if (savedSheets.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Saved End-of-Day Breakdown Sheets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            items(savedSheets, key = { it.id }) { sheet ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sheet.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "${sheet.ledgerDate} • ${sheet.entryCount} items • Saved by ${sheet.createdByName.ifBlank { "Staff" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "Net ₦${"%,.0f".format(sheet.netProfit)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (sheet.netProfit >= 0.0) NigerianEmerald else AlertCrimson
                            )
                        }
                        Text(
                            text = "Sales: ₦${"%,.0f".format(sheet.totalSales)}  •  Purchases: ₦${"%,.0f".format(sheet.totalPurchases)}  •  Expenses: ₦${"%,.0f".format(sheet.totalExpenditure)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onLoadSheet(sheet) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            ReportExporter.exportDailyLedgerPdfAndShare(
                                                context = context,
                                                business = business,
                                                title = sheet.title,
                                                ledgerDate = sheet.ledgerDate,
                                                entries = sheet.entries,
                                                summary = com.example.data.model.LedgerCalculator.summarize(sheet.entries)
                                            )
                                        }
                                        res.onSuccess {
                                            exportStatusMsg = "PDF Breakdown Sheet generated and ready to share."
                                        }.onFailure { err ->
                                            exportStatusMsg = err.localizedMessage ?: "PDF export failed."
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("PDF")
                            }
                            IconButton(onClick = { onDeleteSheet(sheet.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Sheet", tint = AlertCrimson)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun HeroTallyPill(
    label: String,
    amount: Double,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "₦${"%,.0f".format(amount)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LedgerEntryEditorDialog(
    initial: LedgerEntryItem?,
    defaultCurrency: String,
    defaultDate: String,
    onSave: (LedgerEntryItem) -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember(initial) {
        mutableStateOf(initial?.categoryEnum() ?: LedgerCategory.EXPENDITURE)
    }
    var party by remember(initial) { mutableStateOf(initial?.merchantOrParty.orEmpty()) }
    var amountStr by remember(initial) {
        mutableStateOf(
            initial?.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()
        )
    }
    var method by remember(initial) { mutableStateOf(initial?.paymentMethod.orEmpty()) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }
    var errMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial == null) "Add Receipt / Tally Item" else "Edit Tally Item", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Category", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LedgerCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.shortLabel) }
                        )
                    }
                }
                OutlinedTextField(
                    value = party,
                    onValueChange = { party = it },
                    label = { Text("Customer / Vendor / Expense Title *") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("Amount ($defaultCurrency) *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = method,
                    onValueChange = { method = it },
                    label = { Text("Bank / Payment Channel (Optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Purpose / Notes (Optional)") },
                    maxLines = 2,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (errMsg != null) {
                    Text(errMsg!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountStr.replace(",", "").trim().toDoubleOrNull()
                    if (amt == null || amt <= 0.0) {
                        errMsg = "Enter a valid positive amount."
                        return@Button
                    }
                    if (party.trim().isBlank()) {
                        errMsg = "Enter the customer, vendor, or expense title."
                        return@Button
                    }
                    onSave(
                        (initial ?: LedgerEntryItem()).copy(
                            merchantOrParty = party.trim(),
                            category = category.name,
                            amount = amt,
                            currency = initial?.currency?.ifBlank { defaultCurrency } ?: defaultCurrency,
                            paymentMethod = method.trim(),
                            transactionDate = initial?.transactionDate?.ifBlank { defaultDate } ?: defaultDate,
                            notes = notes.trim(),
                            sourceType = initial?.sourceType?.ifBlank { "MANUAL" } ?: "MANUAL"
                        )
                    )
                }
            ) { Text("Save Item") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
