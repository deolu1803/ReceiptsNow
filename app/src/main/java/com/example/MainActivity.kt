package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AddBusiness
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.ThemeMode
import com.example.data.model.ReceiptTemplateType
import com.example.security.configureAppCheck
import com.example.ui.components.BrandCrestBadge
import com.example.ui.components.PublicQrVerificationDialog
import com.example.ui.screens.AiChatbotScreen
import com.example.ui.screens.AllReceiptsScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BillingSubscriptionScreen
import com.example.ui.screens.BrandingSettingsScreen
import com.example.ui.screens.CustomersScreen
import com.example.ui.screens.GenerateReceiptScreen
import com.example.ui.screens.HelpAndLegalScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.NotificationsScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.ReceiptCalculatorScreen
import com.example.ui.screens.ReportsAnalyticsScreen
import com.example.ui.screens.StaffManagementScreen
import com.example.ui.screens.StorageOfflineBackupScreen
import com.example.ui.screens.SuperAdminScreen
import com.example.ui.screens.VerificationQueueScreen
import com.example.ui.theme.ForestEmerald
import com.example.ui.theme.ForestEmeraldDeep
import com.example.ui.theme.MintVibrant
import com.example.ui.theme.Receipts1803Theme
import com.example.ui.theme.isLightColor
import com.example.ui.viewmodel.ActionUiState
import com.example.ui.viewmodel.AppViewModel
import com.example.ui.viewmodel.AuthUiState
import com.example.ui.viewmodel.ReceiptFlowViewModel
import kotlinx.coroutines.launch

enum class AppRoute(
    val route: String,
    val label: String,
    val tabLabel: String,
    val icon: ImageVector
) {
    HOME("home", "Dashboard", "Home", Icons.Default.Dashboard),
    GENERATE("generate", "Generate Receipt", "Generate", Icons.Default.AddCircle),
    RECEIPTS("receipts", "Receipts", "Receipts", Icons.Default.Receipt),
    CUSTOMERS("customers", "Customers", "Clients", Icons.Default.People),
    AI_ASSISTANT("ai_assistant", "AI Fast Bot", "AI Bot", Icons.Default.AutoAwesome),
    CALCULATOR("calculator", "Day Tally & Profit", "Day Tally", Icons.Default.Calculate),
    VERIFY_QUEUE("verify_queue", "Verify Queue", "Verify", Icons.Default.VerifiedUser),
    REPORTS("reports", "Reports", "Reports", Icons.Default.Analytics),
    BRANDING("branding", "Branding", "Brand", Icons.Default.Palette),
    STAFF("staff", "Staff & Audit", "Staff", Icons.Default.Group),
    BILLING("billing", "Billing", "Billing", Icons.Default.AccountBalance),
    STORAGE("storage", "Storage & Sync", "Storage", Icons.Default.Backup),
    NOTIFICATIONS("notifications", "Alerts", "Alerts", Icons.Default.Notifications),
    HELP("help", "Help & Legal", "Help", Icons.AutoMirrored.Filled.Help),
    SUPER_ADMIN("super_admin", "Super Admin", "Admin", Icons.Default.AdminPanelSettings)
}

class MainActivity : ComponentActivity() {
    private var activeAppViewModel: AppViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        val initialCode = extractVerifyCode(intent)
        setContent {
            val appViewModel: AppViewModel = viewModel()
            val flowViewModel: ReceiptFlowViewModel = viewModel()
            activeAppViewModel = appViewModel
            LaunchedEffect(initialCode) {
                if (!initialCode.isNullOrBlank()) {
                    appViewModel.setDeepLinkVerificationCode(initialCode)
                }
            }
            Receipts1803Root(appViewModel, flowViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("FIREBASE_APPCHECK_DEBUG_TOKEN")) {
            configureAppCheck(this, intent)
        }
        val code = extractVerifyCode(intent)
        if (!code.isNullOrBlank()) {
            activeAppViewModel?.setDeepLinkVerificationCode(code)
        }
    }

