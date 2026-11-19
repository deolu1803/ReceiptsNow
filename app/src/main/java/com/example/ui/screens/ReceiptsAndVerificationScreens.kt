package com.example.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.BankTransactionRecord
import com.example.data.model.Business
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.ReceiptRecord
import com.example.data.model.StaffRole
import com.example.data.model.VerificationStatus
import com.example.ui.components.VerificationStatusChip
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.NigerianEmerald
import com.example.ui.theme.SovereignNavy
import com.example.util.BrandColorExtractor
import com.example.util.ReceiptRenderer
import com.example.util.ReportExporter

@Composable
fun AllReceiptsScreen(
    business: Business,
    role: StaffRole,
    receipts: List<ReceiptRecord>,
    selectedReceipt: ReceiptRecord?,
    effectiveEntitlements: EffectiveEntitlements? = null,
    onSelectReceipt: (ReceiptRecord?) -> Unit,
    onOpenQrVerify: () -> Unit,
    onUpdateVerificationStatus: (ReceiptRecord, VerificationStatus, String) -> Unit,
    onAmendReceipt: (ReceiptRecord) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<VerificationStatus?>(null) }

    val filtered = remember(receipts, query, statusFilter) {
        receipts.filter { r ->
            val matchStatus = statusFilter == null || r.verificationStatus == statusFilter?.name
            val q = query.trim().lowercase()
            val matchQuery = q.isBlank() ||
                r.receiptNumber.lowercase().contains(q) ||
                r.customerName.lowercase().contains(q) ||
                r.transactionRefSnapshot.lowercase().contains(q) ||
                r.paymentPurpose.lowercase().contains(q) ||
                r.amountPaid.toString().contains(q)
            matchStatus && matchQuery
        }
    }

    selectedReceipt?.let { detail ->
        ReceiptDetailModal(
            business = business,
            role = role,
            receipt = detail,
            effectiveEntitlements = effectiveEntitlements,
            onDismiss = { onSelectReceipt(null) },
            onUpdateStatus = { st, note ->
                onUpdateVerificationStatus(detail, st, note)
                onSelectReceipt(null)
            },
            onAmend = {
                onSelectReceipt(null)
                onAmendReceipt(detail)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search receipt #, customer, ref...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("receipts_search_input")
            )
            OutlinedButton(
                onClick = onOpenQrVerify,
                modifier = Modifier.height(54.dp).testTag("receipts_qr_verify_button")
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Verify QR")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = statusFilter == null,
                onClick = { statusFilter = null },
                label = { Text("All (${receipts.size})") }
            )
            VerificationStatus.entries.forEach { st ->
                FilterChip(
                    selected = statusFilter == st,
                    onClick = { statusFilter = st },
                    label = { Text(st.label) }
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No receipts match your filter.",
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(filtered, key = { it.id }) { r ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectReceipt(r) }
                            .testTag("receipt_row_${r.id}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    r.receiptNumber,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    r.customerName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    r.paymentPurpose,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("₦${"%,.2f".format(r.amountPaid)}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                VerificationStatusChip(r.verificationStatus)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ReceiptDetailModal(
    business: Business,
    role: StaffRole,
    receipt: ReceiptRecord,
    effectiveEntitlements: EffectiveEntitlements? = null,
    onDismiss: () -> Unit,
    onUpdateStatus: (VerificationStatus, String) -> Unit,
    onAmend: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<Bitmap?>(initialValue = null, business, receipt) {
        value = withContext(Dispatchers.Default) {
            ReceiptRenderer.renderLandscapeReceipt(business, receipt)
        }
    }
    var statusComment by remember(receipt.id) { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(receipt.receiptNumber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("Issued by ${receipt.issuedByName} (${receipt.issuedByRole})", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close Receipt Detail")
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val bmpReady = bitmap
                    if (bmpReady == null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                                Text("Rendering receipt...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Card(elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
                            Image(
                                bitmap = bmpReady.asImageBitmap(),
                                contentDescription = "Receipt Image",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            enabled = bmpReady != null,
                            onClick = {
                                val targetBmp = bmpReady ?: return@Button
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        ReportExporter.saveReceiptImageToGallery(context, targetBmp, receipt.receiptNumber, asJpeg = false)
                                    }
                                    res.onSuccess { Toast.makeText(context, "PNG saved!", Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, it.localizedMessage ?: "Failed to save PNG", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("PNG")
                        }
                        OutlinedButton(
                            enabled = bmpReady != null,
                            onClick = {
                                val targetBmp = bmpReady ?: return@OutlinedButton
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        ReportExporter.saveReceiptImageToGallery(context, targetBmp, receipt.receiptNumber, asJpeg = true)
                                    }
                                    res.onSuccess { Toast.makeText(context, "JPEG saved!", Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, it.localizedMessage ?: "Failed to save JPEG", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("JPEG")
                        }
                        if (effectiveEntitlements?.whatsappSharingEnabled != false) {
                            OutlinedButton(
                                enabled = bmpReady != null,
                                onClick = {
                                    val targetBmp = bmpReady ?: return@OutlinedButton
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            ReportExporter.shareReceiptViaWhatsApp(context, targetBmp, business, receipt)
                                        }
                                        res.onFailure { Toast.makeText(context, it.localizedMessage ?: "WhatsApp share failed", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("WhatsApp")
                            }
                        }
                        if (effectiveEntitlements?.emailSharingEnabled != false) {
                            OutlinedButton(
                                enabled = bmpReady != null,
                                onClick = {
                                    val targetBmp = bmpReady ?: return@OutlinedButton
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            ReportExporter.shareReceiptViaEmail(context, targetBmp, business, receipt)
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
                            enabled = bmpReady != null,
                            onClick = {
                                val targetBmp = bmpReady ?: return@OutlinedButton
                                ReportExporter.printReceiptBitmap(context, targetBmp, receipt.receiptNumber)
                                    .onFailure { Toast.makeText(context, it.localizedMessage ?: "Print failed", Toast.LENGTH_SHORT).show() }
                            }
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Print")
                        }
                        if (effectiveEntitlements?.qrVerificationEnabled != false) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString("receiptsnow://verify?code=${receipt.verificationCode}"))
                                Toast.makeText(context, "Verification link copied!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy Link")
                            }
                        }
                    }

                    if (role.canGenerateReceipts()) {
                        OutlinedButton(onClick = onAmend, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.EditNote, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Create Amended Replacement Receipt")
                        }
                    }

                    if (role.canVerifyTransactions()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Verification & Audit Actions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                VerificationStatusChip(receipt.verificationStatus)
                                OutlinedTextField(
                                    value = statusComment,
                                    onValueChange = { statusComment = it },
                                    label = { Text("Verification / Rejection Audit Note") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { onUpdateStatus(VerificationStatus.VERIFIED, statusComment) },
                                        colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Mark Verified")
                                    }
                                    OutlinedButton(onClick = { onUpdateStatus(VerificationStatus.REJECTED, statusComment) }) {
                                        Text("Reject", color = AlertCrimson)
                                    }
                                    OutlinedButton(onClick = { onUpdateStatus(VerificationStatus.REVERSED, statusComment) }) {
                                        Text("Mark Reversed", color = AlertCrimson)
                                    }
                                    OutlinedButton(onClick = { onUpdateStatus(VerificationStatus.CANCELLED, statusComment) }) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun VerificationQueueScreen(
    role: StaffRole,
    receipts: List<ReceiptRecord>,
    transactions: List<BankTransactionRecord>,
    onUpdateVerificationStatus: (ReceiptRecord, VerificationStatus, String) -> Unit
) {
    var activeReceipt by remember { mutableStateOf<ReceiptRecord?>(null) }

    val pendingList = remember(receipts) {
        receipts.filter { it.verificationStatus == VerificationStatus.PENDING_VERIFICATION.name }
    }
    val txMap = remember(transactions) { transactions.associateBy { it.id } }

    activeReceipt?.let { rct ->
        var comment by remember(rct.id) { mutableStateOf("") }
        val tx = txMap[rct.transactionRecordId]
        val thumbBmp = remember(tx?.thumbnailBase64) {
            tx?.thumbnailBase64?.let { BrandColorExtractor.decodeBase64Bitmap(it) }
        }
        AlertDialog(
            onDismissRequest = { activeReceipt = null },
            title = { Text("Verify ${rct.receiptNumber}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (thumbBmp != null) {
                        Image(
                            bitmap = thumbBmp.asImageBitmap(),
                            contentDescription = "Uploaded Bank Proof Thumbnail",
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Text("Customer: ${rct.customerName}", fontWeight = FontWeight.Bold)
                    Text("Amount: ₦${"%,.2f".format(rct.amountPaid)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Sender: ${rct.senderAccountNameSnapshot} (${rct.senderBankSnapshot})")
                    Text("Beneficiary Bank: ${rct.beneficiaryBankSnapshot}")
                    Text("Reference: ${rct.transactionRefSnapshot}")
                    Text("Session ID: ${rct.sessionIdSnapshot}")
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("Verification Comment / Reference Note") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateVerificationStatus(rct, VerificationStatus.VERIFIED, comment)
                        comment = ""
                        activeReceipt = null
                    },
                    enabled = role.canVerifyTransactions(),
                    colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald)
                ) {
                    Text("Approve & Verify")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = {
                            onUpdateVerificationStatus(rct, VerificationStatus.REJECTED, comment.ifBlank { "Payment not confirmed in bank ledger" })
                            comment = ""
                            activeReceipt = null
                        },
                        enabled = role.canVerifyTransactions()
                    ) {
                        Text("Reject", color = AlertCrimson)
                    }
                    TextButton(onClick = { activeReceipt = null }) {
                        Text("Close")
                    }
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("Pending Bank Verification Queue (${pendingList.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Confirm credits in your business bank statement before marking receipts Verified.", style = MaterialTheme.typography.bodySmall)
        }
        if (pendingList.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Text("No receipts currently pending verification.", modifier = Modifier.padding(20.dp))
                }
            }
        } else {
            items(pendingList, key = { it.id }) { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { activeReceipt = r },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(r.receiptNumber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(r.customerName, fontWeight = FontWeight.SemiBold)
                            Text("Ref: ${r.transactionRefSnapshot.ifBlank { "No ref" }}  •  ${r.senderBankSnapshot}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("₦${"%,.2f".format(r.amountPaid)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
