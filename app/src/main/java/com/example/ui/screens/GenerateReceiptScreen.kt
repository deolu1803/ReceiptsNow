package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.BillingCalculator
import com.example.data.model.Business
import com.example.data.model.CatalogItem
import com.example.data.model.Customer
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.ReceiptLineItem
import com.example.data.model.ReceiptRecord
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StaffRole
import com.example.data.repository.BusinessRepository
import com.example.data.repository.SubscriptionRepository
import com.example.ui.components.CameraCaptureDialog
import com.example.ui.components.ReceiptTemplateSelectorCarousel
import com.example.ui.components.SubscriptionStatusBanner
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.AlertCrimsonContainer
import com.example.ui.theme.BurnishedGoldSoft
import com.example.ui.theme.NigerianEmerald
import com.example.ui.theme.NigerianEmeraldContainer
import com.example.ui.theme.SovereignNavy
import com.example.ui.viewmodel.OcrUiState
import com.example.ui.viewmodel.ReceiptFlowViewModel
import com.example.util.AmountInWords
import com.example.util.ReceiptRenderer
import com.example.util.ReportExporter

@Composable
fun GenerateReceiptScreen(
    business: Business,
    role: StaffRole,
    repository: BusinessRepository,
    subscriptionRepository: SubscriptionRepository? = null,
    effectiveEntitlements: EffectiveEntitlements? = null,
    customers: List<Customer>,
    catalog: List<CatalogItem>,
    isOnline: Boolean,
    flowViewModel: ReceiptFlowViewModel,
    onOpenBilling: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val ocrState by flowViewModel.ocrState.collectAsState()
    val draft by flowViewModel.draft.collectAsState()
    val issuedReceipt by flowViewModel.issuedReceipt.collectAsState()
    val renderedBitmap by flowViewModel.renderedBitmap.collectAsState()
    val isIssuing by flowViewModel.isIssuing.collectAsState()
    val issueError by flowViewModel.issueError.collectAsState()

    var showCamera by remember { mutableStateOf(false) }
    var showTemplatePreview by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            flowViewModel.analyzeDocument(
                uri = uri,
                business = business,
                subscriptionRepository = subscriptionRepository,
                effectiveEntitlements = effectiveEntitlements
            )
        }
    }

    val pdfDocPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            flowViewModel.analyzeDocument(
                uri = uri,
                business = business,
                subscriptionRepository = subscriptionRepository,
                effectiveEntitlements = effectiveEntitlements
            )
        }
    }

    if (showCamera) {
        CameraCaptureDialog(
            onImageCaptured = { uri ->
                showCamera = false
                flowViewModel.analyzeDocument(
                    uri = uri,
                    mimeType = "image/jpeg",
                    business = business,
                    subscriptionRepository = subscriptionRepository,
                    effectiveEntitlements = effectiveEntitlements
                )
            },
            onDismiss = { showCamera = false }
        )
    }

    if (showTemplatePreview) {
        val sampleAmount = (draft.amountInput.replace(",", "").toDoubleOrNull() ?: 25000.0).coerceAtLeast(100.0)
        val previewBmp by produceState<Bitmap?>(initialValue = null, business, draft, draft.selectedTemplate, sampleAmount) {
            value = withContext(Dispatchers.Default) {
                val previewRecord = ReceiptRecord(
                    receiptNumber = "${business.receiptPrefix.ifBlank { "RCN" }}/RCT/2026/000001",
                    verificationCode = "RNW-PREVIEW",
                    customerName = draft.customerName.ifBlank { "Sample Customer Ltd" },
                    customerPhone = draft.customerPhone,
                    paymentPurpose = draft.paymentPurpose.ifBlank { "Payment for goods & services" },
                    amountPaid = sampleAmount,
                    currency = draft.currency.ifBlank { "NGN" },
                    amountInWords = AmountInWords.toNairaWords(sampleAmount, draft.currency.ifBlank { "NGN" }),
                    templateType = draft.selectedTemplate.name,
                    verificationStatus = "VERIFIED",
                    senderBankSnapshot = draft.senderBank.ifBlank { "GTBank" },
                    beneficiaryBankSnapshot = draft.beneficiaryBank.ifBlank { "Access Bank" },
                    transactionRefSnapshot = draft.transactionReference.ifBlank { "PREVIEW-REF-001" }
                )
                ReceiptRenderer.renderLandscapeReceipt(business, previewRecord, draft.selectedTemplate)
            }
        }
        AlertDialog(
            onDismissRequest = { showTemplatePreview = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("Preview • ${draft.selectedTemplate.label}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val bmpReady = previewBmp
                    if (bmpReady == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text("Rendering receipt layout preview...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Card(shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                            Image(
                                bitmap = bmpReady.asImageBitmap(),
                                contentDescription = "Receipt Layout Preview",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                    Text(
                        draft.selectedTemplate.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showTemplatePreview = false }, shape = RoundedCornerShape(14.dp)) {
                    Text("Close Preview")
                }
            }
        )
    }

    val graceEndMillis = effectiveEntitlements?.gracePeriodEndMillis ?: 0L
    val isSubActive = business.isSubscriptionActive(gracePeriodEndMillis = graceEndMillis)
    val receiptQuotaBlocked = effectiveEntitlements?.isReceiptQuotaExceeded == true &&
        (effectiveEntitlements.overageReceiptPriceMinor <= 0L)
    val storageBlocked = effectiveEntitlements?.shouldBlockNewUploads == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!isSubActive) {
            SubscriptionStatusBanner(
                business = business,
                gracePeriodEndMillis = graceEndMillis,
                onManageBilling = onOpenBilling
            )
        }
        if (effectiveEntitlements != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val rTxt = if (effectiveEntitlements.maxReceiptsPerCycle < 0) "Unlimited receipts"
                    else "${effectiveEntitlements.receiptsRemaining} of ${effectiveEntitlements.totalReceiptAllowance} receipts left"
                    val oTxt = if (effectiveEntitlements.maxOcrPerCycle < 0) "Unlimited OCR"
                    else "OCR: ${effectiveEntitlements.ocrUsed}/${effectiveEntitlements.maxOcrPerCycle}"
                    Text(
                        "$rTxt  •  $oTxt  •  Storage ${BillingCalculator.formatBytes(effectiveEntitlements.storageUsedBytes)}/${BillingCalculator.formatBytes(effectiveEntitlements.storageLimitBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                        color = if (receiptQuotaBlocked || storageBlocked) AlertCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (receiptQuotaBlocked || storageBlocked) {
                        TextButton(onClick = onOpenBilling) { Text("Upgrade") }
                    }
                }
            }
        }

        // POST-ISSUANCE LANDSCAPE RECEIPT PREVIEW & SHARE SUITE
        val receipt = issuedReceipt
        if (receipt != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = NigerianEmeraldContainer.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, NigerianEmerald)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Receipt Issued", tint = NigerianEmerald)
                        Column {
                            Text("Official Receipt Issued: ${receipt.receiptNumber}", fontWeight = FontWeight.Bold)
                            Text("Verification Code: ${receipt.verificationCode}", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    val bmp = renderedBitmap
                    if (bmp == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text("Rendering high-resolution receipt...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Card(
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "High-Resolution Landscape Receipt",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            ReportExporter.saveReceiptImageToGallery(context, bmp, receipt.receiptNumber, asJpeg = false)
                                        }
                                        res.onSuccess { Toast.makeText(context, "PNG saved to Pictures/ReceiptsNow", Toast.LENGTH_LONG).show() }
                                            .onFailure { Toast.makeText(context, it.localizedMessage ?: "Save failed", Toast.LENGTH_SHORT).show() }
                                    }
                                },
                                modifier = Modifier.testTag("receipt_download_png_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Download PNG")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            ReportExporter.saveReceiptImageToGallery(context, bmp, receipt.receiptNumber, asJpeg = true)
                                        }
                                        res.onSuccess { Toast.makeText(context, "JPEG saved to Pictures/ReceiptsNow", Toast.LENGTH_LONG).show() }
                                            .onFailure { Toast.makeText(context, it.localizedMessage ?: "Save failed", Toast.LENGTH_SHORT).show() }
                                    }
                                },
                                modifier = Modifier.testTag("receipt_download_jpeg_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Download JPEG")
                            }
                            if (effectiveEntitlements?.whatsappSharingEnabled != false) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                ReportExporter.shareReceiptViaWhatsApp(context, bmp, business, receipt)
                                            }
                                            res.onFailure { Toast.makeText(context, it.localizedMessage ?: "WhatsApp share failed", Toast.LENGTH_SHORT).show() }
                                        }
                                    },
                                    modifier = Modifier.testTag("receipt_whatsapp_button")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("WhatsApp")
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            ReportExporter.shareReceiptImage(context, bmp, business, receipt)
                                        }
                                        res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Share failed", Toast.LENGTH_SHORT).show() }
                                    }
                                },
                                modifier = Modifier.testTag("receipt_share_button")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Share")
                            }
                            if (effectiveEntitlements?.emailSharingEnabled != false) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                ReportExporter.shareReceiptViaEmail(context, bmp, business, receipt)
                                            }
                                            res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Email share failed", Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Email, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Email")
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    ReportExporter.printReceiptBitmap(context, bmp, receipt.receiptNumber)
                                        .onFailure { Toast.makeText(context, it.localizedMessage ?: "Print failed", Toast.LENGTH_SHORT).show() }
                                }
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Print")
                            }
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString("receiptsnow://verify?code=${receipt.verificationCode}"))
                                    Toast.makeText(context, "Verification link copied!", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy Verify Link")
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val tpl = runCatching { ReceiptTemplateType.valueOf(business.defaultTemplate) }
                                .getOrDefault(ReceiptTemplateType.STANDARD)
                            flowViewModel.resetFlow(tpl)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Generate Another Receipt")
                    }
                }
            }
            return@Column
        }

        // STEP 1: UPLOAD BANK TRANSFER / POS PROOF FOR AI OCR
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "AI OCR", tint = MaterialTheme.colorScheme.primary)
                    Text("1. Upload Bank Receipt Proof (AI OCR)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Upload a Nigerian bank/fintech screenshot (JPG/PNG), PDF e-receipt, or snap a POS slip. AI extracts visible fields without inventing missing data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val ocrBusy = ocrState is OcrUiState.Analyzing
                    Button(
                        onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !ocrBusy,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ocr_pick_image_button")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Image", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { pdfDocPicker.launch(arrayOf("application/pdf", "image/*")) },
                        enabled = !ocrBusy,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ocr_pick_pdf_button")
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("PDF", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { showCamera = true },
                        enabled = !ocrBusy,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ocr_camera_button")
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Camera", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold)
                    }
                }

                when (val s = ocrState) {
                    OcrUiState.Analyzing -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text("Analyzing", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is OcrUiState.Extracted -> {
                        val confPct = (s.data.confidenceScore * 100).toInt()
                        Surface(
                            color = if (confPct >= 75) MaterialTheme.colorScheme.secondaryContainer else BurnishedGoldSoft,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("AI Extraction Confidence: $confPct%", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                if (s.data.uncertainFields.isNotEmpty()) {
                                    Text("Review uncertain fields: ${s.data.uncertainFields.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (s.data.extractionNotes.isNotBlank()) {
                                    Text(s.data.extractionNotes, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    is OcrUiState.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = s.message,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (draft.documentUriString.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            flowViewModel.retryLastDocumentOcr(
                                                business = business,
                                                subscriptionRepository = subscriptionRepository,
                                                effectiveEntitlements = effectiveEntitlements
                                            )
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.height(42.dp).testTag("ocr_retry_button")
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Retry AI Scan")
                                    }
                                }
                            }
                        }
                    }
                    OcrUiState.Idle -> Unit
                }
            }
        }

        // STEP 2: REVIEW EXTRACTED TRANSACTION FIELDS
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2. Review Bank Transaction Fields", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.amountInput,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(amountInput = v) } },
                        label = { Text("Amount (NGN) *") },
                        modifier = Modifier.weight(1f).testTag("receipt_amount_input")
                    )
                    OutlinedTextField(
                        value = draft.discountInput,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(discountInput = v) } },
                        label = { Text("Discount") },
                        modifier = Modifier.weight(0.7f)
                    )
                }
                val parsedNet = ((draft.amountInput.replace(",", "").toDoubleOrNull() ?: 0.0) -
                    (draft.discountInput.replace(",", "").toDoubleOrNull() ?: 0.0)).coerceAtLeast(0.0)
                if (parsedNet > 0.0) {
                    Text(
                        "In Words: ${AmountInWords.toNairaWords(parsedNet, draft.currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NigerianEmerald,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.transactionDate,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(transactionDate = v) } },
                        label = { Text("Transaction Date") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.transactionTime,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(transactionTime = v) } },
                        label = { Text("Transaction Time") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.senderBank,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(senderBank = v) } },
                        label = { Text("Sender Bank") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.beneficiaryBank,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(beneficiaryBank = v) } },
                        label = { Text("Beneficiary Bank") },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = draft.senderAccountName,
                    onValueChange = { v -> flowViewModel.updateDraft { it.copy(senderAccountName = v) } },
                    label = { Text("Sender Account Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.transactionReference,
                    onValueChange = { v -> flowViewModel.updateDraft { it.copy(transactionReference = v) } },
                    label = { Text("Transaction Reference") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.sessionId,
                    onValueChange = { v -> flowViewModel.updateDraft { it.copy(sessionId = v) } },
                    label = { Text("NIP Session ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.paymentNarration,
                    onValueChange = { v -> flowViewModel.updateDraft { it.copy(paymentNarration = v) } },
                    label = { Text("Bank Narration / Remark") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { flowViewModel.updateDraft { it.copy(humanReviewed = !it.humanReviewed) } },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = draft.humanReviewed,
                        onCheckedChange = { checked -> flowViewModel.updateDraft { it.copy(humanReviewed = checked) } },
                        modifier = Modifier.testTag("human_review_checkbox")
                    )
                    Text(
                        "I have reviewed all extracted bank details and confirmed they match the payment proof.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // STEP 3: CUSTOMER & PAYMENT PURPOSE
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3. Customer & Payment Purpose", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (customers.isNotEmpty()) {
                    Text("Quick-Pick Existing Customer:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        customers.take(10).forEach { cust ->
                            FilterChip(
                                selected = draft.selectedCustomerId == cust.id,
                                onClick = { flowViewModel.selectExistingCustomer(cust) },
                                label = { Text(cust.fullName) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.customerName,
                    onValueChange = { v -> flowViewModel.updateDraft { it.copy(customerName = v, selectedCustomerId = "") } },
                    label = { Text("Customer Full Name *") },
                    modifier = Modifier.fillMaxWidth().testTag("receipt_customer_name_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.customerPhone,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(customerPhone = v) } },
                        label = { Text("Customer Phone") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.customerEmail,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(customerEmail = v) } },
                        label = { Text("Customer Email") },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (catalog.isNotEmpty()) {
                    Text("Add from Product / Service Catalog:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        catalog.filter { it.active }.forEach { item ->
                            OutlinedButton(
                                onClick = {
                                    flowViewModel.addLineItem(ReceiptLineItem(item.name, 1, item.unitPrice))
                                }
                            ) {
                                Text("${item.name} (₦${"%,.0f".format(item.unitPrice)})")
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.paymentPurpose,
                    onValueChange = { v -> flowViewModel.updateDraft { it.copy(paymentPurpose = v) } },
                    label = { Text("Purpose of Payment / Items Sold *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("receipt_purpose_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.invoiceNumber,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(invoiceNumber = v) } },
                        label = { Text("Invoice No (Optional)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.orderNumber,
                        onValueChange = { v -> flowViewModel.updateDraft { it.copy(orderNumber = v) } },
                        label = { Text("Order No (Optional)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Landscape Receipt Template", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { showTemplatePreview = true }) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Preview Layout")
                    }
                }
                val allowedTemplates = effectiveEntitlements?.allowedTemplates ?: ReceiptTemplateType.entries
                ReceiptTemplateSelectorCarousel(
                    selectedTemplate = draft.selectedTemplate,
                    primaryHex = business.primaryColorHex,
                    secondaryHex = business.secondaryColorHex,
                    accentHex = business.accentColorHex,
                    allowedTemplates = allowedTemplates,
                    onSelectTemplate = { tpl ->
                        flowViewModel.updateDraft { it.copy(selectedTemplate = tpl) }
                    }
                )
            }
        }

        if (!issueError.isNullOrBlank()) {
            Surface(color = AlertCrimsonContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(issueError!!, color = AlertCrimson, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    flowViewModel.saveAsOfflineDraft(business) {
                        Toast.makeText(context, "Saved as offline draft!", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).height(52.dp).testTag("save_offline_draft_button")
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Save Offline Draft", maxLines = 2)
            }

            Button(
                onClick = { flowViewModel.issueReceipt(repository, business, role, effectiveEntitlements) },
                enabled = !isIssuing && isOnline && isSubActive && role.canGenerateReceipts() && !receiptQuotaBlocked && !storageBlocked,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1.4f).height(52.dp).testTag("issue_official_receipt_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isIssuing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Issuing...")
                } else {
                    Text("Issue Official Receipt", fontWeight = FontWeight.Bold)
                }
            }
        }
        val disabledReason = when {
            !role.canGenerateReceipts() -> "Your role (${role.displayName}) does not permit issuing official receipts."
            !isSubActive -> "Active subscription required to issue official receipts. Save as offline draft or activate billing."
            receiptQuotaBlocked -> "Monthly receipt allowance reached (${effectiveEntitlements?.receiptsUsed}/${effectiveEntitlements?.totalReceiptAllowance}). Upgrade your plan to issue more receipts."
            storageBlocked -> "Cloud storage allowance reached. Upgrade your plan to issue new receipts; existing documents remain safe."
            !isOnline -> "You are currently offline. Save as an offline draft to auto-sync when back online."
            else -> null
        }
        if (disabledReason != null) {
            Text(
                text = disabledReason,
                style = MaterialTheme.typography.bodySmall,
                color = AlertCrimson
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
