package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ai.ReceiptOcrService
import com.example.data.model.Business
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.ReceiptRecord
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.NigerianEmerald
import com.example.ui.viewmodel.AiChatMessage
import com.example.util.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiChatbotScreen(
    business: Business,
    messages: List<AiChatMessage>,
    isBusy: Boolean,
    effectiveEntitlements: EffectiveEntitlements,
    superAdminEmail: String,
    onSendMessage: (text: String, imageUri: Uri?) -> Unit,
    onSubmitQuickComplaint: (subject: String, category: String, description: String) -> Unit,
    onNavigateToRoute: (route: String) -> Unit,
    onOpenReceiptDetail: (ReceiptRecord) -> Unit,
    onClearChat: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showComplaintDialog by remember { mutableStateOf(false) }
    val previewHelper = remember { ReceiptOcrService() }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            attachedImageUri = uri
        }
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            attachedImageUri = uri
        }
    }

    val attachedPreview by produceState<Pair<String, Bitmap?>?>(initialValue = null, attachedImageUri) {
        val uri = attachedImageUri
        value = if (uri == null) {
            null
        } else {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Transfer Proof"
            val mime = context.contentResolver.getType(uri).orEmpty()
            val bmp = runCatching { previewHelper.decodeDocumentPreview(context, uri, mime) }.getOrNull()
            name to bmp
        }
    }

    LaunchedEffect(messages.size, isBusy) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
        }
    }

    if (showComplaintDialog) {
        QuickComplaintDialog(
            superAdminEmail = superAdminEmail.ifBlank { "info.1803consult@gmail.com" },
            onDismiss = { showComplaintDialog = false },
            onSubmit = { subject, category, desc ->
                onSubmitQuickComplaint(subject, category, desc)
                showComplaintDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Emerald Fintech AI Header Card
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Assistant",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "ReceiptsNow AI Bot",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "${business.tradingName.ifBlank { business.registeredName }} • Instant receipts",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = { showComplaintDialog = true },
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("ai_quick_complaint_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ReportProblem, contentDescription = null, modifier = Modifier.size(15.dp), tint = AlertCrimson)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Report Issue",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = onClearChat,
                            modifier = Modifier.testTag("ai_clear_chat_button")
                        ) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Clear Conversation",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        // Quick action chips (wrapping FlowRow so nothing clips off the right edge)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistChip(
                onClick = {
                    inputText = "Generate a receipt of ₦25,000 for Chinedu Okafor for Product Supply via GTBank"
                },
                leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Sample Receipt", maxLines = 1) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("ai_chip_fast_receipt")
            )
            AssistChip(
                onClick = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Upload Proof", maxLines = 1) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("ai_chip_attach_proof")
            )
            AssistChip(
                onClick = {
                    docPicker.launch(arrayOf("application/pdf", "image/*"))
                },
                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Upload PDF", maxLines = 1) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("ai_chip_attach_pdf")
            )
            AssistChip(
                onClick = { onNavigateToRoute("calculator") },
                leadingIcon = { Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Day Tally", maxLines = 1) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("ai_chip_calculator")
            )
            AssistChip(
                onClick = {
                    onSendMessage("Check my current subscription plan, receipt quota, OCR credits, and storage usage.", null)
                },
                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Plan & Quota", maxLines = 1) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("ai_chip_quota")
            )
            AssistChip(
                onClick = { showComplaintDialog = true },
                leadingIcon = { Icon(Icons.Default.ReportProblem, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Complain", maxLines = 1) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("ai_chip_complaint")
            )
        }

        // Conversation list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("ai_chat_message_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Fast Shortcuts Without Manual Forms",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Tell me what you want to do in plain English—or attach a bank transfer screenshot—and I can execute it immediately:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "• \"Issue a ₦50,000 receipt for Amina Bello for Website Design via OPay\"\n" +
                                    "• Attach a transfer screenshot & say \"Generate receipt for Musa Traders\"\n" +
                                    "• \"Check my remaining monthly receipts and storage\"\n" +
                                    "• \"I am having trouble sharing receipts on WhatsApp — report this to Super Admin\"",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(
                        message = msg,
                        business = business,
                        effectiveEntitlements = effectiveEntitlements,
                        superAdminEmail = superAdminEmail.ifBlank { "info.1803consult@gmail.com" },
                        onNavigateToRoute = onNavigateToRoute,
                        onOpenReceiptDetail = onOpenReceiptDetail,
                        onShareWhatsApp = { receipt, bmp ->
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    ReportExporter.shareReceiptViaWhatsApp(context, bmp, business, receipt)
                                }
                                res.onFailure { Toast.makeText(context, it.localizedMessage ?: "WhatsApp share failed", Toast.LENGTH_SHORT).show() }
                            }
                        },
                        onShareGeneral = { receipt, bmp ->
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    ReportExporter.shareReceiptImage(context, bmp, business, receipt)
                                }
                                res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Share failed", Toast.LENGTH_SHORT).show() }
                            }
                        },
                        onDownloadPng = { receipt, bmp ->
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    ReportExporter.saveReceiptImageToGallery(context, bmp, receipt.receiptNumber, asJpeg = false)
                                }
                                res.onSuccess { Toast.makeText(context, "PNG saved to Pictures/ReceiptsNow", Toast.LENGTH_LONG).show() }
                                    .onFailure { Toast.makeText(context, it.localizedMessage ?: "Save failed", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    )
                }
            }

            if (isBusy) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Processing request...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // Attachment badge & thumbnail preview if present
        if (attachedImageUri != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val previewBmp = attachedPreview?.second
                    if (previewBmp != null && !previewBmp.isRecycled) {
                        Image(
                            bitmap = previewBmp.asImageBitmap(),
                            contentDescription = "Attached Proof Preview",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "Attached Document",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachedPreview?.first ?: "Transfer Proof Ready",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Tap Send now to auto-generate the receipt (or add customer/notes).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    IconButton(onClick = { attachedImageUri = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove Attachment")
                    }
                }
            }
        }

        // Bottom composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box {
                IconButton(
                    onClick = { showAttachMenu = true },
                    enabled = !isBusy,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("ai_attach_image_button")
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach Transfer Receipt Image or PDF")
                }
                DropdownMenu(
                    expanded = showAttachMenu,
                    onDismissRequest = { showAttachMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Upload Screenshot / Image") },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                        onClick = {
                            showAttachMenu = false
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Upload Bank PDF / File") },
                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                        onClick = {
                            showAttachMenu = false
                            docPicker.launch(arrayOf("application/pdf", "image/*"))
                        }
                    )
                }
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ask AI to generate a receipt or report an issue...") },
                maxLines = 4,
                enabled = !isBusy,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_chat_input")
            )
            FilledIconButton(
                onClick = {
                    val toSend = inputText
                    val uri = attachedImageUri
                    inputText = ""
                    attachedImageUri = null
                    onSendMessage(toSend, uri)
                },
                enabled = !isBusy && (inputText.isNotBlank() || attachedImageUri != null),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("ai_send_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Message")
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: AiChatMessage,
    business: Business,
    effectiveEntitlements: EffectiveEntitlements,
    superAdminEmail: String,
    onNavigateToRoute: (String) -> Unit,
    onOpenReceiptDetail: (ReceiptRecord) -> Unit,
    onShareWhatsApp: (ReceiptRecord, android.graphics.Bitmap) -> Unit,
    onShareGeneral: (ReceiptRecord, android.graphics.Bitmap) -> Unit,
    onDownloadPng: (ReceiptRecord, android.graphics.Bitmap) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        val bgColor = when {
            message.isUser -> MaterialTheme.colorScheme.primaryContainer
            message.isError -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surface
        }
        val textColor = when {
            message.isUser -> MaterialTheme.colorScheme.onPrimaryContainer
            message.isError -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
        val bubbleShape = if (message.isUser) {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        } else {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
        }
        Surface(
            color = bgColor,
            shape = bubbleShape,
            border = if (message.isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.attachmentBitmap != null && !message.attachmentBitmap.isRecycled) {
                    Image(
                        bitmap = message.attachmentBitmap.asImageBitmap(),
                        contentDescription = message.attachmentName.ifBlank { "Uploaded proof" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else if (message.attachmentName.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(message.attachmentName, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )

                val receipt = message.generatedReceipt
                val bmp = message.renderedReceiptBitmap
                if (receipt != null) {
                    Surface(
                        color = NigerianEmerald.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, NigerianEmerald.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NigerianEmerald, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "${receipt.receiptNumber} • ₦${"%,.2f".format(receipt.amountPaid)}",
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Generated Receipt ${receipt.receiptNumber}",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.FillWidth
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (effectiveEntitlements.whatsappSharingEnabled) {
                                        Button(
                                            onClick = { onShareWhatsApp(receipt, bmp) },
                                            colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("WhatsApp")
                                        }
                                    }
                                    OutlinedButton(onClick = { onShareGeneral(receipt, bmp) }) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Share")
                                    }
                                    OutlinedButton(onClick = { onDownloadPng(receipt, bmp) }) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save PNG")
                                    }
                                    OutlinedButton(onClick = { onOpenReceiptDetail(receipt) }) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Open")
                                    }
                                }
                            }
                        }
                    }
                }

                if (message.reportedTicketId != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.MarkEmailRead, contentDescription = null, tint = NigerianEmerald, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Complaint Escalated • ${message.reportedTicketId}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = if (message.ticketEmailDelivered == true) {
                                    "Super Admin notified via email ($superAdminEmail)."
                                } else {
                                    "Logged to Super Admin portal & queued for email notification ($superAdminEmail)."
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = { onNavigateToRoute("help") },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("View My Support Tickets")
                            }
                        }
                    }
                }

                if (!message.preparedDraftNotice.isNullOrBlank()) {
                    Text(
                        text = message.preparedDraftNotice,
                        style = MaterialTheme.typography.labelMedium,
                        color = NigerianEmerald,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                val targetRoute = message.suggestedRoute
                if (!targetRoute.isNullOrBlank()) {
                    val btnLabel = when (targetRoute) {
                        "generate" -> "Review in Generate Receipt"
                        "calculator" -> "Open Day Tally Calculator"
                        "billing" -> "Open Billing & Plans"
                        "receipts" -> "Open Receipts"
                        "customers" -> "Open Customers"
                        "help" -> "Open Help & Tickets"
                        else -> "Open Screen"
                    }
                    OutlinedButton(
                        onClick = { onNavigateToRoute(targetRoute) },
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(btnLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickComplaintDialog(
    superAdminEmail: String,
    onDismiss: () -> Unit,
    onSubmit: (subject: String, category: String, description: String) -> Unit
) {
    val categories = listOf("TECHNICAL", "RECEIPT_GENERATION", "WHATSAPP_SHARE", "BILLING", "ACCOUNT", "OTHER")
    var selectedCategory by remember { mutableStateOf("TECHNICAL") }
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report Difficulty to Super Admin", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Your complaint will be logged and an email notification will be dispatched to $superAdminEmail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.replace("_", " ")) }
                        )
                    }
                }
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Subject *") },
                    placeholder = { Text("e.g., Cannot share receipt or upload proof") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("complaint_subject_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Describe the difficulty *") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("complaint_description_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (subject.isNotBlank() && description.isNotBlank()) {
                        onSubmit(subject, selectedCategory, description)
                    }
                },
                enabled = subject.isNotBlank() && description.isNotBlank(),
                modifier = Modifier.testTag("complaint_submit_button")
            ) {
                Text("Send Complaint")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
