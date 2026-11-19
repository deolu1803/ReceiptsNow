package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.model.BillingCalculator
import com.example.data.model.Business
import com.example.data.model.PlatformSetting
import com.example.data.model.PublicVerification
import com.example.data.model.SubscriptionPayment
import com.example.data.model.SubscriptionPlan
import com.example.data.model.SubscriptionStatus
import com.example.data.model.VerificationStatus
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.AlertCrimsonContainer
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.BurnishedGoldSoft
import com.example.ui.theme.NigerianEmerald
import com.example.ui.theme.NigerianEmeraldContainer
import com.example.ui.theme.SovereignNavy
import com.example.util.BrandColorExtractor
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.util.concurrent.Executors

@Composable
fun BrandCrestBadge(
    business: Business?,
    modifier: Modifier = Modifier,
    sizeDp: Int = 44
) {
    val logoBmp = remember(business?.logoBase64) {
        business?.logoBase64?.let { BrandColorExtractor.decodeBase64Bitmap(it) }
    }
    Surface(
        modifier = modifier.size(sizeDp.dp),
        shape = CircleShape,
        color = if (logoBmp != null) Color.White else Color(0xFF083427),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        if (logoBmp != null) {
            Image(
                bitmap = logoBmp.asImageBitmap(),
                contentDescription = "Business Logo",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.ic_1803r_emerald_icon_1794867502860),
                contentDescription = "ReceiptsNow Emblem",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .scale(1.42f)
            )
        }
    }
}

