package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.ForestEmerald
import com.example.ui.theme.ForestEmeraldDeep
import com.example.ui.theme.MintVibrant

@Composable
fun AuthScreen(
    isLoading: Boolean,
    authError: String?,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onOpenQrVerify: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColors = if (isDarkTheme) {
        listOf(Color(0xFF03100B), ForestEmeraldDeep, Color(0xFF0A2A1F))
    } else {
        listOf(MaterialTheme.colorScheme.background, Color(0xFFE3F6ED), Color(0xFFD3EFE2))
    }
    val titleColor = if (isDarkTheme) Color.White else ForestEmeraldDeep
    val subtitleColor = if (isDarkTheme) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    val crestBg = if (isDarkTheme) ForestEmeraldDeep else ForestEmerald
    val crestBorder = if (isDarkTheme) MintVibrant else Color(0xFF10B981)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = bgColors)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onToggleTheme,
            shape = RoundedCornerShape(999.dp),
            color = if (isDarkTheme) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (isDarkTheme) Color.White.copy(alpha = 0.28f) else MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 16.dp)
                .testTag("auth_theme_toggle_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                    tint = if (isDarkTheme) BurnishedGold else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (isDarkTheme) "Light Mode" else "Dark Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isDarkTheme) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
                color = crestBg,
                border = BorderStroke(2.5.dp, crestBorder),
                shadowElevation = 6.dp
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_1803r_emerald_icon_1794867502860),
                    contentDescription = "ReceiptsNow Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .scale(1.42f)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "ReceiptsNow",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = titleColor
                )
                Text(
                    text = "Official Business Payment Receipts & Bank Proof Intelligence for Nigeria",
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(4.dp))

            if (!authError.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = authError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Button(
                onClick = onGoogleSignIn,
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("google_sign_in_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onSecondary,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Signing in...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = "Sign in with Google")
                    Spacer(Modifier.width(10.dp))
                    Text("Continue with Google", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }

            val qrAccent = if (isDarkTheme) BurnishedGold else MaterialTheme.colorScheme.primary
            OutlinedButton(
                onClick = onOpenQrVerify,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("auth_verify_qr_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, qrAccent.copy(alpha = 0.75f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = qrAccent)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Verify a Receipt")
                Spacer(Modifier.width(8.dp))
                Text("Verify Receipt QR / Code", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