    private fun extractVerifyCode(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        if ((data.scheme == "receiptsnow" || data.scheme == "receiptnow" || data.scheme == "receipts1803") && data.host == "verify") {
            return data.getQueryParameter("code")
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Receipts1803Root(
    appViewModel: AppViewModel,
    flowViewModel: ReceiptFlowViewModel
) {
    val context = LocalContext.current
    val authState by appViewModel.authState.collectAsState()
    val authError by appViewModel.authError.collectAsState()
    val actionState by appViewModel.actionState.collectAsState()
    val businesses by appViewModel.businesses.collectAsState()
    val hasLoadedBusinesses by appViewModel.hasLoadedBusinesses.collectAsState()
    val activeBusiness by appViewModel.activeBusiness.collectAsState()
    val pendingInvites by appViewModel.pendingInvites.collectAsState()
    val platformSetting by appViewModel.platformSetting.collectAsState()
    val announcements by appViewModel.announcements.collectAsState()
    val customers by appViewModel.customers.collectAsState()
    val catalog by appViewModel.catalog.collectAsState()
    val transactions by appViewModel.transactions.collectAsState()
    val receipts by appViewModel.receipts.collectAsState()
    val staffMembers by appViewModel.staffMembers.collectAsState()
    val role by appViewModel.currentStaffRole.collectAsState()
    val isStaffActive by appViewModel.isCurrentStaffActive.collectAsState()
    val auditLogs by appViewModel.auditLogs.collectAsState()
    val notifications by appViewModel.notifications.collectAsState()
    val subscriptionPayments by appViewModel.subscriptionPayments.collectAsState()
    val supportTickets by appViewModel.supportTickets.collectAsState()
    val offlineDrafts by appViewModel.offlineDrafts.collectAsState()
    val isOnline by appViewModel.isOnline.collectAsState()
    val qrResult by appViewModel.qrVerificationResult.collectAsState()
    val deepLinkCode by appViewModel.pendingDeepLinkCode.collectAsState()

    val allBusinessesAdmin by appViewModel.allBusinessesAdmin.collectAsState()
    val allPaymentsAdmin by appViewModel.allPaymentsAdmin.collectAsState()
    val allTicketsAdmin by appViewModel.allTicketsAdmin.collectAsState()
    val subscriptionPlans by appViewModel.subscriptionPlans.collectAsState()
    val planFeatures by appViewModel.planFeatures.collectAsState()
    val planEntitlements by appViewModel.planEntitlements.collectAsState()
    val activeSubscription by appViewModel.activeSubscription.collectAsState()
    val activeUsageQuota by appViewModel.activeUsageQuota.collectAsState()
    val activeStorageUsage by appViewModel.activeStorageUsage.collectAsState()
    val effectiveEntitlements by appViewModel.effectiveEntitlements.collectAsState()
    val businessInvoices by appViewModel.businessInvoices.collectAsState()
    val businessPlanHistory by appViewModel.businessPlanHistory.collectAsState()
    val showUpdateDialog by appViewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    var showQrVerifyModal by remember { mutableStateOf(false) }
    var showCreateExtraBusiness by remember { mutableStateOf(false) }
    var selectedReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedReceiptForDetail = remember(receipts, selectedReceiptId) {
        selectedReceiptId?.let { id -> receipts.firstOrNull { it.id == id } }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionState) {
        when (val s = actionState) {
            is ActionUiState.Success -> {
                snackbarHostState.showSnackbar(s.message)
                appViewModel.clearActionState()
            }
            is ActionUiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                appViewModel.clearActionState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(deepLinkCode) {
        if (!deepLinkCode.isNullOrBlank()) showQrVerifyModal = true
    }

    LaunchedEffect(authState, activeBusiness?.id) {
        val signedIn = authState as? AuthUiState.SignedIn
        if (signedIn == null) {
            selectedReceiptId = null
            showCreateExtraBusiness = false
            flowViewModel.resetFlow()
        } else {
            selectedReceiptId = null
            val defaultTpl = activeBusiness?.let { b ->
                runCatching { ReceiptTemplateType.valueOf(b.defaultTemplate) }
                    .getOrDefault(ReceiptTemplateType.STANDARD)
            } ?: ReceiptTemplateType.STANDARD
            flowViewModel.resetFlow(defaultTpl)
        }
    }

    Receipts1803Theme(
        darkTheme = isDarkTheme,
        brandPrimaryHex = activeBusiness?.primaryColorHex,
        brandAccentHex = activeBusiness?.secondaryColorHex
    ) {
        val view = LocalView.current
        val topHeaderBg = when (authState) {
            is AuthUiState.SignedOut -> MaterialTheme.colorScheme.background
            is AuthUiState.Loading -> MaterialTheme.colorScheme.background
            is AuthUiState.SignedIn -> MaterialTheme.colorScheme.primaryContainer
        }
        val lightStatusIcons = topHeaderBg.isLightColor()
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightNavigationBars = !isDarkTheme
                insetsController.isAppearanceLightStatusBars = lightStatusIcons
            }
        }
        if (showQrVerifyModal) {
            PublicQrVerificationDialog(
                initialCode = deepLinkCode.orEmpty(),
                result = qrResult,
                onLookup = { code -> appViewModel.lookupPublicVerification(code) },
                onDismiss = {
                    showQrVerifyModal = false
                    appViewModel.clearVerificationResult()
                }
            )
        }

        if (showUpdateDialog) {
            val targetVer = platformSetting.latestVersionName.ifBlank { BuildConfig.VERSION_NAME }
            val dialogTitle = platformSetting.updateTitle.ifBlank { "Update to Version $targetVer" }
            AlertDialog(
                onDismissRequest = {
                    if (!platformSetting.forceUpdate) {
                        appViewModel.dismissOptionalUpdate()
                    }
                },
                icon = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.NewReleases,
                                contentDescription = "App Update Available",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text = dialogTitle,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Installed: v${BuildConfig.VERSION_NAME}  →  Available: v$targetVer",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        Text(
                            text = platformSetting.releaseNotes.ifBlank {
                                "A new ReceiptsNow update is ready with the latest improvements, pricing, and fixes."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { appViewModel.applyLiveUpdate(context) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("app_update_confirm_button")
                    ) {
                        Text("Update", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = if (!platformSetting.forceUpdate) {
                    {
                        TextButton(
                            onClick = { appViewModel.dismissOptionalUpdate() },
                            modifier = Modifier.testTag("app_update_later_button")
                        ) {
                            Text("Later")
                        }
                    }
                } else null
            )
        }

        when (val auth = authState) {
            AuthUiState.Loading -> {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(92.dp),
                            shape = CircleShape,
                            color = Color(0xFF083427),
                            border = BorderStroke(2.dp, MintVibrant),
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
                        Spacer(Modifier.height(20.dp))
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            AuthUiState.SignedOut -> {
                AuthScreen(
                    isLoading = false,
                    authError = authError,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { appViewModel.toggleLightDarkTheme(isDarkTheme) },
                    onGoogleSignIn = { appViewModel.signInWithGoogle(context) },
                    onOpenQrVerify = { showQrVerifyModal = true }
                )
            }

            is AuthUiState.SignedIn -> {
                key(auth.user.uid) {
                    val biz = activeBusiness
                    BackHandler(enabled = showCreateExtraBusiness && biz != null) {
                        showCreateExtraBusiness = false
                    }
                    if (!hasLoadedBusinesses && businesses.isEmpty() && !showCreateExtraBusiness) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.size(84.dp),
                                    shape = CircleShape,
                                    color = Color(0xFF083427),
                                    border = BorderStroke(2.dp, MintVibrant)
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
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Text(
                                    "Loading your ReceiptsNow workspace...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    } else if ((biz == null && !auth.isSuperAdmin) || showCreateExtraBusiness) {
                        OnboardingScreen(
                            user = auth.user,
                            pendingInvites = pendingInvites,
                            platformSetting = platformSetting,
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = { appViewModel.toggleLightDarkTheme(isDarkTheme) },
                            onAcceptInvite = { appViewModel.acceptInvite(it) },
                            onCompleteOnboarding = { profile, newBiz ->
                                appViewModel.completeBusinessOnboarding(profile, newBiz) {
                                    showCreateExtraBusiness = false
                                }
                            },
                            onSignOut = { appViewModel.signOut(context) },
                            onCancelExtraWorkspace = if (biz != null) ({ showCreateExtraBusiness = false }) else null
                        )
                } else if (biz != null && !isStaffActive && !auth.isSuperAdmin) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Staff Access Deactivated",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Your staff access for ${biz.tradingName.ifBlank { biz.registeredName }} has been deactivated by the Business Owner. Contact your administrator or switch workspaces.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (businesses.size > 1) {
                                Spacer(Modifier.height(4.dp))
                                Text("Switch Workspace", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                businesses.filter { it.id != biz.id }.forEach { other ->
                                    OutlinedButton(
                                        onClick = { appViewModel.selectBusiness(other.id) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(other.tradingName.ifBlank { other.registeredName })
                                    }
                                }
                            }
                            Button(
                                onClick = { showCreateExtraBusiness = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Create New Business Workspace")
                            }
                            OutlinedButton(
                                onClick = { appViewModel.signOut(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Sign Out")
                            }
                        }
                    }
                } else {
                    val navController = rememberNavController()
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val defaultRoute = if (biz == null && auth.isSuperAdmin) AppRoute.SUPER_ADMIN.route else AppRoute.HOME.route
                    val currentRoute = backStackEntry?.destination?.route ?: defaultRoute
                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()

                    fun navigateTo(route: String) {
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                        scope.launch { drawerState.close() }
                    }

                    val inSuperAdminConsole = auth.isSuperAdmin && (biz == null || currentRoute == AppRoute.SUPER_ADMIN.route)
                    val primaryTabs = if (inSuperAdminConsole || biz == null) {
                        emptyList()
                    } else {
                        buildList {
                            add(AppRoute.HOME)
                            if (role.canGenerateReceipts()) add(AppRoute.GENERATE)
                            add(AppRoute.RECEIPTS)
                            if (role.canManageCustomers()) add(AppRoute.CUSTOMERS)
                            add(AppRoute.AI_ASSISTANT)
                        }
                    }

                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet(
                                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                                modifier = Modifier.width(304.dp)
                            ) {
                                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        BrandCrestBadge(business = biz, sizeDp = 44)
                                        Column {
                                            Text(
                                                biz?.tradingName?.ifBlank { biz.registeredName } ?: "Admin",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(auth.user.email.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }

                                    if (businesses.size > 1) {
                                        Text("Switch Business Workspace", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                        businesses.forEach { b ->
                                            NavigationDrawerItem(
                                                label = { Text(b.tradingName.ifBlank { b.registeredName }) },
                                                selected = b.id == biz?.id,
                                                onClick = {
                                                    appViewModel.selectBusiness(b.id)
                                                    scope.launch { drawerState.close() }
                                                }
                                            )
                                        }
                                    }

                                    NavigationDrawerItem(
                                        icon = { Icon(Icons.Default.AddBusiness, contentDescription = null) },
                                        label = { Text("Add Business Workspace") },
                                        selected = false,
                                        onClick = {
                                            showCreateExtraBusiness = true
                                            scope.launch { drawerState.close() }
                                        }
                                    )

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                    if (biz != null) {
                                        val drawerRoutes = buildList {
                                            add(AppRoute.HOME)
                                            if (role.canGenerateReceipts()) add(AppRoute.GENERATE)
                                            add(AppRoute.RECEIPTS)
                                            if (role.canManageCustomers()) add(AppRoute.CUSTOMERS)
                                            add(AppRoute.AI_ASSISTANT)
                                            add(AppRoute.CALCULATOR)
                                            if (role.canVerifyTransactions()) add(AppRoute.VERIFY_QUEUE)
                                            if (role.canViewAnalyticsAndReports()) add(AppRoute.REPORTS)
                                            if (role.canManageSettingsAndBranding()) add(AppRoute.BRANDING)
                                            add(AppRoute.STAFF)
                                            if (role.canManageStaffAndSubscription()) add(AppRoute.BILLING)
                                            add(AppRoute.STORAGE)
                                            add(AppRoute.NOTIFICATIONS)
                                            add(AppRoute.HELP)
                                        }
                                        drawerRoutes.forEach { dest ->
                                            NavigationDrawerItem(
                                                icon = { Icon(dest.icon, contentDescription = null) },
                                                label = { Text(if (dest == AppRoute.AI_ASSISTANT) "AI Assistant" else dest.label) },
                                                selected = currentRoute == dest.route,
                                                onClick = { navigateTo(dest.route) }
                                            )
                                        }
                                    }

                                    if (auth.isSuperAdmin) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                        NavigationDrawerItem(
                                            icon = { Icon(AppRoute.SUPER_ADMIN.icon, contentDescription = null) },
                                            label = { Text(AppRoute.SUPER_ADMIN.label) },
                                            selected = currentRoute == AppRoute.SUPER_ADMIN.route,
                                            onClick = { navigateTo(AppRoute.SUPER_ADMIN.route) }
                                        )
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    Text(
                                        "Appearance",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                    SingleChoiceSegmentedButtonRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        ThemeMode.entries.forEachIndexed { idx, mode ->
                                            SegmentedButton(
                                                selected = themeMode == mode,
                                                onClick = { appViewModel.setThemeMode(mode) },
                                                shape = SegmentedButtonDefaults.itemShape(
                                                    index = idx,
                                                    count = ThemeMode.entries.size
                                                ),
                                                modifier = Modifier.testTag("drawer_theme_${mode.name.lowercase()}")
                                            ) {
                                                Text(
                                                    mode.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    NavigationDrawerItem(
                                        icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                                        label = { Text("Public QR Verify") },
                                        selected = false,
                                        onClick = {
                                            showQrVerifyModal = true
                                            scope.launch { drawerState.close() }
                                        }
                                    )
                                    NavigationDrawerItem(
                                        icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                                        label = { Text("Sign Out") },
                                        selected = false,
                                        onClick = { appViewModel.signOut(context) }
                                    )
                                }
                            }
                        }
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val isWide = maxWidth >= 720.dp
                            Scaffold(
                                contentWindowInsets = WindowInsets.safeDrawing,
                                snackbarHost = {
                                    SnackbarHost(snackbarHostState) { data ->
                                        Snackbar(
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.padding(12.dp),
                                            action = data.visuals.actionLabel?.let { label ->
                                                {
                                                    androidx.compose.material3.TextButton(
                                                        onClick = { data.performAction() }
                                                    ) { Text(label) }
                                                }
                                            }
                                        ) {
                                            Text(data.visuals.message)
                                        }
                                    }
                                },
                                topBar = {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                                        shadowElevation = 2.dp
                                    ) {
                                        TopAppBar(
                                            title = {
                                                val titleText = AppRoute.entries.firstOrNull { it.route == currentRoute }?.label
                                                    ?: "ReceiptsNow"
                                                Text(
                                                    text = titleText,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1
                                                )
                                            },
                                            navigationIcon = {
                                                Surface(
                                                    onClick = { scope.launch { drawerState.open() } },
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f),
                                                    modifier = Modifier
                                                        .padding(start = 8.dp)
                                                        .size(44.dp)
                                                        .testTag("open_drawer_button")
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            Icons.Default.Menu,
                                                            contentDescription = "Open Menu",
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            actions = {
                                                Surface(
                                                    onClick = { appViewModel.toggleLightDarkTheme(isDarkTheme) },
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f),
                                                    modifier = Modifier
                                                        .padding(end = 6.dp)
                                                        .size(42.dp)
                                                        .testTag("topbar_theme_toggle_button")
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                                            contentDescription = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.size(19.dp)
                                                        )
                                                    }
                                                }
                                                Surface(
                                                    onClick = { showQrVerifyModal = true },
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f),
                                                    modifier = Modifier
                                                        .padding(end = 10.dp)
                                                        .size(42.dp)
                                                        .testTag("topbar_qr_verify_button")
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            Icons.Default.QrCodeScanner,
                                                            contentDescription = "Verify QR Code",
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.size(19.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            colors = TopAppBarDefaults.topAppBarColors(
                                                containerColor = Color.Transparent,
                                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        )
                                    }
                                },
                                bottomBar = {
                                    if (!isWide && primaryTabs.size > 1) {
                                        val pillBg = if (isDarkTheme) Color(0xFF0A2B20) else ForestEmeraldDeep
                                        val pillBorder = if (isDarkTheme) Color(0xFF1F4E3C) else Color.White.copy(alpha = 0.15f)
                                        val activeCapsuleColor = if (isDarkTheme) MintVibrant else Color.White
                                        val activeContentColor = ForestEmeraldDeep
                                        val inactiveContentColor = Color.White.copy(alpha = 0.72f)

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .navigationBarsPadding()
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(28.dp),
                                                color = pillBg,
                                                border = BorderStroke(1.dp, pillBorder),
                                                shadowElevation = 10.dp,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 6.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    primaryTabs.forEach { dest ->
                                                        val selected = currentRoute == dest.route
                                                        Surface(
                                                            onClick = { navigateTo(dest.route) },
                                                            color = if (selected) activeCapsuleColor else Color.Transparent,
                                                            shape = RoundedCornerShape(22.dp),
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .heightIn(min = 52.dp)
                                                                .testTag("bottom_tab_${dest.route}")
                                                        ) {
                                                            Column(
                                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                                verticalArrangement = Arrangement.Center,
                                                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = dest.icon,
                                                                    contentDescription = dest.label,
                                                                    tint = if (selected) activeContentColor else inactiveContentColor,
                                                                    modifier = Modifier.size(20.dp)
                                                                )
                                                                Spacer(Modifier.height(2.dp))
                                                                Text(
                                                                    text = dest.tabLabel,
                                                                    fontSize = 11.sp,
                                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                                    color = if (selected) activeContentColor else inactiveContentColor,
                                                                    maxLines = 1,
                                                                    softWrap = false
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            ) { innerPadding ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding)
                                ) {
                                    if (isWide && primaryTabs.size > 1) {
                                        NavigationRail(modifier = Modifier.fillMaxHeight()) {
                                            primaryTabs.forEach { dest ->
                                                NavigationRailItem(
                                                    selected = currentRoute == dest.route,
                                                    onClick = { navigateTo(dest.route) },
                                                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                                                    label = { Text(dest.tabLabel, maxLines = 1, softWrap = false) }
                                                )
                                            }
                                        }
                                    }

                                    val startRoute = if (biz == null && auth.isSuperAdmin) {
                                        AppRoute.SUPER_ADMIN.route
                                    } else {
                                        AppRoute.HOME.route
                                    }

                                    NavHost(
                                        navController = navController,
                                        startDestination = startRoute,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        composable(AppRoute.HOME.route) {
                                            if (biz != null) {
                                                HomeDashboardScreen(
                                                    business = biz,
                                                    role = role,
                                                    receipts = receipts,
                                                    customers = customers,
                                                    announcements = announcements,
                                                    pendingDrafts = offlineDrafts,
                                                    isOnline = isOnline,
                                                    effectiveEntitlements = effectiveEntitlements,
                                                    onGenerateReceipt = { navigateTo(AppRoute.GENERATE.route) },
                                                    onOpenAiAssistant = { navigateTo(AppRoute.AI_ASSISTANT.route) },
                                                    onOpenCalculator = { navigateTo(AppRoute.CALCULATOR.route) },
                                                    onOpenVerificationQueue = { navigateTo(AppRoute.VERIFY_QUEUE.route) },
                                                    onOpenCustomers = { navigateTo(AppRoute.CUSTOMERS.route) },
                                                    onOpenReports = { navigateTo(AppRoute.REPORTS.route) },
                                                    onOpenBranding = { navigateTo(AppRoute.BRANDING.route) },
                                                    onOpenBilling = { navigateTo(AppRoute.BILLING.route) },
                                                    onOpenQrVerify = { showQrVerifyModal = true },
                                                    onSyncOfflineDrafts = { appViewModel.syncPendingOfflineDrafts(silent = false) },
                                                    onSelectReceipt = { r ->
                                                        selectedReceiptId = r.id
                                                        navigateTo(AppRoute.RECEIPTS.route)
                                                    }
                                                )
                                            }
                                        }
                                        composable(AppRoute.GENERATE.route) {
                                            if (biz != null) {
                                                GenerateReceiptScreen(
                                                    business = biz,
                                                    role = role,
                                                    repository = appViewModel.repository,
                                                    subscriptionRepository = appViewModel.subscriptionRepository,
                                                    effectiveEntitlements = effectiveEntitlements,
                                                    customers = customers,
                                                    catalog = catalog,
                                                    isOnline = isOnline,
                                                    flowViewModel = flowViewModel,
                                                    onOpenBilling = { navigateTo(AppRoute.BILLING.route) }
                                                )
                                            }
                                        }
                                        composable(AppRoute.RECEIPTS.route) {
                                            if (biz != null) {
                                                AllReceiptsScreen(
                                                    business = biz,
                                                    role = role,
                                                    receipts = receipts,
                                                    selectedReceipt = selectedReceiptForDetail,
                                                    effectiveEntitlements = effectiveEntitlements,
                                                    onSelectReceipt = { selectedReceiptId = it?.id },
                                                    onOpenQrVerify = { showQrVerifyModal = true },
                                                    onUpdateVerificationStatus = { rct, st, note ->
                                                        appViewModel.updateVerificationStatus(rct, st, note)
                                                    },
                                                    onAmendReceipt = { rct ->
                                                        flowViewModel.startAmendmentFrom(rct)
                                                        navigateTo(AppRoute.GENERATE.route)
                                                    }
                                                )
                                            }
                                        }
                                        composable(AppRoute.CUSTOMERS.route) {
                                            if (biz != null) {
                                                CustomersScreen(
                                                    business = biz,
                                                    role = role,
                                                    customers = customers,
                                                    catalog = catalog,
                                                    receipts = receipts,
                                                    effectiveEntitlements = effectiveEntitlements,
                                                    onUpsertCustomer = { appViewModel.upsertCustomer(it) },
                                                    onSaveCatalogItem = { appViewModel.saveCatalogItem(it) }
                                                )
                                            }
                                        }
                                        composable(AppRoute.AI_ASSISTANT.route) {
                                            if (biz != null) {
                                                val aiChatMessages by appViewModel.aiChatMessages.collectAsStateWithLifecycle()
                                                val aiChatBusy by appViewModel.aiChatBusy.collectAsStateWithLifecycle()
                                                AiChatbotScreen(
                                                    business = biz,
                                                    messages = aiChatMessages,
                                                    isBusy = aiChatBusy,
                                                    effectiveEntitlements = effectiveEntitlements,
                                                    superAdminEmail = platformSetting.notificationEmail,
                                                    onSendMessage = { txt, uri ->
                                                        appViewModel.sendAiChatMessage(
                                                            userText = txt,
                                                            imageUri = uri,
                                                            receiptFlowViewModel = flowViewModel,
                                                            onNavigateToRoute = { target -> navigateTo(target) }
                                                        )
                                                    },
                                                    onSubmitQuickComplaint = { sub, cat, desc ->
                                                        appViewModel.submitQuickComplaintFromChatbot(sub, cat, desc)
                                                    },
                                                    onNavigateToRoute = { target -> navigateTo(target) },
                                                    onOpenReceiptDetail = { r ->
                                                        selectedReceiptId = r.id
                                                        navigateTo(AppRoute.RECEIPTS.route)
                                                    },
                                                    onClearChat = { appViewModel.clearAiChat() }
                                                )
                                            }
                                        }
                                        composable(AppRoute.CALCULATOR.route) {
                                            if (biz != null) {
                                                val ledgerTitle by appViewModel.ledgerTitle.collectAsStateWithLifecycle()
                                                val ledgerDate by appViewModel.ledgerDate.collectAsStateWithLifecycle()
                                                val ledgerEntries by appViewModel.ledgerEntries.collectAsStateWithLifecycle()
                                                val ledgerSummary by appViewModel.ledgerSummary.collectAsStateWithLifecycle()
                                                val savedLedgers by appViewModel.dailyLedgers.collectAsStateWithLifecycle()
                                                val ledgerBusy by appViewModel.ledgerBusy.collectAsStateWithLifecycle()
                                                val ledgerProgress by appViewModel.ledgerProgress.collectAsStateWithLifecycle()
                                                val ledgerError by appViewModel.ledgerError.collectAsStateWithLifecycle()
                                                ReceiptCalculatorScreen(
                                                    business = biz,
                                                    title = ledgerTitle,
                                                    ledgerDate = ledgerDate,
                                                    entries = ledgerEntries,
                                                    summary = ledgerSummary,
                                                    savedSheets = savedLedgers,
                                                    isBusy = ledgerBusy,
                                                    progressText = ledgerProgress,
                                                    errorText = ledgerError,
                                                    onTitleChange = { appViewModel.setLedgerTitle(it) },
                                                    onDateChange = { appViewModel.setLedgerDate(it) },
                                                    onUploadBatchUris = { uris -> appViewModel.analyzeBatchReceiptsForLedger(uris) },
                                                    onCaptureCameraBitmap = { bmp -> appViewModel.analyzeCapturedBitmapForLedger(bmp) },
                                                    onImportTodaysIssued = { appViewModel.importTodaysIssuedReceiptsToLedger() },
                                                    onUpsertEntry = { entry -> appViewModel.addOrUpdateLedgerEntry(entry) },
                                                    onRemoveEntry = { entryId -> appViewModel.removeLedgerEntry(entryId) },
                                                    onClearDraft = { appViewModel.clearLedgerDraft() },
                                                    onSaveSheet = { appViewModel.saveCurrentLedgerSheet() },
                                                    onLoadSheet = { sheet -> appViewModel.loadSavedLedgerSheet(sheet) },
                                                    onDeleteSheet = { sheetId -> appViewModel.deleteSavedLedgerSheet(sheetId) },
                                                    onDismissError = { appViewModel.clearLedgerError() }
                                                )
                                            }
                                        }
                                        composable(AppRoute.VERIFY_QUEUE.route) {
                                            VerificationQueueScreen(
                                                role = role,
                                                receipts = receipts,
                                                transactions = transactions,
                                                onUpdateVerificationStatus = { rct, st, note ->
                                                    appViewModel.updateVerificationStatus(rct, st, note)
                                                }
                                            )
                                        }
                                        composable(AppRoute.REPORTS.route) {
                                            if (biz != null) {
                                                ReportsAnalyticsScreen(
                                                    business = biz,
                                                    role = role,
                                                    receipts = receipts,
                                                    effectiveEntitlements = effectiveEntitlements
                                                )
                                            }
                                        }
                                        composable(AppRoute.BRANDING.route) {
                                            if (biz != null) {
                                                BrandingSettingsScreen(
                                                    business = biz,
                                                    role = role,
                                                    themeMode = themeMode,
                                                    onSelectThemeMode = { appViewModel.setThemeMode(it) },
                                                    onSaveBusiness = { appViewModel.updateBusinessSettings(it) }
                                                )
                                            }
                                        }
                                        composable(AppRoute.STAFF.route) {
                                            if (biz != null) {
                                                StaffManagementScreen(
                                                    business = biz,
                                                    role = role,
                                                    staffMembers = staffMembers,
                                                    auditLogs = auditLogs,
                                                    effectiveEntitlements = effectiveEntitlements,
                                                    onInviteStaff = { e, n, r -> appViewModel.inviteStaff(e, n, r) },
                                                    onToggleStaffActive = { m, a -> appViewModel.toggleStaffActive(m, a) }
                                                )
                                            }
                                        }
                                        composable(AppRoute.BILLING.route) {
                                            if (biz != null) {
                                                BillingSubscriptionScreen(
                                                    business = biz,
                                                    platformSetting = platformSetting,
                                                    payments = subscriptionPayments,
                                                    plans = subscriptionPlans,
                                                    planFeatures = planFeatures,
                                                    planEntitlements = planEntitlements,
                                                    activeSubscription = activeSubscription,
                                                    usageQuota = activeUsageQuota,
                                                    storageUsage = activeStorageUsage,
                                                    effectiveEntitlements = effectiveEntitlements,
                                                    invoices = businessInvoices,
                                                    planHistory = businessPlanHistory,
                                                    isSubmitting = actionState is ActionUiState.Busy,
                                                    onUpdateAutoRenewOrCancel = { autoRenew, cancelAtPeriodEnd, cancelImmediately ->
                                                        appViewModel.updateAutoRenewOrCancellation(
                                                            autoRenew = autoRenew,
                                                            cancelAtPeriodEnd = cancelAtPeriodEnd,
                                                            cancelImmediately = cancelImmediately
                                                        )
                                                    },
                                                    onVerifyPaystackPayment = { selectedPlan, reference, confirmedInWebView, channelHint, paymentUrl, prorationQuote, onError, onDone ->
                                                        appViewModel.verifyAndActivatePaystackSubscription(
                                                            selectedPlan = selectedPlan,
                                                            reference = reference,
                                                            checkoutConfirmedInWebView = confirmedInWebView,
                                                            detectedChannel = channelHint,
                                                            paymentUrl = paymentUrl,
                                                            prorationQuote = prorationQuote,
                                                            onError = onError,
                                                            onDone = onDone
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                        composable(AppRoute.STORAGE.route) {
                                            if (biz != null) {
                                                StorageOfflineBackupScreen(
                                                    business = biz,
                                                    customers = customers,
                                                    receipts = receipts,
                                                    payments = subscriptionPayments,
                                                    offlineDrafts = offlineDrafts,
                                                    storageUsage = activeStorageUsage,
                                                    effectiveEntitlements = effectiveEntitlements,
                                                    onSyncNow = { appViewModel.syncPendingOfflineDrafts(silent = false) },
                                                    onDeleteDraft = { appViewModel.deleteOfflineDraft(it) }
                                                )
                                            }
                                        }
                                        composable(AppRoute.NOTIFICATIONS.route) {
                                            NotificationsScreen(
                                                notifications = notifications,
                                                announcements = announcements,
                                                onMarkRead = { appViewModel.markNotificationRead(it) }
                                            )
                                        }
                                        composable(AppRoute.HELP.route) {
                                            HelpAndLegalScreen(
                                                tickets = supportTickets,
                                                platformSetting = platformSetting,
                                                onCreateTicket = { sub, cat, desc ->
                                                    appViewModel.createSupportTicket(sub, cat, desc)
                                                }
                                            )
                                        }
                                        composable(AppRoute.SUPER_ADMIN.route) {
                                            if (auth.isSuperAdmin) {
                                                val allMailNotificationsAdmin by appViewModel.allMailNotificationsAdmin.collectAsStateWithLifecycle()
                                                val allSubscriptionsAdmin by appViewModel.allSubscriptionsAdmin.collectAsStateWithLifecycle()
                                                val allInvoicesAdmin by appViewModel.allInvoicesAdmin.collectAsStateWithLifecycle()
                                                val allQuotasAdmin by appViewModel.allQuotasAdmin.collectAsStateWithLifecycle()
                                                val allStorageAdmin by appViewModel.allStorageAdmin.collectAsStateWithLifecycle()
                                                val allPlanChangesAdmin by appViewModel.allPlanChangesAdmin.collectAsStateWithLifecycle()
                                                val allEventsAdmin by appViewModel.allEventsAdmin.collectAsStateWithLifecycle()
                                                val allCustomEntitlementsAdmin by appViewModel.allCustomEntitlementsAdmin.collectAsStateWithLifecycle()
                                                val mrrArrSummary by appViewModel.mrrArrSummary.collectAsStateWithLifecycle()
                                                val emailRelayTestStatus by appViewModel.emailRelayTestStatus.collectAsStateWithLifecycle()
                                                SuperAdminScreen(
                                                    allBusinesses = allBusinessesAdmin,
                                                    allPayments = allPaymentsAdmin,
                                                    allMailNotifications = allMailNotificationsAdmin,
                                                    allTickets = allTicketsAdmin,
                                                    platformSetting = platformSetting,
                                                    plans = subscriptionPlans,
                                                    planEntitlements = planEntitlements,
                                                    planFeatures = planFeatures,
                                                    allSubscriptions = allSubscriptionsAdmin,
                                                    allInvoices = allInvoicesAdmin,
                                                    allQuotas = allQuotasAdmin,
                                                    allStorageUsages = allStorageAdmin,
                                                    allPlanChanges = allPlanChangesAdmin,
                                                    allEvents = allEventsAdmin,
                                                    allCustomEntitlements = allCustomEntitlementsAdmin,
                                                    mrrArrSummary = mrrArrSummary,
                                                    emailRelayTestStatus = emailRelayTestStatus,
                                                    onSavePlatformSettings = { appViewModel.savePlatformSettingsAdmin(it) },
                                                    onTestEmailRelay = { appViewModel.testEmailRelayAdmin(it) },
                                                    onClearEmailRelayTestStatus = { appViewModel.clearEmailRelayTestStatus() },
                                                    onResendPaymentNotification = { appViewModel.resendPaymentNotificationAdmin(it) },
                                                    onVerifyPayment = { payment, verifyViaApi ->
                                                        appViewModel.verifySubscriptionPaymentAdmin(payment, verifyViaApi)
                                                    },
                                                    onMarkPaymentStatus = { payment, status, reason ->
                                                        appViewModel.markPaymentStatusAdmin(payment, status, reason)
                                                    },
                                                    onRejectPayment = { payment, reason ->
                                                        appViewModel.rejectSubscriptionPaymentAdmin(payment, reason)
                                                    },
                                                    onSetBusinessStatus = { id, st -> appViewModel.setBusinessAccountStatusAdmin(id, st) },
                                                    onPublishAnnouncement = { t, m, c -> appViewModel.publishAnnouncementAdmin(t, m, c) },
                                                    onReplyTicket = { id, st, r -> appViewModel.replySupportTicketAdmin(id, st, r) },
                                                    onResendTicketNotification = { appViewModel.resendTicketNotificationAdmin(it) },
                                                    onSavePlan = { plan, ent, feats -> appViewModel.savePlanAdmin(plan, ent, feats) },
                                                    onSetPlanStatus = { plan, st, vis -> appViewModel.setPlanStatusAdmin(plan, st, vis) },
                                                    onSaveCustomEntitlement = { targetBiz, override -> appViewModel.saveCustomEntitlementAdmin(targetBiz, override) },
                                                    onSuspendOrRestoreSubscription = { targetBiz, susp, reason ->
                                                        appViewModel.suspendOrRestoreSubscriptionAdmin(targetBiz, susp, reason)
                                                    },
                                                    onRunExpirySweep = { appViewModel.processCycleExpiriesAdmin() },
                                                    onPublishLiveUpdate = { verName, verCode, notes, url, force, announce ->
                                                        appViewModel.publishLiveUpdateAdmin(verName, verCode, notes, url, force, announce)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