@Composable
fun VerificationStatusChip(statusName: String, modifier: Modifier = Modifier) {
    val status = runCatching { VerificationStatus.valueOf(statusName) }
        .getOrDefault(VerificationStatus.PENDING_VERIFICATION)
    val (bg, fg, icon) = when (status) {
        VerificationStatus.VERIFIED -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Default.Verified
        )
        VerificationStatus.PENDING_VERIFICATION -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Default.Pending
        )
        VerificationStatus.REJECTED,
        VerificationStatus.CANCELLED,
        VerificationStatus.REVERSED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.ErrorOutline
        )
    }
    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = status.label, tint = fg, modifier = Modifier.size(14.dp))
            Text(
                text = status.label,
                color = fg,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun KpiMetricCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SubscriptionStatusBanner(
    business: Business,
    onManageBilling: () -> Unit,
    modifier: Modifier = Modifier,
    gracePeriodEndMillis: Long = 0L
) {
    val eff = business.effectiveSubscriptionStatus(gracePeriodEndMillis = gracePeriodEndMillis)
    val isActive = business.isSubscriptionActive(gracePeriodEndMillis = gracePeriodEndMillis)
    val trialDaysLeft = business.trialDaysRemaining()
    val isExpiredTrial = business.isTrialExpired()
    val (bg, fg, title) = when {
        business.accountStatus == "SUSPENDED" -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Account Suspended by Admin"
        )
        eff == SubscriptionStatus.TRIALING -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "90-Day Free Trial Active ($trialDaysLeft day${if (trialDaysLeft == 1) "" else "s"} left)"
        )
        eff == SubscriptionStatus.ACTIVE -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Subscription Active (${business.subscriptionPlanCode})"
        )
        eff == SubscriptionStatus.EXPIRING_SOON -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "Subscription Expiring Soon"
        )
        eff == SubscriptionStatus.GRACE_PERIOD -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "Grace Period Active — Renew Soon"
        )
        isExpiredTrial -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "90-Day Free Trial Expired"
        )
        else -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Subscription Required to Issue Receipts"
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = title,
                    tint = fg
                )
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = fg, fontWeight = FontWeight.Bold)
                    Text(
                        text = when {
                            eff == SubscriptionStatus.TRIALING ->
                                "Enjoy full access during your 90-day free trial. Choose Monthly or Yearly anytime."
                            isActive ->
                                "Full receipt scanning, high-res receipt issuance, and QR verification enabled."
                            isExpiredTrial ->
                                "Your 90-day free trial has ended. Subscribe to the Monthly or Yearly plan to continue."
                            else ->
                                "Choose a Monthly or Yearly plan to pay via Paystack and activate immediately."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = fg.copy(alpha = 0.9f)
                    )
                }
            }
            TextButton(
                onClick = onManageBilling,
                modifier = Modifier.testTag("banner_manage_subscription_button")
            ) {
                Text(if (isActive) "Billing" else "Subscribe", color = fg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PublicQrVerificationDialog(
    initialCode: String,
    result: Result<PublicVerification?>?,
    onLookup: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var codeInput by remember(initialCode) { mutableStateOf(initialCode) }
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        QrScannerDialog(
            onScanned = { scanned ->
                val extracted = if (scanned.contains("code=")) {
                    Uri.parse(scanned).getQueryParameter("code") ?: scanned
                } else scanned
                codeInput = extracted.trim()
                showScanner = false
                onLookup(codeInput)
            },
            onDismiss = { showScanner = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verify ReceiptsNow QR Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the receipt verification code (e.g. RNW-...) or scan the QR code printed on the business receipt.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        label = { Text("Verification Code") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("qr_verify_code_input")
                    )
                    IconButton(
                        onClick = { showScanner = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("qr_open_scanner_button")
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code with Camera")
                    }
                }

                if (result != null) {
                    result.onSuccess { pub ->
                        if (pub == null) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                                Text(
                                    "No receipt found matching this verification code.",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else if (!pub.publicVerificationEnabled) {
                            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(10.dp)) {
                                Text(
                                    "Public verification is currently disabled by ${pub.businessName}.",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(pub.businessName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text("Receipt No: ${pub.receiptNumber}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text("Amount: ${pub.amountFormatted}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("Customer: ${pub.customerDisplayName}  •  Date: ${pub.receiptDateText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text("Purpose: ${pub.purposeSummary}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    VerificationStatusChip(pub.verificationStatus)
                                }
                            }
                        }
                    }.onFailure { err ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                            Text(
                                err.localizedMessage ?: "Verification lookup failed.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (codeInput.isNotBlank()) onLookup(codeInput.trim()) },
                modifier = Modifier.testTag("qr_verify_lookup_button")
            ) {
                Text("Verify Receipt")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun SignaturePadDialog(
    onSaveBase64: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize(600, 260)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Authorised Representative Signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sign inside the box below using your finger.", style = MaterialTheme.typography.bodySmall)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset -> currentStroke = listOf(offset) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentStroke = currentStroke + change.position
                                },
                                onDragEnd = {
                                    if (currentStroke.size > 1) strokes.add(currentStroke)
                                    currentStroke = emptyList()
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        fun drawList(pts: List<Offset>) {
                            if (pts.size < 2) return
                            val path = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                            }
                            drawPath(path, color = Color(0xFF0B203B), style = Stroke(width = 6f, cap = StrokeCap.Round))
                        }
                        strokes.forEach { drawList(it) }
                        drawList(currentStroke)
                    }
                }
                TextButton(onClick = { strokes.clear(); currentStroke = emptyList() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Clear Signature")
                    Spacer(Modifier.width(6.dp))
                    Text("Clear Pad")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = canvasSize.width.coerceAtLeast(400)
                    val h = canvasSize.height.coerceAtLeast(180)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c = AndroidCanvas(bmp)
                    c.drawColor(AndroidColor.TRANSPARENT)
                    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.parseColor("#0B203B")
                        style = AndroidPaint.Style.STROKE
                        strokeWidth = 6f
                        strokeCap = AndroidPaint.Cap.ROUND
                    }
                    strokes.forEach { pts ->
                        if (pts.size >= 2) {
                            val p = AndroidPath()
                            p.moveTo(pts.first().x, pts.first().y)
                            for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
                            c.drawPath(p, paint)
                        }
                    }
                    val encoded = BrandColorExtractor.encodeLogoBase64(bmp, 400)
                    bmp.recycle()
                    onSaveBase64(encoded)
                },
                enabled = strokes.isNotEmpty(),
                modifier = Modifier.testTag("signature_save_button")
            ) {
                Text("Save Signature")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CameraCaptureDialog(
    onImageCaptured: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isDisposed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            isDisposed = true
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            if (!hasPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Camera permission is needed to snap physical bank/POS receipts.", color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                if (isDisposed) return@addListener
                                runCatching {
                                    val provider = cameraProviderFuture.get()
                                    if (isDisposed) return@addListener
                                    cameraProvider = provider
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val cap = ImageCapture.Builder()
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                        .build()
                                    imageCapture = cap
                                    provider.unbindAll()
                                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
                                }.onFailure { captureError = it.localizedMessage }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        }
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Camera", tint = Color.White)
                    }

                    if (captureError != null) {
                        Text(
                            captureError!!,
                            color = Color.Red,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(20.dp)
                        )
                    }

                    Button(
                        onClick = {
                            val cap = imageCapture ?: return@Button
                            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
                            val outputOpts = ImageCapture.OutputFileOptions.Builder(file).build()
                            cap.takePicture(
                                outputOpts,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        if (!isDisposed) {
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                            onImageCaptured(uri)
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        captureError = exception.localizedMessage
                                    }
                                }
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 36.dp)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Capture Receipt")
                        Spacer(Modifier.width(8.dp))
                        Text("Capture Receipt")
                    }
                }
            }
        }
    }
}

@Composable
fun QrScannerDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var detectedOnce by remember { mutableStateOf(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isDisposed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            isDisposed = true
            runCatching { cameraProvider?.unbindAll() }
            executor.shutdownNow()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            if (!hasPermission) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Camera permission is required to scan a receipt QR code.", color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Allow Camera")
                    }
                    TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val future = ProcessCameraProvider.getInstance(ctx)
                            val reader = MultiFormatReader().apply {
                                setHints(
                                    mapOf(
                                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                        DecodeHintType.TRY_HARDER to true
                                    )
                                )
                            }
                            future.addListener({
                                if (isDisposed || executor.isShutdown) return@addListener
                                runCatching {
                                    val provider = future.get()
                                    if (isDisposed || executor.isShutdown) return@addListener
                                    cameraProvider = provider
                                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    analysis.setAnalyzer(executor) { imageProxy ->
                                        if (detectedOnce || isDisposed) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }
                                        try {
                                            val plane = imageProxy.planes.firstOrNull() ?: return@setAnalyzer
                                            val width = imageProxy.width
                                            val height = imageProxy.height
                                            val rowStride = plane.rowStride
                                            val pixelStride = plane.pixelStride
                                            val buffer = plane.buffer
                                            buffer.rewind()
                                            val bytes = ByteArray(width * height)
                                            if (pixelStride == 1 && rowStride == width) {
                                                buffer.get(bytes)
                                            } else {
                                                val rowBuf = ByteArray(rowStride)
                                                for (y in 0 until height) {
                                                    val remaining = buffer.remaining()
                                                    if (remaining <= 0) break
                                                    val toRead = minOf(rowStride, remaining)
                                                    buffer.get(rowBuf, 0, toRead)
                                                    if (pixelStride == 1) {
                                                        System.arraycopy(rowBuf, 0, bytes, y * width, minOf(width, toRead))
                                                    } else {
                                                        var x = 0
                                                        var idx = 0
                                                        while (x < width && idx < toRead) {
                                                            bytes[y * width + x] = rowBuf[idx]
                                                            x++
                                                            idx += pixelStride
                                                        }
                                                    }
                                                }
                                            }
                                            val source = PlanarYUVLuminanceSource(
                                                bytes,
                                                width,
                                                height,
                                                0,
                                                0,
                                                width,
                                                height,
                                                false
                                            )
                                            val bitmap = BinaryBitmap(HybridBinarizer(source))
                                            val res = reader.decodeWithState(bitmap)
                                            if (!res.text.isNullOrBlank() && !isDisposed) {
                                                detectedOnce = true
                                                ContextCompat.getMainExecutor(ctx).execute {
                                                    if (!isDisposed) onScanned(res.text)
                                                }
                                            }
                                        } catch (_: Exception) {
                                            // No QR in frame
                                        } finally {
                                            reader.reset()
                                            imageProxy.close()
                                        }
                                    }
                                    provider.unbindAll()
                                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        }
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close QR Scanner", tint = Color.White)
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(28.dp)
                    ) {
                        Text(
                            "Align the ReceiptsNow QR code inside the camera frame",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PaystackBillingOverviewCard(
    setting: PlatformSetting,
    modifier: Modifier = Modifier,
    plans: List<SubscriptionPlan> = emptyList(),
    onSelectPlan: (SubscriptionPlan) -> Unit = {}
) {
    val defaults = remember { BillingCalculator.defaultLaunchPlans() }
    val monthlyPlan = remember(plans, setting.monthlyPriceNgn) {
        plans.firstOrNull { it.planCode.equals("MONTHLY", ignoreCase = true) }
            ?: defaults.first { it.planCode.equals("MONTHLY", ignoreCase = true) }
                .copy(priceMinor = kotlin.math.round(setting.monthlyPriceNgn * 100.0).toLong().coerceAtLeast(0L))
    }
    val yearlyPlan = remember(plans, setting.annualPriceNgn) {
        plans.firstOrNull { it.planCode.equals("ANNUAL", ignoreCase = true) || it.planCode.equals("YEARLY", ignoreCase = true) }
            ?: defaults.first { it.planCode.equals("ANNUAL", ignoreCase = true) }
                .copy(priceMinor = kotlin.math.round(setting.annualPriceNgn * 100.0).toLong().coerceAtLeast(0L))
    }
    var selectedOption by remember { mutableStateOf("MONTHLY") }
    val chosenPlan = if (selectedOption == "YEARLY") yearlyPlan else monthlyPlan

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Instant Paystack Checkout",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Choose between the Monthly or Yearly subscription to pay securely via Paystack (Card, Bank Transfer, USSD, or OPay). Successful payments activate immediately.",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val monthlySelected = selectedOption == "MONTHLY"
                val yearlySelected = selectedOption == "YEARLY"
                Surface(
                    onClick = { selectedOption = "MONTHLY" },
                    shape = RoundedCornerShape(16.dp),
                    color = if (monthlySelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    border = BorderStroke(
                        width = if (monthlySelected) 2.dp else 1.dp,
                        color = if (monthlySelected) NigerianEmerald else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("checkout_select_monthly")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Monthly Plan",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (monthlySelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Selected Monthly",
                                    tint = NigerianEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            monthlyPlan.formattedPrice(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = NigerianEmerald
                        )
                        Text(
                            "${monthlyPlan.intervalDays} days access",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    onClick = { selectedOption = "YEARLY" },
                    shape = RoundedCornerShape(16.dp),
                    color = if (yearlySelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    border = BorderStroke(
                        width = if (yearlySelected) 2.dp else 1.dp,
                        color = if (yearlySelected) NigerianEmerald else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("checkout_select_yearly")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Yearly Plan",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (yearlySelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Selected Yearly",
                                    tint = NigerianEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            yearlyPlan.formattedPrice(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = NigerianEmerald
                        )
                        Text(
                            "${yearlyPlan.intervalDays} days access",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Button(
                onClick = { onSelectPlan(chosenPlan) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NigerianEmerald,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("instant_paystack_checkout_button")
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (selectedOption == "YEARLY") {
                        "Subscribe Yearly (${yearlyPlan.formattedPrice()})"
                    } else {
                        "Subscribe Monthly (${monthlyPlan.formattedPrice()})"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun decodeEvaluateJsString(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    return runCatching {
        JSONTokener(raw).nextValue() as? String
    }.getOrNull() ?: raw.trim('"')
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PaystackCheckoutDialog(
    plan: com.example.data.model.SubscriptionPlan,
    quote: com.example.data.model.ProrationQuote,
    checkoutUrl: String,
    ownerEmail: String,
    isVerifying: Boolean,
    verificationError: String? = null,
    onPaymentDetected: (reference: String, checkoutConfirmedInWebView: Boolean, detectedChannel: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val fullUrl = remember(checkoutUrl, ownerEmail) {
        com.example.data.billing.PaystackPaymentService.buildCheckoutUrl(checkoutUrl, ownerEmail)
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(fullUrl) }
    var hasAutoTriggered by remember(fullUrl) { mutableStateOf(false) }
    var wasVerifying by remember { mutableStateOf(false) }
    var showManualRefBox by remember { mutableStateOf(false) }
    var manualRefInput by remember { mutableStateOf("") }
    var localNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isVerifying) {
        if (wasVerifying && !isVerifying) {
            // Allow retry if a previous verification attempt finished without closing the dialog
            hasAutoTriggered = false
        }
        wasVerifying = isVerifying
    }

    LaunchedEffect(verificationError) {
        if (!verificationError.isNullOrBlank()) {
            localNotice = verificationError
            showManualRefBox = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                webChromeClient = null
                removeAllViews()
                destroy()
            }
            webViewRef = null
        }
    }

    LaunchedEffect(webViewRef, hasAutoTriggered, isVerifying) {
        while (!hasAutoTriggered && !isVerifying) {
            delay(2200L)
            val wv = webViewRef ?: continue
            wv.evaluateJavascript("(function(){ return document.body ? document.body.innerText : ''; })()") { raw ->
                val text = decodeEvaluateJsString(raw)
                val detected = com.example.data.billing.PaystackPaymentService.detectCompletionFromPageText(
                    url = currentUrl,
                    pageText = text
                )
                if (detected != null && !hasAutoTriggered) {
                    hasAutoTriggered = true
                    onPaymentDetected(detected.reference, true, detected.channelHint)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 14.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(
                    color = NigerianEmerald,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Paystack Checkout • ${plan.name}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "${com.example.data.model.BillingCalculator.formatMinorCurrency(quote.netPayableMinor, plan.currency)} • Auto-activation enabled",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                        IconButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)))
                                }
                                showManualRefBox = true
                                localNotice = "Opened Paystack in browser. After paying, paste your Paystack reference below."
                            },
                            modifier = Modifier.testTag("paystack_open_external_browser_button")
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in External Browser", tint = Color.White)
                        }
                        IconButton(
                            onClick = { if (!isVerifying) onDismiss() },
                            enabled = !isVerifying,
                            modifier = Modifier.testTag("paystack_close_checkout_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close Checkout", tint = Color.White)
                        }
                    }
                }

                if (loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = NigerianEmerald
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadsImagesAutomatically = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val nextUrl = request?.url?.toString().orEmpty()
                                        currentUrl = nextUrl
                                        if (nextUrl.startsWith("http://") || nextUrl.startsWith("https://")) {
                                            if (com.example.data.billing.PaystackPaymentService.isCancelledOrFailedUrl(nextUrl)) {
                                                localNotice = "Payment was cancelled or failed. You can retry inside checkout."
                                                return false
                                            }
                                            val ref = com.example.data.billing.PaystackPaymentService.extractReferenceFromUrl(nextUrl)
                                            if (!ref.isNullOrBlank() && !hasAutoTriggered) {
                                                hasAutoTriggered = true
                                                onPaymentDetected(ref, true, "paystack_redirect")
                                            }
                                            return false
                                        }
                                        if (nextUrl.startsWith("intent://", ignoreCase = true)) {
                                            val parsed = runCatching {
                                                Intent.parseUri(nextUrl, Intent.URI_INTENT_SCHEME)
                                            }.getOrNull()
                                            if (parsed != null) {
                                                val launched = runCatching { ctx.startActivity(parsed) }.isSuccess
                                                if (!launched) {
                                                    val fallbackUrl = parsed.getStringExtra("browser_fallback_url")
                                                    if (!fallbackUrl.isNullOrBlank()) {
                                                        view?.loadUrl(fallbackUrl)
                                                    } else {
                                                        localNotice = "External payment app not installed. Choose Card, Transfer, or USSD inside checkout."
                                                    }
                                                }
                                            }
                                            return true
                                        }
                                        runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(nextUrl)))
                                        }.onFailure {
                                            localNotice = "Could not open external app link. Continue inside Paystack checkout."
                                        }
                                        return true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        if (!url.isNullOrBlank()) currentUrl = url
                                        val ref = url?.let {
                                            com.example.data.billing.PaystackPaymentService.extractReferenceFromUrl(it)
                                        }
                                        if (!ref.isNullOrBlank() && !hasAutoTriggered) {
                                            hasAutoTriggered = true
                                            onPaymentDetected(ref, true, "paystack_redirect")
                                            return
                                        }
                                        view?.evaluateJavascript("(function(){ return document.body ? document.body.innerText : ''; })()") { raw ->
                                            val text = decodeEvaluateJsString(raw)
                                            val detected = com.example.data.billing.PaystackPaymentService.detectCompletionFromPageText(
                                                url = currentUrl,
                                                pageText = text
                                            )
                                            if (detected != null && !hasAutoTriggered) {
                                                hasAutoTriggered = true
                                                onPaymentDetected(detected.reference, true, detected.channelHint)
                                            }
                                        }
                                    }
                                }
                                loadUrl(fullUrl)
                                webViewRef = this
                            }
                        }
                    )

                    if (isVerifying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.52f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(shape = RoundedCornerShape(18.dp)) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(color = NigerianEmerald)
                                    Text("Confirming Paystack Payment...", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Activating your ${plan.name} subscription & generating invoice",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom detection & external fallback bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (localNotice != null) {
                            Text(
                                localNotice!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (showManualRefBox) {
                            OutlinedTextField(
                                value = manualRefInput,
                                onValueChange = { manualRefInput = it },
                                label = { Text("Paystack Payment Reference (from receipt/email)") },
                                placeholder = { Text("e.g. T948271635 or PSK_...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("paystack_manual_ref_input")
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { showManualRefBox = false },
                                    enabled = !isVerifying,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Hide") }
                                Button(
                                    onClick = {
                                        val trimmed = manualRefInput.trim()
                                        if (trimmed.length < 6) {
                                            localNotice = "Enter your Paystack reference (6+ chars)."
                                        } else {
                                            onPaymentDetected(trimmed, false, "paystack_manual_ref")
                                        }
                                    },
                                    enabled = !isVerifying,
                                    colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald),
                                    modifier = Modifier.weight(1f).testTag("paystack_verify_manual_ref_button")
                                ) { Text("Verify & Activate") }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { showManualRefBox = true },
                                    modifier = Modifier.testTag("paystack_toggle_manual_ref_button")
                                ) {
                                    Text("Already paid? Enter Ref", style = MaterialTheme.typography.labelMedium)
                                }
                                Button(
                                    onClick = {
                                        val wv = webViewRef
                                        wv?.evaluateJavascript("(function(){ return document.body ? document.body.innerText : ''; })()") { raw ->
                                            val text = decodeEvaluateJsString(raw)
                                            val detected = com.example.data.billing.PaystackPaymentService.detectCompletionFromPageText(
                                                url = currentUrl,
                                                pageText = text
                                            )
                                            if (detected != null) {
                                                hasAutoTriggered = true
                                                onPaymentDetected(detected.reference, true, detected.channelHint)
                                            } else {
                                                localNotice = "Still on Paystack payment page. Complete payment first, or tap 'Already paid? Enter Ref'."
                                            }
                                        }
                                    },
                                    enabled = !isVerifying,
                                    colors = ButtonDefaults.buttonColors(containerColor = NigerianEmerald),
                                    modifier = Modifier.testTag("paystack_check_completion_button")
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Check Payment Status")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SubscriptionReceiptPreviewDialog(
    payment: SubscriptionPayment,
    onDismiss: () -> Unit
) {
    val bmp by produceState<Bitmap?>(initialValue = null, payment.receiptImageBase64) {
        value = if (payment.receiptImageBase64.isBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                BrandColorExtractor.decodeBase64Bitmap(payment.receiptImageBase64)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (payment.isPaystack()) "Paystack Transaction • ${payment.effectiveReference()}"
                else "Payment Receipt • ${payment.txRef}"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${payment.businessName} (${payment.planName.ifBlank { payment.planCode }} • ₦${"%,.2f".format(payment.amountNgn)})", fontWeight = FontWeight.Bold)
                Text("Owner: ${payment.ownerName} • ${payment.ownerEmail}", style = MaterialTheme.typography.bodySmall)
                if (payment.isPaystack()) {
                    Text("Method: PAYSTACK (${payment.paymentStatus})", style = MaterialTheme.typography.bodySmall, color = NigerianEmerald, fontWeight = FontWeight.SemiBold)
                    if (payment.paystackTransactionId.isNotBlank()) {
                        Text("Paystack Tx ID: ${payment.paystackTransactionId}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (payment.paystackChannel.isNotBlank()) {
                        Text("Channel: ${payment.paystackChannel.uppercase()} ${payment.paystackCardSummary} ${payment.paystackBank}".trim(), style = MaterialTheme.typography.bodySmall)
                    }
                    if (payment.paystackCustomerEmail.isNotBlank()) {
                        Text("Payer Email: ${payment.paystackCustomerEmail}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (payment.paystackGatewayResponse.isNotBlank()) {
                        Text("Gateway: ${payment.paystackGatewayResponse}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (payment.verificationSource.isNotBlank()) {
                        Text("Source: ${payment.verificationSource}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    if (payment.senderName.isNotBlank()) {
                        Text("Sender: ${payment.senderName} ${if (payment.senderBank.isNotBlank()) "(${payment.senderBank})" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                    val previewBmp = bmp
                    if (previewBmp != null && !previewBmp.isRecycled) {
                        Image(
                            bitmap = previewBmp.asImageBitmap(),
                            contentDescription = "Uploaded Payment Receipt",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("No receipt image attached to this record.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        }
    )
}

