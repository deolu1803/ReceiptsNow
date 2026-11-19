package com.example.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.ThemeMode
import com.example.data.model.AuditLog
import com.example.data.model.Business
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.ReceiptRecord
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StaffMember
import com.example.data.model.StaffRole
import com.example.ui.components.BrandColorWheelCard
import com.example.ui.components.ReceiptTemplateSelectorCarousel
import com.example.ui.components.SignaturePadDialog
import com.example.ui.theme.NigerianEmerald
import com.example.ui.theme.SovereignNavy
import com.example.ui.theme.parseHexColorOrNull
import com.example.util.AmountInWords
import com.example.util.BrandColorExtractor
import com.example.util.ReceiptRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandingSettingsScreen(
    business: Business,
    role: StaffRole,
    themeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSaveBusiness: (Business) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tradingName by remember(business.id) { mutableStateOf(business.tradingName) }
    var address by remember(business.id) { mutableStateOf(business.address) }
    var phone by remember(business.id) { mutableStateOf(business.phone) }
    var email by remember(business.id) { mutableStateOf(business.email) }
    var cacNumber by remember(business.id) { mutableStateOf(business.cacNumber) }
    var authorisedRep by remember(business.id) { mutableStateOf(business.authorisedRepName) }
    var slogan by remember(business.id) { mutableStateOf(business.businessSlogan) }
    var stampText by remember(business.id) { mutableStateOf(business.stampText) }
    var prefix by remember(business.id) { mutableStateOf(business.receiptPrefix) }
    var ack by remember(business.id) { mutableStateOf(business.defaultAcknowledgement) }
    var logoBase64 by remember(business.id) { mutableStateOf(business.logoBase64) }
    var signatureBase64 by remember(business.id) { mutableStateOf(business.signatureBase64) }
    var primaryHex by remember(business.id) { mutableStateOf(business.primaryColorHex) }
    var secondaryHex by remember(business.id) { mutableStateOf(business.secondaryColorHex) }
    var accentHex by remember(business.id) { mutableStateOf(business.accentColorHex) }
    var showQr by remember(business.id) { mutableStateOf(business.showQrCode) }
    var showSignature by remember(business.id) { mutableStateOf(business.showSignature) }
    var publicVerify by remember(business.id) { mutableStateOf(business.publicVerificationEnabled) }
    var selectedTemplate by remember(business.id) {
        mutableStateOf(
            runCatching { ReceiptTemplateType.valueOf(business.defaultTemplate) }
                .getOrDefault(ReceiptTemplateType.STANDARD)
        )
    }
    var showSignatureDialog by remember { mutableStateOf(false) }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val processed = withContext(Dispatchers.IO) {
                    val bmp = BrandColorExtractor.decodeSampledBitmap(context, uri, 1024) ?: return@withContext null
                    try {
                        val encoded = BrandColorExtractor.encodeLogoBase64(bmp, 512)
                        val palette = BrandColorExtractor.extractPalette(bmp)
                        encoded to palette
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }
                if (processed != null) {
                    val (encoded, palette) = processed
                    logoBase64 = encoded
                    primaryHex = palette.primaryHex
                    secondaryHex = palette.secondaryHex
                    accentHex = palette.accentHex
                } else {
                    Toast.makeText(context, "Could not decode selected logo image.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showSignatureDialog) {
        SignaturePadDialog(
            onSaveBase64 = {
                signatureBase64 = it
                showSignatureDialog = false
            },
            onDismiss = { showSignatureDialog = false }
        )
    }

    val previewBiz = business.copy(
        tradingName = tradingName.ifBlank { business.registeredName },
        address = address,
        phone = phone,
        email = email,
        cacNumber = cacNumber,
        authorisedRepName = authorisedRep,
        businessSlogan = slogan,
        stampText = stampText,
        receiptPrefix = prefix,
        defaultAcknowledgement = ack,
        logoBase64 = logoBase64,
        signatureBase64 = signatureBase64,
        primaryColorHex = primaryHex,
        secondaryColorHex = secondaryHex,
        accentColorHex = accentHex,
        showQrCode = showQr,
        showSignature = showSignature,
        defaultTemplate = selectedTemplate.name
    )

    val previewBitmap by produceState<Bitmap?>(initialValue = null, previewBiz, selectedTemplate) {
        delay(120L)
        value = withContext(Dispatchers.Default) {
            val sample = ReceiptRecord(
                receiptNumber = "${prefix.ifBlank { "RCN" }}/RCT/2026/000001",
                verificationCode = "RNW-PREVIEW01",
                customerName = "Template Preview Customer",
                paymentPurpose = "Template Branding & Color Preview",
                amountPaid = 125000.0,
                currency = "NGN",
                amountInWords = AmountInWords.toNairaWords(125000.0, "NGN"),
                templateType = selectedTemplate.name,
                verificationStatus = "VERIFIED",
                senderBankSnapshot = "GTBank",
                beneficiaryBankSnapshot = "Access Bank",
                transactionRefSnapshot = "PREVIEW-REF-001"
            )
            ReceiptRenderer.renderLandscapeReceipt(previewBiz, sample, selectedTemplate)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("App Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Choose Light Mode, Dark Mode, or follow your device system setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { idx, mode ->
                        val icon = when (mode) {
                            ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Default.LightMode
                            ThemeMode.DARK -> Icons.Default.DarkMode
                        }
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { onSelectThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = ThemeMode.entries.size),
                            icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.testTag("branding_theme_${mode.name.lowercase()}")
                        ) {
                            Text(mode.label, maxLines = 1)
                        }
                    }
                }
            }
        }

        Text("Templates & Business Branding", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Live Template Preview (${selectedTemplate.label})", style = MaterialTheme.typography.labelLarge)
        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            val bmpReady = previewBitmap
            if (bmpReady == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                Image(
                    bitmap = bmpReady.asImageBitmap(),
                    contentDescription = "Live Template Preview",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
        Text("Select Receipt Layout Template", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        ReceiptTemplateSelectorCarousel(
            selectedTemplate = selectedTemplate,
            primaryHex = primaryHex,
            secondaryHex = secondaryHex,
            accentHex = accentHex,
            onSelectTemplate = { selectedTemplate = it }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Upload Logo")
            }
            OutlinedButton(
                onClick = { showSignatureDialog = true },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Icon(Icons.Default.Draw, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Signature Pad")
            }
        }

        BrandColorWheelCard(
            primaryHex = primaryHex,
            secondaryHex = secondaryHex,
            accentHex = accentHex,
            onPrimaryChange = { primaryHex = it },
            onSecondaryChange = { secondaryHex = it },
            onAccentChange = { accentHex = it }
        )

        OutlinedTextField(value = tradingName, onValueChange = { tradingName = it }, label = { Text("Trading / Brand Name") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = prefix, onValueChange = { prefix = it.uppercase().take(12) }, label = { Text("Receipt Prefix") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = cacNumber, onValueChange = { cacNumber = it }, label = { Text("CAC RC / BN Number") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Business Address") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
        }
        OutlinedTextField(value = authorisedRep, onValueChange = { authorisedRep = it }, label = { Text("Authorised Signatory Name") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = stampText, onValueChange = { stampText = it }, label = { Text("Stamp Label") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = slogan, onValueChange = { slogan = it }, label = { Text("Business Slogan") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = ack, onValueChange = { ack = it }, label = { Text("Default Receipt Acknowledgement") }, shape = RoundedCornerShape(14.dp), minLines = 2, modifier = Modifier.fillMaxWidth())

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Show Authorised Signature")
            Switch(checked = showSignature, onCheckedChange = { showSignature = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Show QR Verification Code")
            Switch(checked = showQr, onCheckedChange = { showQr = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Allow Public QR Verification")
            Switch(checked = publicVerify, onCheckedChange = { publicVerify = it })
        }

        if (role.canManageSettingsAndBranding()) {
            Button(
                onClick = { onSaveBusiness(previewBiz.copy(publicVerificationEnabled = publicVerify)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_branding_settings_button")
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save Branding & Template Settings")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun StaffManagementScreen(
    business: Business,
    role: StaffRole,
    staffMembers: List<StaffMember>,
    auditLogs: List<AuditLog>,
    effectiveEntitlements: EffectiveEntitlements? = null,
    onInviteStaff: (email: String, name: String, role: StaffRole) -> Unit,
    onToggleStaffActive: (StaffMember, Boolean) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var showInviteDialog by remember { mutableStateOf(false) }

    if (showInviteDialog) {
        var inviteEmail by remember { mutableStateOf("") }
        var inviteName by remember { mutableStateOf("") }
        var selectedRole by remember { mutableStateOf(StaffRole.RECEIPT_OFFICER) }
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Invite Team Member") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = inviteName, onValueChange = { inviteName = it }, label = { Text("Staff Full Name") })
                    OutlinedTextField(value = inviteEmail, onValueChange = { inviteEmail = it }, label = { Text("Google Account Email *") })
                    Text("Assign Role:", style = MaterialTheme.typography.labelLarge)
                    StaffRole.entries.filter { it != StaffRole.OWNER }.forEach { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selectedRole = r }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(selected = selectedRole == r, onClick = { selectedRole = r }, label = { Text(r.displayName) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inviteEmail.isNotBlank()) {
                            onInviteStaff(inviteEmail.trim(), inviteName.trim(), selectedRole)
                            showInviteDialog = false
                        }
                    }
                ) { Text("Send Invite") }
            },
            dismissButton = { TextButton(onClick = { showInviteDialog = false }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Team (${staffMembers.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Audit Logs (${auditLogs.size})") })
        }
        if (tab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Spacer(Modifier.height(6.dp)) }
                val activeCount = staffMembers.count { it.active }
                val maxStaff = effectiveEntitlements?.maxStaffAccounts ?: -1
                val atStaffCap = maxStaff >= 0 && activeCount >= maxStaff
                if (maxStaff >= 0) {
                    item {
                        Text(
                            "Team Seats: $activeCount / $maxStaff active (${effectiveEntitlements?.planName ?: "Current Plan"})",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (atStaffCap) com.example.ui.theme.AlertCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (role.canManageStaffAndSubscription()) {
                    item {
                        Button(
                            onClick = { showInviteDialog = true },
                            enabled = !atStaffCap,
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("invite_staff_button")
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (atStaffCap) "Staff Limit Reached — Upgrade Plan" else "Invite Staff by Google Email")
                        }
                    }
                }
                items(staffMembers, key = { it.staffUid }) { member ->
                    Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(member.fullName.ifBlank { member.email }, fontWeight = FontWeight.Bold)
                                Text(member.email, style = MaterialTheme.typography.bodySmall)
                                Text(member.staffRole().displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            if (role.canManageStaffAndSubscription() && member.staffUid != business.ownerUid) {
                                Switch(
                                    checked = member.active,
                                    onCheckedChange = { onToggleStaffActive(member, it) }
                                )
                            } else {
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(999.dp)) {
                                    Text(if (member.active) "Active" else "Inactive", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(6.dp)) }
                items(auditLogs, key = { it.id }) { log ->
                    Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(log.action, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(log.details, style = MaterialTheme.typography.bodySmall)
                                Text("By ${log.actorName} (${log.actorRole})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
