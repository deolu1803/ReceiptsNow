package com.example.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Business
import com.example.data.model.PlatformSetting
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StaffInvite
import com.example.data.model.UserProfile
import com.example.ui.components.BrandColorWheelCard
import com.example.ui.components.ReceiptTemplateSelectorCarousel
import com.example.ui.components.SignaturePadDialog
import com.example.ui.theme.NigerianEmerald
import com.example.ui.theme.NigerianEmeraldContainer
import com.example.util.BrandColorExtractor
import com.google.firebase.auth.FirebaseUser

@Composable
fun OnboardingScreen(
    user: FirebaseUser,
    pendingInvites: List<StaffInvite>,
    platformSetting: PlatformSetting,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onAcceptInvite: (StaffInvite) -> Unit,
    onCompleteOnboarding: (UserProfile, Business) -> Unit,
    onSignOut: () -> Unit,
    onCancelExtraWorkspace: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(1) }
    var validationError by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = step > 1 || onCancelExtraWorkspace != null) {
        if (step > 1) {
            validationError = null
            step -= 1
        } else {
            onCancelExtraWorkspace?.invoke()
        }
    }

    // Step 1: Owner Info
    var ownerName by remember { mutableStateOf(user.displayName.orEmpty()) }
    var ownerPhone by remember { mutableStateOf("") }
    var ownerEmail by remember { mutableStateOf(user.email.orEmpty()) }
    var ownerState by remember { mutableStateOf("Lagos") }
    var ownerCity by remember { mutableStateOf("") }
    var ownerAddress by remember { mutableStateOf("") }

    // Step 2: Business Info
    var registeredName by remember { mutableStateOf("") }
    var tradingName by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Retail & Commerce") }
    var description by remember { mutableStateOf("") }
    var structure by remember { mutableStateOf("Business Name") }
    var bizAddress by remember { mutableStateOf("") }
    var bizPhone by remember { mutableStateOf("") }
    var bizEmail by remember { mutableStateOf(user.email.orEmpty()) }
    var website by remember { mutableStateOf("") }
    var cacNumber by remember { mutableStateOf("") }
    var socialMedia by remember { mutableStateOf("") }
    var authorisedRepName by remember { mutableStateOf(user.displayName.orEmpty()) }

    // Step 3 & 4: Logo + Brand Colors
    var logoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var logoBase64 by remember { mutableStateOf("") }
    var primaryHex by remember { mutableStateOf("#054230") }
    var secondaryHex by remember { mutableStateOf("#10B981") }
    var accentHex by remember { mutableStateOf("#D99B26") }
    var bgHex by remember { mutableStateOf("#FFFFFF") }
    var textHex by remember { mutableStateOf("#0F172A") }

    // Step 5: Preferences
    var receiptPrefix by remember { mutableStateOf("RCN") }
    var defaultAcknowledgement by remember {
        mutableStateOf("Received with thanks from the above-named customer the sum stated herein for the service or goods described in this receipt.")
    }
    var slogan by remember { mutableStateOf("") }
    var stampText by remember { mutableStateOf("PAID & VERIFIED") }
    var signatureBase64 by remember { mutableStateOf("") }
    var showSignature by remember { mutableStateOf(true) }
    var showQrCode by remember { mutableStateOf(true) }
    var publicVerifyEnabled by remember { mutableStateOf(true) }
    var maskAccountNumbers by remember { mutableStateOf(true) }
    var selectedTemplate by remember { mutableStateOf(ReceiptTemplateType.STANDARD) }
    var showSignatureDialog by remember { mutableStateOf(false) }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val processed = withContext(Dispatchers.IO) {
                    val bmp = BrandColorExtractor.decodeSampledBitmap(context, uri, 1024) ?: return@withContext null
                    val encoded = BrandColorExtractor.encodeLogoBase64(bmp, 512)
                    val palette = BrandColorExtractor.extractPalette(bmp)
                    Triple(bmp, encoded, palette)
                }
                if (processed != null) {
                    val (bmp, encoded, palette) = processed
                    logoBitmap = bmp
                    logoBase64 = encoded
                    primaryHex = palette.primaryHex
                    secondaryHex = palette.secondaryHex
                    accentHex = palette.accentHex
                    bgHex = palette.backgroundHex
                    textHex = palette.textHex
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

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "ReceiptsNow Workspace Setup",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Step $step of 6 • Nigerian Business Onboarding",
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onCancelExtraWorkspace != null) {
                            TextButton(onClick = onCancelExtraWorkspace) {
                                Text("Back", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        IconButton(
                            onClick = onToggleTheme,
                            modifier = Modifier.testTag("onboarding_theme_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        IconButton(onClick = onSignOut) {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign out",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LinearProgressIndicator(
                    progress = { step / 6f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp))
                )

                if (pendingInvites.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.GroupAdd, contentDescription = "Staff Invites", tint = NigerianEmerald)
                                Text("Pending Staff Invitation(s)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            pendingInvites.forEach { invite ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(invite.businessName, fontWeight = FontWeight.Bold)
                                        Text("Role: ${invite.role}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Button(
                                        onClick = { onAcceptInvite(invite) },
                                        modifier = Modifier.testTag("accept_invite_button")
                                    ) {
                                        Text("Accept & Join")
                                    }
                                }
                            }
                        }
                    }
                }

                if (!validationError.isNullOrBlank()) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                        Text(
                            validationError!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                when (step) {
                    1 -> {
                        Text("1. Business Owner Information", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = ownerName,
                            onValueChange = { ownerName = it },
                            label = { Text("Owner Full Name *") },
                            modifier = Modifier.fillMaxWidth().testTag("onboarding_owner_name")
                        )
                        OutlinedTextField(
                            value = ownerPhone,
                            onValueChange = { ownerPhone = it },
                            label = { Text("Phone Number (e.g. +234...) *") },
                            modifier = Modifier.fillMaxWidth().testTag("onboarding_owner_phone")
                        )
                        OutlinedTextField(
                            value = ownerEmail,
                            onValueChange = { ownerEmail = it },
                            label = { Text("Email Address *") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = ownerState,
                                onValueChange = { ownerState = it },
                                label = { Text("State (Nigeria)") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = ownerCity,
                                onValueChange = { ownerCity = it },
                                label = { Text("City / LGA") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = ownerAddress,
                            onValueChange = { ownerAddress = it },
                            label = { Text("Contact Address") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    2 -> {
                        Text("2. Business Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = registeredName,
                            onValueChange = { registeredName = it },
                            label = { Text("Registered Business Name *") },
                            modifier = Modifier.fillMaxWidth().testTag("onboarding_biz_name")
                        )
                        OutlinedTextField(
                            value = tradingName,
                            onValueChange = { tradingName = it },
                            label = { Text("Trading / Brand Name (Shown on Receipt)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Business Structure", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Sole Proprietor", "Business Name", "Limited Liability (Ltd)").forEach { s ->
                                FilterChip(
                                    selected = structure == s,
                                    onClick = { structure = s },
                                    shape = RoundedCornerShape(14.dp),
                                    label = { Text(s) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            label = { Text("Industry / Category") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = cacNumber,
                            onValueChange = { cacNumber = it },
                            label = { Text("CAC RC / BN Number (Optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = bizAddress,
                            onValueChange = { bizAddress = it },
                            label = { Text("Business Office Address *") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = bizPhone,
                                onValueChange = { bizPhone = it },
                                label = { Text("Business Phone *") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = bizEmail,
                                onValueChange = { bizEmail = it },
                                label = { Text("Business Email") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = website,
                                onValueChange = { website = it },
                                label = { Text("Website (Optional)") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = authorisedRepName,
                                onValueChange = { authorisedRepName = it },
                                label = { Text("Authorised Signatory") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    3 -> {
                        Text("3. Upload Business Logo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Upload your business logo (PNG, JPEG, WebP). Transparent backgrounds are preserved, and your receipt brand colors are extracted automatically.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (logoBitmap != null) {
                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    ) {
                                        Image(
                                            bitmap = logoBitmap!!.asImageBitmap(),
                                            contentDescription = "Uploaded Logo Preview",
                                            modifier = Modifier.size(140.dp).padding(12.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedButton(
                                            onClick = { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                        ) {
                                            Icon(Icons.Default.Image, contentDescription = "Replace Logo")
                                            Spacer(Modifier.width(6.dp))
                                            Text("Replace")
                                        }
                                        OutlinedButton(
                                            onClick = { logoBitmap = null; logoBase64 = "" }
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove Logo")
                                            Spacer(Modifier.width(6.dp))
                                            Text("Remove")
                                        }
                                    }
                                } else {
                                    Icon(Icons.Default.Business, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                                    Button(
                                        onClick = { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                        modifier = Modifier.testTag("onboarding_upload_logo_button")
                                    ) {
                                        Icon(Icons.Default.Image, contentDescription = "Choose Business Logo")
                                        Spacer(Modifier.width(8.dp))
                                        Text("Select Logo from Gallery")
                                    }
                                    Text("You can also skip and add a logo later in Branding Settings.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    4 -> {
                        Text("4. Brand Colors & Full Color Wheel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Use the interactive color wheel to pick any shade for your Primary, Secondary, and Accent brand colors.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        BrandColorWheelCard(
                            primaryHex = primaryHex,
                            secondaryHex = secondaryHex,
                            accentHex = accentHex,
                            onPrimaryChange = { primaryHex = it },
                            onSecondaryChange = { secondaryHex = it },
                            onAccentChange = { accentHex = it }
                        )
                    }

                    5 -> {
                        Text("5. Receipt Numbering & Preferences", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = receiptPrefix,
                            onValueChange = { receiptPrefix = it.uppercase().take(12) },
                            label = { Text("Receipt Prefix (e.g. RCN)") },
                            supportingText = { Text("Sample Format: ${receiptPrefix.ifBlank { "RCN" }}/RCT/2026/000001") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().testTag("onboarding_prefix_input")
                        )
                        OutlinedTextField(
                            value = slogan,
                            onValueChange = { slogan = it },
                            label = { Text("Business Slogan / Tagline (Optional)") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = stampText,
                            onValueChange = { stampText = it },
                            label = { Text("Official Stamp Text") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = defaultAcknowledgement,
                            onValueChange = { defaultAcknowledgement = it },
                            label = { Text("Default Receipt Acknowledgement Text") },
                            shape = RoundedCornerShape(14.dp),
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = { showSignatureDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.Default.Draw, contentDescription = "Draw Authorised Signature")
                            Spacer(Modifier.width(8.dp))
                            Text(if (signatureBase64.isBlank()) "Draw Authorised Signature (Optional)" else "Signature Captured — Redraw")
                        }
                        Text("Default Landscape Receipt Template", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        ReceiptTemplateSelectorCarousel(
                            selectedTemplate = selectedTemplate,
                            primaryHex = primaryHex,
                            secondaryHex = secondaryHex,
                            accentHex = accentHex,
                            onSelectTemplate = { selectedTemplate = it }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Include Scannable QR Verification Code")
                            Switch(checked = showQrCode, onCheckedChange = { showQrCode = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Enable Public QR Verification")
                            Switch(checked = publicVerifyEnabled, onCheckedChange = { publicVerifyEnabled = it })
                        }
                    }

                    6 -> {
                        Text("6. Review & Subscription Plan Info", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(tradingName.ifBlank { registeredName }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Owner: $ownerName ($ownerPhone)")
                                Text("Prefix: ${receiptPrefix.ifBlank { "RCN" }}/RCT/YYYY/SEQ  •  Currency: NGN (₦)")
                                Text("Template: ${selectedTemplate.label}")
                            }
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = NigerianEmeraldContainer.copy(alpha = 0.45f)),
                            border = BorderStroke(1.dp, NigerianEmerald)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("ReceiptsNow Subscription & 90-Day Free Trial", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("• 90-Day Free Trial: Starts automatically when your workspace is created")
                                Text("• Monthly Plan: ₦${"%,.0f".format(platformSetting.monthlyPriceNgn)} / month")
                                Text("• Yearly Plan: ₦${"%,.0f".format(platformSetting.annualPriceNgn)} / year")
                                Text(
                                    "After your 90-day free trial expires, subscribe seamlessly to the Monthly or Yearly plan via Paystack to continue issuing receipts.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (step > 1) {
                        OutlinedButton(
                            onClick = { validationError = null; step-- },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Step")
                            Spacer(Modifier.width(6.dp))
                            Text("Back")
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Button(
                        onClick = {
                            validationError = null
                            when (step) {
                                1 -> {
                                    if (ownerName.isBlank() || ownerPhone.isBlank()) {
                                        validationError = "Please enter owner full name and phone number."
                                    } else step = 2
                                }
                                2 -> {
                                    if (registeredName.isBlank() || bizAddress.isBlank() || bizPhone.isBlank()) {
                                        validationError = "Please enter registered business name, address, and phone."
                                    } else step = 3
                                }
                                in 3..5 -> step++
                                6 -> {
                                    val profile = UserProfile(
                                        userId = user.uid,
                                        fullName = ownerName.trim(),
                                        email = ownerEmail.trim().ifBlank { user.email.orEmpty() },
                                        phone = ownerPhone.trim(),
                                        country = "Nigeria",
                                        state = ownerState.trim(),
                                        city = ownerCity.trim(),
                                        address = ownerAddress.trim()
                                    )
                                    val biz = Business(
                                        ownerUid = user.uid,
                                        ownerName = ownerName.trim(),
                                        ownerEmail = ownerEmail.trim().ifBlank { user.email.orEmpty() },
                                        ownerPhone = ownerPhone.trim(),
                                        registeredName = registeredName.trim(),
                                        tradingName = tradingName.ifBlank { registeredName }.trim(),
                                        category = category.trim(),
                                        description = description.trim(),
                                        structure = structure,
                                        address = bizAddress.trim(),
                                        phone = bizPhone.trim(),
                                        email = bizEmail.trim(),
                                        website = website.trim(),
                                        cacNumber = cacNumber.trim(),
                                        socialMedia = socialMedia.trim(),
                                        authorisedRepName = authorisedRepName.ifBlank { ownerName }.trim(),
                                        logoBase64 = logoBase64,
                                        signatureBase64 = signatureBase64,
                                        stampText = stampText.trim(),
                                        primaryColorHex = primaryHex,
                                        secondaryColorHex = secondaryHex,
                                        accentColorHex = accentHex,
                                        backgroundColorHex = bgHex,
                                        textColorHex = textHex,
                                        defaultCurrency = "NGN",
                                        receiptPrefix = receiptPrefix.ifBlank { "RCN" },
                                        defaultAcknowledgement = defaultAcknowledgement,
                                        businessSlogan = slogan.trim(),
                                        defaultTemplate = selectedTemplate.name,
                                        publicVerificationEnabled = publicVerifyEnabled,
                                        maskAccountNumbers = maskAccountNumbers,
                                        showSignature = showSignature,
                                        showQrCode = showQrCode
                                    )
                                    onCompleteOnboarding(profile, biz)
                                }
                            }
                        },
                        modifier = Modifier.height(50.dp).testTag("onboarding_next_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(if (step == 6) "Create Business Workspace" else "Next")
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            if (step == 6) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}
