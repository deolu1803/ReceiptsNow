package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.ai.AiAssistantService
import com.example.data.ai.AssistantActionResult
import com.example.data.ai.AssistantActionType
import com.example.data.ai.AssistantHistoryTurn
import com.example.data.ai.ReceiptLedgerAnalyzerService
import com.example.data.ai.ReceiptOcrService
import com.example.data.billing.PaystackPaymentService
import com.example.data.billing.PaystackVerificationResult
import com.example.data.local.AppLocalDatabase
import com.example.data.local.OfflineDraftEntity
import com.example.data.local.ThemeMode
import com.example.data.local.ThemePreferences
import com.example.data.model.AppNotification
import com.example.data.model.AuditLog
import com.example.data.model.BankTransactionRecord
import com.example.data.model.BillingCalculator
import com.example.data.model.Business
import com.example.data.model.BusinessSubscription
import com.example.data.model.CatalogItem
import com.example.data.model.CustomBusinessEntitlement
import com.example.data.model.Customer
import com.example.data.model.DailyLedgerSheet
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.LedgerCalculationSummary
import com.example.data.model.LedgerCalculator
import com.example.data.model.LedgerCategory
import com.example.data.model.LedgerEntryItem
import com.example.data.model.MailNotification
import com.example.data.model.MrrArrSummary
import com.example.data.model.PlatformAnnouncement
import com.example.data.model.PlatformSetting
import com.example.data.model.PlanChangeHistory
import com.example.data.model.PlanEntitlement
import com.example.data.model.PlanFeature
import com.example.data.model.PlanStatus
import com.example.data.model.ProrationQuote
import com.example.data.model.PublicVerification
import com.example.data.model.ReceiptRecord
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StaffInvite
import com.example.data.model.StaffMember
import com.example.data.model.StaffRole
import com.example.data.model.StorageUsage
import com.example.data.model.SubscriptionEvent
import com.example.data.model.SubscriptionInvoice
import com.example.data.model.SubscriptionPayment
import com.example.data.model.SubscriptionPlan
import com.example.data.model.SubscriptionStatus
import com.example.data.model.SupportTicket
import com.example.data.model.UsageQuota
import com.example.data.model.UserProfile
import com.example.data.model.VerificationStatus
import com.example.data.repository.BusinessRepository
import com.example.data.repository.SubscriptionRepository
import com.example.ui.auth.GoogleAuthHelper
import com.example.ui.auth.GoogleSignInOutcome
import com.example.util.AmountInWords
import com.example.util.ConnectivityObserver
import com.example.util.EmailRelayClient
import com.example.util.ReceiptProofEncoder
import com.example.util.ReceiptRenderer
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object SignedOut : AuthUiState
    data class SignedIn(val user: FirebaseUser, val isSuperAdmin: Boolean) : AuthUiState
}

sealed interface ActionUiState {
    data object Idle : ActionUiState
    data class Busy(val message: String) : ActionUiState
    data class Success(val message: String) : ActionUiState
    data class Error(val message: String) : ActionUiState
}

data class PaymentSubmissionOutcome(
    val paymentId: String,
    val targetEmail: String,
    val subject: String,
    val body: String,
    val receiptImageBase64: String = "",
    val receiptFileName: String = "",
    val autoEmailDelivered: Boolean = false,
    val deliveryNote: String = "",
    val isAutoActivated: Boolean = false,
    val planName: String = "",
    val paystackReference: String = "",
    val amountFormatted: String = "",
    val invoiceId: String = "",
    val channelSummary: String = "",
    val verificationSource: String = ""
)

data class EmailRelayTestStatus(
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val message: String = ""
)

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val attachmentBitmap: Bitmap? = null,
    val attachmentName: String = "",
    val generatedReceipt: ReceiptRecord? = null,
    val renderedReceiptBitmap: Bitmap? = null,
    val preparedDraftNotice: String? = null,
    val reportedTicketId: String? = null,
    val ticketEmailDelivered: Boolean? = null,
    val suggestedRoute: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val repository = BusinessRepository(application)
    val subscriptionRepository = SubscriptionRepository(application)
    val localDb = AppLocalDatabase.getInstance(application)
    private val themePreferences = ThemePreferences(application)
    val connectivityObserver = ConnectivityObserver(application)
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val aiAssistantService = AiAssistantService()
    private val receiptOcrService = ReceiptOcrService()
    private val receiptLedgerAnalyzer = ReceiptLedgerAnalyzerService(receiptOcrService)

    private var lastAiAttachmentBitmap: Bitmap? = null
    private var lastAiAttachmentName: String = ""
    private var lastAiAttachmentSize: Long = 0L
    private var lastAiAttachmentIsPdf: Boolean = false

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _actionState = MutableStateFlow<ActionUiState>(ActionUiState.Idle)
    val actionState: StateFlow<ActionUiState> = _actionState.asStateFlow()

    private val _emailRelayTestStatus = MutableStateFlow<EmailRelayTestStatus?>(null)
    val emailRelayTestStatus: StateFlow<EmailRelayTestStatus?> = _emailRelayTestStatus.asStateFlow()

    private val _aiChatMessages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val aiChatMessages: StateFlow<List<AiChatMessage>> = _aiChatMessages.asStateFlow()

    private val _aiChatBusy = MutableStateFlow(false)
    val aiChatBusy: StateFlow<Boolean> = _aiChatBusy.asStateFlow()

    // End-of-Day Receipt Calculator state
    private fun todayDateLagos(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Africa/Lagos")
        }.format(Date())

    private fun todayDisplayLagos(): String =
        SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Africa/Lagos")
        }.format(Date())

    private val _ledgerTitle = MutableStateFlow("End-of-Day Breakdown (${todayDisplayLagos()})")
    val ledgerTitle: StateFlow<String> = _ledgerTitle.asStateFlow()

    private val _ledgerDate = MutableStateFlow(todayDateLagos())
    val ledgerDate: StateFlow<String> = _ledgerDate.asStateFlow()

    private val _ledgerEntries = MutableStateFlow<List<LedgerEntryItem>>(emptyList())
    val ledgerEntries: StateFlow<List<LedgerEntryItem>> = _ledgerEntries.asStateFlow()

    private val _ledgerLoadedId = MutableStateFlow("")
    val ledgerLoadedId: StateFlow<String> = _ledgerLoadedId.asStateFlow()

    private val _ledgerBusy = MutableStateFlow(false)
    val ledgerBusy: StateFlow<Boolean> = _ledgerBusy.asStateFlow()

    private val _ledgerProgress = MutableStateFlow<String?>(null)
    val ledgerProgress: StateFlow<String?> = _ledgerProgress.asStateFlow()

    private val _ledgerError = MutableStateFlow<String?>(null)
    val ledgerError: StateFlow<String?> = _ledgerError.asStateFlow()

    // Live App Update popup state
    private val _dismissedUpdateTag = MutableStateFlow<String?>(null)
    private val _liveConfigSyncRevision = MutableStateFlow(0L)

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _selectedBusinessId = MutableStateFlow<String?>(null)

    val isOnline: StateFlow<Boolean> = connectivityObserver.observeOnline()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), connectivityObserver.isOnlineNow())

    val themeMode: StateFlow<ThemeMode> = themePreferences.themeModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    private val currentUidFlow = MutableStateFlow<String?>(null)
    private val currentEmailFlow = MutableStateFlow<String>("")

    val userProfile: StateFlow<UserProfile?> = currentUidFlow
        .flatMapLatest { uid ->
            if (uid.isNullOrBlank()) flowOf(null)
            else repository.observeUserProfile(uid).catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _hasLoadedBusinesses = MutableStateFlow(false)
    val hasLoadedBusinesses: StateFlow<Boolean> = _hasLoadedBusinesses.asStateFlow()

    val businesses: StateFlow<List<Business>> = currentUidFlow
        .flatMapLatest { uid ->
            if (uid.isNullOrBlank()) {
                _hasLoadedBusinesses.value = false
                flowOf(emptyList())
            } else {
                _hasLoadedBusinesses.value = false
                repository.observeUserBusinesses(uid)
                    .onEach { _hasLoadedBusinesses.value = true }
                    .catch {
                        _hasLoadedBusinesses.value = true
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingInvites: StateFlow<List<StaffInvite>> = currentEmailFlow
        .flatMapLatest { email ->
            if (email.isBlank()) flowOf(emptyList())
            else repository.observePendingInvitesForEmail(email).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeBusiness: StateFlow<Business?> = combine(
        businesses,
        userProfile,
        _selectedBusinessId
    ) { list, profile, selectedId ->
        val targetId = selectedId ?: profile?.activeBusinessId
        list.firstOrNull { it.id == targetId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val platformSetting: StateFlow<PlatformSetting> = currentUidFlow
        .flatMapLatest { uid ->
            if (uid.isNullOrBlank()) flowOf(PlatformSetting())
            else repository.observePlatformSettings().catch { emit(PlatformSetting()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlatformSetting())

    val announcements: StateFlow<List<PlatformAnnouncement>> = currentUidFlow
        .flatMapLatest { uid ->
            if (uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeAnnouncements().catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val activeBizPair = combine(activeBusiness, currentUidFlow) { biz, uid -> biz to uid }

    val customers: StateFlow<List<Customer>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeCustomers(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val catalog: StateFlow<List<CatalogItem>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeCatalog(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transactions: StateFlow<List<BankTransactionRecord>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeTransactions(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val receipts: StateFlow<List<ReceiptRecord>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeReceipts(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val staffMembers: StateFlow<List<StaffMember>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeStaff(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentStaffRole: StateFlow<StaffRole> = combine(activeBusiness, staffMembers, currentUidFlow) { biz, members, uid ->
        if (biz == null || uid.isNullOrBlank()) StaffRole.RECEIPT_OFFICER
        else if (biz.ownerUid == uid) StaffRole.OWNER
        else members.firstOrNull { it.staffUid == uid }?.staffRole() ?: StaffRole.RECEIPT_OFFICER
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StaffRole.RECEIPT_OFFICER)

    val isCurrentStaffActive: StateFlow<Boolean> = combine(activeBusiness, staffMembers, currentUidFlow) { biz, members, uid ->
        if (biz == null || uid.isNullOrBlank()) true
        else if (biz.ownerUid == uid) true
        else members.firstOrNull { it.staffUid == uid }?.active ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val auditLogs: StateFlow<List<AuditLog>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeAuditLogs(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifications: StateFlow<List<AppNotification>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeNotifications(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptionPayments: StateFlow<List<SubscriptionPayment>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank() || biz.ownerUid != uid) flowOf(emptyList())
            else repository.observeBusinessSubscriptionPayments(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val supportTickets: StateFlow<List<SupportTicket>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeBusinessTickets(uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val offlineDrafts: StateFlow<List<OfflineDraftEntity>> = activeBusiness
        .flatMapLatest { biz ->
            if (biz == null) flowOf(emptyList())
            else localDb.offlineDraftDao().observeDrafts(biz.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyLedgers: StateFlow<List<DailyLedgerSheet>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeDailyLedgers(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ledgerSummary: StateFlow<LedgerCalculationSummary> = combine(_ledgerEntries) { arr ->
        @Suppress("UNCHECKED_CAST")
        LedgerCalculator.summarize(arr[0])
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LedgerCalculator.summarize(emptyList()))

    val showUpdateDialog: StateFlow<Boolean> = combine(
        _authState,
        platformSetting,
        _dismissedUpdateTag
    ) { auth, setting, dismissedTag ->
        if (auth !is AuthUiState.SignedIn) {
            false
        } else {
            val available = setting.isUpdateAvailable(
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME
            )
            if (!available) {
                false
            } else if (setting.forceUpdate) {
                true
            } else {
                val tag = "${setting.latestVersionCode}_${setting.latestVersionName}_${setting.configRevision}"
                dismissedTag != tag
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Super Admin flows (only subscribed when verified Super Admin is signed in)
    val allBusinessesAdmin: StateFlow<List<Business>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                repository.observeAllBusinessesAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPaymentsAdmin: StateFlow<List<SubscriptionPayment>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                repository.observeAllSubscriptionPaymentsAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTicketsAdmin: StateFlow<List<SupportTicket>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                repository.observeAllTicketsAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMailNotificationsAdmin: StateFlow<List<MailNotification>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                repository.observeAllMailNotificationsAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- DYNAMIC SUBSCRIPTION PLANS, QUOTAS, STORAGE, INVOICES & OVERRIDES ---

    val subscriptionPlans: StateFlow<List<SubscriptionPlan>> = currentUidFlow
        .flatMapLatest { uid ->
            if (uid.isNullOrBlank()) flowOf(BillingCalculator.defaultLaunchPlans())
            else subscriptionRepository.observeSubscriptionPlans()
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BillingCalculator.defaultLaunchPlans())

    val planFeatures: StateFlow<List<PlanFeature>> = currentUidFlow
        .flatMapLatest { uid ->
            if (uid.isNullOrBlank()) flowOf(BillingCalculator.defaultLaunchFeatures())
            else subscriptionRepository.observePlanFeatures()
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BillingCalculator.defaultLaunchFeatures())

    val planEntitlements: StateFlow<List<PlanEntitlement>> = currentUidFlow
        .flatMapLatest { uid ->
            if (uid.isNullOrBlank()) flowOf(BillingCalculator.defaultLaunchEntitlements())
            else subscriptionRepository.observePlanEntitlements()
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BillingCalculator.defaultLaunchEntitlements())

    val activeSubscription: StateFlow<BusinessSubscription?> = activeBusiness
        .flatMapLatest { biz ->
            if (biz == null) flowOf(null)
            else subscriptionRepository.observeBusinessSubscription(biz.id).catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeUsageQuota: StateFlow<UsageQuota?> = activeBusiness
        .flatMapLatest { biz ->
            if (biz == null) flowOf(null)
            else subscriptionRepository.observeUsageQuota(biz.id).catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeStorageUsage: StateFlow<StorageUsage?> = activeBusiness
        .flatMapLatest { biz ->
            if (biz == null) flowOf(null)
            else subscriptionRepository.observeStorageUsage(biz.id).catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeCustomEntitlement: StateFlow<CustomBusinessEntitlement?> = activeBusiness
        .flatMapLatest { biz ->
            if (biz == null) flowOf(null)
            else subscriptionRepository.observeCustomBusinessEntitlement(biz.id).catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val businessInvoices: StateFlow<List<SubscriptionInvoice>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank() || biz.ownerUid != uid) flowOf(emptyList())
            else subscriptionRepository.observeBusinessInvoices(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val businessPlanHistory: StateFlow<List<PlanChangeHistory>> = activeBizPair
        .flatMapLatest { (biz, uid) ->
            if (biz == null || uid.isNullOrBlank() || biz.ownerUid != uid) flowOf(emptyList())
            else subscriptionRepository.observePlanChangeHistory(biz.id, uid).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val effectiveEntitlements: StateFlow<EffectiveEntitlements> = combine(
        combine(activeBusiness, activeSubscription, subscriptionPlans, planEntitlements) { biz, sub, plans, ents ->
            arrayOf(biz, sub, plans, ents)
        },
        combine(activeUsageQuota, activeStorageUsage, activeCustomEntitlement, staffMembers, customers) { q, st, cust, staff, custList ->
            arrayOf(q, st, cust, staff.count { it.active }, custList.size)
        }
    ) { left, right ->
        @Suppress("UNCHECKED_CAST")
        BillingCalculator.resolveEffectiveEntitlements(
            business = left[0] as Business?,
            subscription = left[1] as BusinessSubscription?,
            plans = (left[2] as List<SubscriptionPlan>).ifEmpty { BillingCalculator.defaultLaunchPlans() },
            entitlements = (left[3] as List<PlanEntitlement>).ifEmpty { BillingCalculator.defaultLaunchEntitlements() },
            quota = right[0] as UsageQuota?,
            storage = right[1] as StorageUsage?,
            custom = right[2] as CustomBusinessEntitlement?,
            currentStaffCount = right[3] as Int,
            currentCustomerCount = right[4] as Int
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        BillingCalculator.resolveEffectiveEntitlements(null, null, BillingCalculator.defaultLaunchPlans(), BillingCalculator.defaultLaunchEntitlements(), null, null, null)
    )

    val allSubscriptionsAdmin: StateFlow<List<BusinessSubscription>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                subscriptionRepository.observeAllSubscriptionsAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allInvoicesAdmin: StateFlow<List<SubscriptionInvoice>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                subscriptionRepository.observeAllInvoicesAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allQuotasAdmin: StateFlow<List<UsageQuota>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                subscriptionRepository.observeAllUsageQuotasAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allStorageAdmin: StateFlow<List<StorageUsage>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                subscriptionRepository.observeAllStorageUsageAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allEventsAdmin: StateFlow<List<SubscriptionEvent>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                subscriptionRepository.observeAllSubscriptionEventsAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPlanChangesAdmin: StateFlow<List<PlanChangeHistory>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                subscriptionRepository.observeAllPlanChangeHistoryAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCustomEntitlementsAdmin: StateFlow<List<CustomBusinessEntitlement>> = _authState
        .flatMapLatest { state ->
            if (state is AuthUiState.SignedIn && state.isSuperAdmin) {
                subscriptionRepository.observeAllCustomEntitlementsAdmin().catch { emit(emptyList()) }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mrrArrSummary: StateFlow<MrrArrSummary> = combine(allSubscriptionsAdmin, subscriptionPlans) { subs, plans ->
        BillingCalculator.computeMrrAndArr(subs, plans.ifEmpty { BillingCalculator.defaultLaunchPlans() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MrrArrSummary(0L, 0L, 0, 0, 0, 0, emptyMap()))

    // Public QR verification state
    private val _qrVerificationResult = MutableStateFlow<Result<PublicVerification?>?>(null)
    val qrVerificationResult: StateFlow<Result<PublicVerification?>?> = _qrVerificationResult.asStateFlow()

    private val _pendingDeepLinkCode = MutableStateFlow<String?>(null)
    val pendingDeepLinkCode: StateFlow<String?> = _pendingDeepLinkCode.asStateFlow()

    private val legacyTrialAttemptedBizIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var syncJob: Job? = null

    init {
        initializeAuth(getApplication())
        observeConnectivityForAutoSync()
        observeLegacyTrialActivation()
    }

    private fun observeLegacyTrialActivation() {
        viewModelScope.launch {
            combine(activeBusiness, currentUidFlow) { biz, uid -> biz to uid }
                .collect { (biz, uid) ->
                    if (biz != null &&
                        !uid.isNullOrBlank() &&
                        biz.ownerUid == uid &&
                        biz.subscriptionStatus == SubscriptionStatus.AWAITING_SUBSCRIPTION.name &&
                        biz.subscriptionActivatedMillis == 0L &&
                        legacyTrialAttemptedBizIds.add(biz.id)
                    ) {
                        repository.activateLegacyFreeTrialIfEligible(biz)
                    }
                }
        }
    }

    private fun initializeAuth(context: Context) {
        viewModelScope.launch {
            val current = Firebase.auth.currentUser
            if (current != null) {
                applySignedInUser(current)
            } else {
                val autoUser = GoogleAuthHelper.attemptAutoSignIn(context)
                if (autoUser != null) {
                    applySignedInUser(autoUser)
                } else {
                    _authState.value = AuthUiState.SignedOut
                }
            }
        }
    }

    private fun applySignedInUser(user: FirebaseUser) {
        if (currentUidFlow.value != user.uid) {
            _hasLoadedBusinesses.value = false
            _selectedBusinessId.value = null
            clearAiChat()
            _ledgerEntries.value = emptyList()
            _ledgerLoadedId.value = ""
            _qrVerificationResult.value = null
            _pendingDeepLinkCode.value = null
            _dismissedUpdateTag.value = null
        }
        currentUidFlow.value = user.uid
        currentEmailFlow.value = user.email.orEmpty().lowercase()
        _authError.value = null
        val isAdmin = repository.isCurrentUserSuperAdmin()
        _authState.value = AuthUiState.SignedIn(
            user = user,
            isSuperAdmin = isAdmin
        )
        if (isAdmin) {
            viewModelScope.launch {
                subscriptionRepository.ensureDefaultLaunchPlansSeededAdmin()
            }
        }
    }

    fun signInWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            _authError.value = null
            _authState.value = AuthUiState.Loading
            when (val outcome = GoogleAuthHelper.signInInteractive(activityContext)) {
                is GoogleSignInOutcome.Success -> applySignedInUser(outcome.user)
                is GoogleSignInOutcome.Cancelled -> {
                    _authState.value = AuthUiState.SignedOut
                }
                is GoogleSignInOutcome.Error -> {
                    _authError.value = outcome.message
                    _authState.value = AuthUiState.SignedOut
                }
            }
        }
    }

    fun signOut(context: Context) {
        // Clear listener-driving flows and in-memory tenant state first so Firestore listeners unsubscribe cleanly before auth revocation.
        _hasLoadedBusinesses.value = false
        currentUidFlow.value = null
        currentEmailFlow.value = ""
        _selectedBusinessId.value = null
        clearAiChat()
        _ledgerEntries.value = emptyList()
        _ledgerLoadedId.value = ""
        _dismissedUpdateTag.value = null
        _qrVerificationResult.value = null
        _pendingDeepLinkCode.value = null
        _actionState.value = ActionUiState.Idle
        _authState.value = AuthUiState.SignedOut
        viewModelScope.launch {
            withContext(NonCancellable) {
                GoogleAuthHelper.signOut(context.applicationContext)
            }
        }
    }

    fun selectBusiness(businessId: String) {
        if (_selectedBusinessId.value == businessId) return
        _selectedBusinessId.value = businessId
        clearAiChat()
        _ledgerEntries.value = emptyList()
        _ledgerLoadedId.value = ""
        _qrVerificationResult.value = null
        val profile = userProfile.value ?: return
        viewModelScope.launch {
            repository.saveUserProfile(profile.copy(activeBusinessId = businessId))
        }
    }

    fun clearActionState() {
        _actionState.value = ActionUiState.Idle
    }

    fun setDeepLinkVerificationCode(code: String?) {
        _pendingDeepLinkCode.value = code
        if (!code.isNullOrBlank()) {
            lookupPublicVerification(code)
        }
    }

    fun lookupPublicVerification(code: String) {
        viewModelScope.launch {
            _qrVerificationResult.value = repository.lookupPublicVerification(code)
        }
    }

    fun clearVerificationResult() {
        _qrVerificationResult.value = null
        _pendingDeepLinkCode.value = null
    }

    fun completeBusinessOnboarding(
        ownerProfile: UserProfile,
        business: Business,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Creating your isolated business workspace...")
            repository.createBusinessWorkspace(ownerProfile, business)
                .onSuccess { bizId ->
                    _selectedBusinessId.value = bizId
                    _actionState.value = ActionUiState.Success("Business workspace created!")
                    onCreated(bizId)
                }
                .onFailure { err ->
                    _actionState.value = ActionUiState.Error(err.localizedMessage ?: "Failed to create business workspace.")
                }
        }
    }

    fun updateBusinessSettings(business: Business) {
        val role = currentStaffRole.value
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Saving business settings...")
            repository.updateBusinessSettings(business, role)
                .onSuccess { _actionState.value = ActionUiState.Success("Settings saved successfully.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to save settings.") }
        }
    }

    fun acceptInvite(invite: StaffInvite) {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Joining ${invite.businessName}...")
            repository.acceptStaffInvite(invite)
                .onSuccess {
                    _selectedBusinessId.value = invite.businessId
                    _actionState.value = ActionUiState.Success("Joined ${invite.businessName} as ${invite.role}")
                }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to accept invite.") }
        }
    }

    fun upsertCustomer(customer: Customer) {
        val biz = activeBusiness.value ?: return
        val maxCustomers = effectiveEntitlements.value.maxCustomers
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Saving customer...")
            repository.upsertCustomer(biz, customer, maxCustomers = maxCustomers)
                .onSuccess { _actionState.value = ActionUiState.Success("Customer saved.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to save customer.") }
        }
    }

    fun saveCatalogItem(item: CatalogItem) {
        val biz = activeBusiness.value ?: return
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Saving catalog item...")
            repository.saveCatalogItem(biz, item)
                .onSuccess { _actionState.value = ActionUiState.Success("Catalog item saved.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to save item.") }
        }
    }

    fun updateVerificationStatus(receipt: ReceiptRecord, newStatus: VerificationStatus, comment: String) {
        val biz = activeBusiness.value ?: return
        val role = currentStaffRole.value
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Updating verification status...")
            repository.updateReceiptVerificationStatus(biz, receipt, newStatus, comment, role)
                .onSuccess { _actionState.value = ActionUiState.Success("Receipt ${receipt.receiptNumber} marked ${newStatus.label}.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to update verification status.") }
        }
    }

    fun inviteStaff(email: String, name: String, role: StaffRole) {
        val biz = activeBusiness.value ?: return
        val maxStaff = effectiveEntitlements.value.maxStaffAccounts
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Sending staff invitation...")
            repository.inviteStaff(biz, email, name, role, maxStaffAccounts = maxStaff)
                .onSuccess { _actionState.value = ActionUiState.Success("Invitation sent to $email.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to invite staff.") }
        }
    }

    fun toggleStaffActive(member: StaffMember, active: Boolean) {
        val biz = activeBusiness.value ?: return
        viewModelScope.launch {
            repository.toggleStaffActive(biz, member, active)
                .onSuccess { _actionState.value = ActionUiState.Success("${member.fullName} updated.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to update staff status.") }
        }
    }

    fun markNotificationRead(notificationId: String) {
        val biz = activeBusiness.value ?: return
        viewModelScope.launch {
            repository.markNotificationRead(biz.id, notificationId)
        }
    }

    private suspend fun submitComplaintAndNotifyAdmin(
        business: Business,
        subject: String,
        category: String,
        description: String,
        source: String
    ): Result<Triple<String, Boolean, String>> {
        val setting = platformSetting.value
        val targetMail = setting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }
        val createRes = repository.createSupportTicket(
            business = business,
            subject = subject,
            category = category,
            description = description,
            setting = setting,
            source = source
        )
        if (createRes.isFailure) {
            return Result.failure(createRes.exceptionOrNull() ?: Exception("Failed to submit complaint."))
        }
        val ticketId = createRes.getOrThrow()
        val bizName = business.tradingName.ifBlank { business.registeredName }
        val cleanSubject = subject.trim().ifBlank { "Customer Complaint" }.take(200)
        val cleanCategory = category.trim().ifBlank { "TECHNICAL" }.take(64)
        val cleanDesc = description.trim().take(4000)
        val user = Firebase.auth.currentUser
        val reporterName = user?.displayName?.takeIf { it.isNotBlank() } ?: business.ownerName
        val reporterEmail = user?.email?.takeIf { it.isNotBlank() } ?: business.ownerEmail
        val emailSubject = "[ReceiptsNow Complaint][$cleanCategory] $cleanSubject - $bizName".take(240)
        val emailBody = buildString {
            appendLine("A customer has reported a complaint / support difficulty in ReceiptsNow.")
            appendLine()
            appendLine("Ticket ID: $ticketId")
            appendLine("Source: $source")
            appendLine("Category: $cleanCategory")
            appendLine("Business: $bizName (${business.id})")
            appendLine("Reported By: $reporterName ($reporterEmail)")
            appendLine("Subject: $cleanSubject")
            appendLine()
            appendLine("Details:")
            appendLine(cleanDesc)
            appendLine()
            appendLine("Open Super Admin -> Tickets tab or reply directly to $reporterEmail.")
        }

        val webhookUrl = setting.emailNotificationWebhookUrl.trim()
        var emailDelivered = false
        var note = "Complaint #$ticketId logged & queued for $targetMail."
        if (webhookUrl.isNotBlank()) {
            val payload = JSONObject().apply {
                put("eventType", "SUPPORT_COMPLAINT")
                put("ticketId", ticketId)
                put("paymentId", ticketId)
                put("toEmail", targetMail)
                put("subject", emailSubject)
                put("body", emailBody)
                put("businessId", business.id)
                put("businessName", bizName)
                put("ownerName", reporterName)
                put("ownerEmail", reporterEmail)
                put("ownerPhone", business.ownerPhone.ifBlank { business.phone })
                put("planCode", cleanCategory)
                put("amountNgn", 0)
                put("txRef", ticketId)
                put("senderName", source)
            }
            val relayRes = postEmailNotificationWebhook(webhookUrl, payload)
            if (relayRes.isSuccess) {
                emailDelivered = true
                note = "Complaint #$ticketId submitted and Super Admin notified via email ($targetMail)."
                repository.updateTicketRelayOutcome(ticketId, delivered = true, errorMessage = "")
            } else {
                val errMsg = relayRes.exceptionOrNull()?.localizedMessage ?: "Relay error"
                Log.w("AppViewModel", "Complaint email relay failed: $errMsg")
                note = "Complaint #$ticketId saved to Super Admin portal. Email relay retry needed ($errMsg)."
                repository.updateTicketRelayOutcome(ticketId, delivered = false, errorMessage = errMsg)
            }
        }
        return Result.success(Triple(ticketId, emailDelivered, note))
    }

    fun createSupportTicket(
        subject: String,
        category: String,
        description: String,
        source: String = "HELP_DESK",
        onCompleted: ((String, Boolean, String) -> Unit)? = null
    ) {
        val biz = activeBusiness.value ?: return
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Submitting complaint & notifying Super Admin...")
            submitComplaintAndNotifyAdmin(
                business = biz,
                subject = subject,
                category = category,
                description = description,
                source = source
            ).onSuccess { (ticketId, delivered, note) ->
                _actionState.value = ActionUiState.Success(note)
                onCompleted?.invoke(ticketId, delivered, note)
            }.onFailure { err ->
                _actionState.value = ActionUiState.Error(err.localizedMessage ?: "Failed to submit complaint.")
            }
        }
    }

    // --- PAYSTACK SUBSCRIPTION CHECKOUT & AUTO-ACTIVATION ---

    private suspend fun postEmailNotificationWebhook(
        webhookUrl: String,
        payload: JSONObject
    ): Result<Unit> = withContext(Dispatchers.IO) {
        EmailRelayClient.postJson(httpClient, webhookUrl, payload).map { Unit }
    }

    fun verifyAndActivatePaystackSubscription(
        selectedPlan: SubscriptionPlan,
        reference: String,
        checkoutConfirmedInWebView: Boolean,
        detectedChannel: String = "",
        paymentUrl: String = "",
        prorationQuote: ProrationQuote? = null,
        onError: ((String) -> Unit)? = null,
        onDone: (PaymentSubmissionOutcome) -> Unit
    ) {
        val biz = activeBusiness.value ?: run {
            onError?.invoke("No active business workspace selected.")
            return
        }
        val setting = platformSetting.value
        val cleanRef = reference.trim()
        if (cleanRef.length < 6) {
            val msg = "Please enter a valid Paystack reference (at least 6 characters)."
            _actionState.value = ActionUiState.Error(msg)
            onError?.invoke(msg)
            return
        }
        val expectedMinor = prorationQuote?.netPayableMinor ?: selectedPlan.priceMinor
        val resolvedUrl = paymentUrl.ifBlank { PaystackPaymentService.resolvePlanUrl(selectedPlan, setting) }
        val amountFormatted = BillingCalculator.formatMinorCurrency(expectedMinor, selectedPlan.currency)

        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Verifying Paystack payment & activating ${selectedPlan.name}...")
            val secretKey = BuildConfig.PAYSTACK_SECRET_KEY.trim()
                .takeUnless { it.equals("PAYSTACK_SECRET_KEY_PLACEHOLDER", ignoreCase = true) }
                .orEmpty()
            val verifyEndpoint = setting.paystackVerifyEndpoint.trim()
            val hasApiVerifier = secretKey.isNotBlank() || verifyEndpoint.isNotBlank()

            val verification: PaystackVerificationResult = if (hasApiVerifier) {
                val apiRes = PaystackPaymentService.verifyTransaction(
                    reference = cleanRef,
                    expectedAmountMinor = expectedMinor,
                    verifyEndpointUrl = verifyEndpoint,
                    secretKey = secretKey,
                    client = httpClient
                )
                if (apiRes.verified) {
                    apiRes
                } else if (apiRes.rawStatus.isNotBlank() && !checkoutConfirmedInWebView) {
                    val errMsg = apiRes.errorMessage.ifBlank { "Paystack transaction could not be verified." }
                    _actionState.value = ActionUiState.Error(errMsg)
                    onError?.invoke(errMsg)
                    return@launch
                } else if (checkoutConfirmedInWebView) {
                    // Fallback to trusted in-app Paystack checkout confirmation when API key is test/mismatched or network blip
                    PaystackVerificationResult(
                        verified = true,
                        reference = cleanRef,
                        amountMinor = expectedMinor,
                        currency = selectedPlan.currency.ifBlank { "NGN" },
                        channel = detectedChannel.ifBlank { "paystack_checkout" },
                        customerEmail = biz.ownerEmail,
                        gatewayResponse = "Confirmed via Paystack Checkout",
                        verificationSource = PaystackPaymentService.SOURCE_CHECKOUT_CONFIRMED,
                        paidAtMillis = System.currentTimeMillis()
                    )
                } else {
                    apiRes
                }
            } else if (checkoutConfirmedInWebView) {
                PaystackVerificationResult(
                    verified = true,
                    reference = cleanRef,
                    amountMinor = expectedMinor,
                    currency = selectedPlan.currency.ifBlank { "NGN" },
                    channel = detectedChannel.ifBlank { "paystack_checkout" },
                    customerEmail = biz.ownerEmail,
                    gatewayResponse = "Confirmed via Paystack Checkout",
                    verificationSource = PaystackPaymentService.SOURCE_CHECKOUT_CONFIRMED,
                    paidAtMillis = System.currentTimeMillis()
                )
            } else {
                PaystackVerificationResult(
                    verified = false,
                    reference = cleanRef,
                    amountMinor = expectedMinor,
                    currency = selectedPlan.currency.ifBlank { "NGN" },
                    channel = detectedChannel.ifBlank { "paystack_external" },
                    customerEmail = biz.ownerEmail,
                    gatewayResponse = "Manual reference submitted for Super Admin confirmation",
                    verificationSource = PaystackPaymentService.SOURCE_MANUAL_UNVERIFIED
                )
            }

            val entitlement = planEntitlements.value.firstOrNull { it.planId == selectedPlan.planId }
                ?: BillingCalculator.defaultLaunchEntitlements().firstOrNull { it.planId == selectedPlan.planId }
                ?: PlanEntitlement(planId = selectedPlan.planId)

            repository.recordAndActivatePaystackPayment(
                business = biz,
                setting = setting,
                plan = selectedPlan,
                entitlement = entitlement,
                reference = cleanRef,
                verification = verification,
                paymentUrl = resolvedUrl,
                prorationCreditMinor = prorationQuote?.prorationCreditMinor ?: 0L
            ).onSuccess { record ->
                val targetMail = setting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }
                val bizName = biz.tradingName.ifBlank { biz.registeredName }
                val channelSummary = listOfNotNull(
                    verification.channel.takeIf { it.isNotBlank() }?.uppercase(),
                    verification.cardSummary.takeIf { it.isNotBlank() },
                    verification.bank.takeIf { it.isNotBlank() }
                ).joinToString(" • ").ifBlank { "Paystack" }

                val subject = if (record.activated) {
                    "Paystack Subscription Activated (${selectedPlan.name}) - $bizName"
                } else {
                    "Paystack Payment Reference Submitted (${selectedPlan.name}) - $bizName"
                }
                val body = buildString {
                    appendLine(
                        if (record.activated) {
                            "A Paystack subscription payment has been confirmed and the plan is now ACTIVE."
                        } else {
                            "A business owner submitted a Paystack reference from external checkout for confirmation."
                        }
                    )
                    appendLine()
                    appendLine("Business: $bizName (${biz.id})")
                    appendLine("Owner: ${biz.ownerName} (${biz.ownerEmail})")
                    appendLine("Plan: ${selectedPlan.name} (${selectedPlan.planCode})")
                    appendLine("Amount: $amountFormatted")
                    appendLine("Paystack Ref: $cleanRef")
                    appendLine("Channel: $channelSummary")
                    appendLine("Source: ${verification.verificationSource}")
                    if (record.invoiceId.isNotBlank()) appendLine("Invoice ID: ${record.invoiceId}")
                }

                var autoEmailDelivered = false
                val webhookUrl = setting.emailNotificationWebhookUrl.trim()
                if (webhookUrl.isNotBlank()) {
                    val payload = JSONObject().apply {
                        put(
                            "eventType",
                            if (record.activated) "PAYSTACK_SUBSCRIPTION_CONFIRMED" else "PAYSTACK_SUBSCRIPTION_PENDING"
                        )
                        put("paymentId", record.paymentId)
                        put("toEmail", targetMail)
                        put("subject", subject)
                        put("body", body)
                        put("businessId", biz.id)
                        put("businessName", bizName)
                        put("ownerName", biz.ownerName)
                        put("ownerEmail", biz.ownerEmail)
                        put("ownerPhone", biz.ownerPhone.ifBlank { biz.phone })
                        put("planCode", selectedPlan.planCode)
                        put("amountNgn", expectedMinor / 100.0)
                        put("currency", selectedPlan.currency)
                        put("txRef", cleanRef)
                        put("paystackReference", cleanRef)
                        put("paystackChannel", channelSummary)
                        put("verificationSource", verification.verificationSource)
                        put("senderName", biz.ownerName)
                    }
                    autoEmailDelivered = postEmailNotificationWebhook(webhookUrl, payload).isSuccess
                }

                val note = if (record.activated) {
                    "${selectedPlan.name} subscription activated via Paystack ($cleanRef)!"
                } else {
                    "Paystack reference $cleanRef saved for confirmation in Super Admin console."
                }
                _actionState.value = ActionUiState.Success(note)
                onDone(
                    PaymentSubmissionOutcome(
                        paymentId = record.paymentId,
                        targetEmail = targetMail,
                        subject = subject,
                        body = body,
                        autoEmailDelivered = autoEmailDelivered,
                        deliveryNote = note,
                        isAutoActivated = record.activated,
                        planName = selectedPlan.name,
                        paystackReference = cleanRef,
                        amountFormatted = amountFormatted,
                        invoiceId = record.invoiceId,
                        channelSummary = channelSummary,
                        verificationSource = verification.verificationSource
                    )
                )
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to activate Paystack subscription."
                _actionState.value = ActionUiState.Error(msg)
                onError?.invoke(msg)
            }
        }
    }

    fun testEmailRelayAdmin(settingOverride: PlatformSetting? = null) {
        val setting = settingOverride ?: platformSetting.value
        val rawUrl = setting.emailNotificationWebhookUrl.trim()
        val targetMail = setting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }
        if (rawUrl.isBlank()) {
            val msg = "Enter an Email Relay Webhook URL (Google Apps Script /exec or Cloud Function URL) first."
            _emailRelayTestStatus.value = EmailRelayTestStatus(isTesting = false, isSuccess = false, message = msg)
            _actionState.value = ActionUiState.Error(msg)
            return
        }
        val validatedUrl = EmailRelayClient.normalizeAndValidateUrl(rawUrl).getOrElse { err ->
            val msg = err.localizedMessage ?: "Invalid Email Relay URL."
            _emailRelayTestStatus.value = EmailRelayTestStatus(isTesting = false, isSuccess = false, message = msg)
            _actionState.value = ActionUiState.Error(msg)
            return
        }
        viewModelScope.launch {
            _emailRelayTestStatus.value = EmailRelayTestStatus(
                isTesting = true,
                isSuccess = null,
                message = "Testing relay at ${validatedUrl.host}..."
            )
            _actionState.value = ActionUiState.Busy("Sending test email to $targetMail...")
            val payload = JSONObject().apply {
                put("paymentId", "test_${System.currentTimeMillis()}")
                put("toEmail", targetMail)
                put("subject", "ReceiptsNow Admin Email Relay Test")
                put("body", "This is a live test notification from ReceiptsNow Admin settings confirming email alerts to $targetMail are active.")
                put("businessName", "ReceiptsNow Test Workspace")
                put("ownerName", "Super Admin")
                put("ownerEmail", targetMail)
                put("planCode", "MONTHLY")
                put("amountNgn", setting.monthlyPriceNgn)
                put("txRef", "TEST_RELAY_${System.currentTimeMillis()}")
            }
            postEmailNotificationWebhook(validatedUrl.toString(), payload)
                .onSuccess {
                    val okMsg = "Test email delivered via relay to $targetMail!"
                    _emailRelayTestStatus.value = EmailRelayTestStatus(isTesting = false, isSuccess = true, message = okMsg)
                    _actionState.value = ActionUiState.Success(okMsg)
                }
                .onFailure { err ->
                    val errDetail = err.localizedMessage ?: "Unknown relay error"
                    _emailRelayTestStatus.value = EmailRelayTestStatus(isTesting = false, isSuccess = false, message = errDetail)
                    _actionState.value = ActionUiState.Error(errDetail)
                }
        }
    }

    fun clearEmailRelayTestStatus() {
        _emailRelayTestStatus.value = null
    }

    fun resendPaymentNotificationAdmin(payment: SubscriptionPayment) {
        val setting = platformSetting.value
        val webhookUrl = setting.emailNotificationWebhookUrl.trim()
        val targetMail = setting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }
        if (webhookUrl.isBlank()) {
            _actionState.value = ActionUiState.Error("Configure Email Relay Webhook URL in Settings first to resend automatically.")
            return
        }
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Sending email notification to $targetMail...")
            val ref = payment.effectiveReference()
            val subject = if (payment.isPaystack()) {
                "ReceiptsNow Paystack Payment (${payment.paymentStatus}) - ${payment.businessName.ifBlank { payment.businessId }}"
            } else {
                "ReceiptsNow ${payment.planCode} Payment - ${payment.businessName.ifBlank { payment.businessId }}"
            }
            val body = buildString {
                appendLine("Business Name: ${payment.businessName}")
                appendLine("Owner: ${payment.ownerName} (${payment.ownerEmail})")
                appendLine("Method: ${payment.paymentMethod}")
                appendLine("Plan: ${payment.planName.ifBlank { payment.planCode }} • NGN ${"%,.2f".format(payment.amountNgn)}")
                appendLine("Reference: $ref")
                if (payment.paystackChannel.isNotBlank()) appendLine("Channel: ${payment.paystackChannel}")
                if (payment.paystackCardSummary.isNotBlank()) appendLine("Card: ${payment.paystackCardSummary}")
                if (payment.paystackBank.isNotBlank()) appendLine("Bank: ${payment.paystackBank}")
                if (payment.verificationSource.isNotBlank()) appendLine("Verification: ${payment.verificationSource}")
                appendLine("Status: ${payment.paymentStatus}")
            }
            val payload = JSONObject().apply {
                put("paymentId", payment.id)
                put("toEmail", targetMail)
                put("subject", subject)
                put("body", body)
                put("businessId", payment.businessId)
                put("businessName", payment.businessName)
                put("ownerName", payment.ownerName)
                put("ownerEmail", payment.ownerEmail)
                put("ownerPhone", payment.ownerPhone)
                put("planCode", payment.planCode)
                put("amountNgn", payment.amountNgn)
                put("txRef", ref)
                put("paystackReference", payment.paystackReference)
                put("paystackChannel", payment.paystackChannel)
                put("senderName", payment.senderName)
                put("senderBank", payment.senderBank)
                put("transferNote", payment.transferNote)
                put("receiptFileName", payment.receiptFileName.ifBlank { "receipt_${payment.id}.jpg" })
                put("receiptMimeType", payment.receiptMimeType.ifBlank { "image/jpeg" })
                put("receiptImageBase64", payment.receiptImageBase64)
            }
            postEmailNotificationWebhook(webhookUrl, payload)
                .onSuccess {
                    repository.updateMailDeliveryStatusAdmin(payment.id, "SENT", "")
                    _actionState.value = ActionUiState.Success("Notification email sent to $targetMail!")
                }
                .onFailure { err ->
                    val msg = err.localizedMessage ?: "Relay error"
                    repository.updateMailDeliveryStatusAdmin(payment.id, "FAILED", msg)
                    _actionState.value = ActionUiState.Error("Failed to send email: $msg")
                }
        }
    }

    // --- OFFLINE DRAFTS & AUTO-SYNC ---

    private fun observeConnectivityForAutoSync() {
        viewModelScope.launch {
            connectivityObserver.observeOnline().collect { online ->
                if (online) {
                    syncPendingOfflineDrafts(silent = true)
                }
            }
        }
    }

    fun syncPendingOfflineDrafts(silent: Boolean = false) {
        val biz = activeBusiness.value ?: return
        if (!isCurrentStaffActive.value || !currentStaffRole.value.canGenerateReceipts()) {
            if (!silent) {
                _actionState.value = ActionUiState.Error("Your current staff access does not permit issuing receipts.")
            }
            return
        }
        if (!biz.isSubscriptionActive(gracePeriodEndMillis = effectiveEntitlements.value.gracePeriodEndMillis)) {
            if (!silent) {
                _actionState.value = ActionUiState.Error("Active subscription required before syncing pending offline receipts.")
            }
            return
        }
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            val pending = localDb.offlineDraftDao().getPendingDrafts(biz.id)
            if (pending.isEmpty()) {
                if (!silent) _actionState.value = ActionUiState.Success("All offline drafts are already synced.")
                return@launch
            }
            if (!silent) _actionState.value = ActionUiState.Busy("Syncing ${pending.size} offline draft(s)...")
            var syncedCount = 0
            for (draft in pending) {
                val tx = BankTransactionRecord(
                    documentType = "OFFLINE_DRAFT",
                    documentName = draft.documentName,
                    amount = draft.amount,
                    currency = draft.currency,
                    transactionDate = draft.transactionDate,
                    transactionTime = draft.transactionTime,
                    transactionReference = draft.transactionReference,
                    sessionId = draft.sessionId,
                    paymentNarration = draft.paymentNarration,
                    transferChannel = draft.transferChannel,
                    senderName = draft.senderName,
                    senderAccountName = draft.senderName,
                    senderBank = draft.senderBank,
                    beneficiaryBank = draft.beneficiaryBank,
                    humanReviewed = true
                )
                val cust = Customer(
                    fullName = draft.customerName,
                    phone = draft.customerPhone,
                    email = draft.customerEmail,
                    address = draft.customerAddress
                )
                val tpl = runCatching { ReceiptTemplateType.valueOf(draft.templateType) }
                    .getOrDefault(ReceiptTemplateType.STANDARD)
                val res = repository.issueReceiptFromTransaction(
                    business = biz,
                    transaction = tx,
                    customer = cust,
                    paymentPurpose = draft.paymentPurpose,
                    invoiceNumber = draft.invoiceNumber,
                    orderNumber = draft.orderNumber,
                    notes = draft.notes,
                    amountPaid = draft.amount,
                    discountAmount = 0.0,
                    amountInWords = AmountInWords.toNairaWords(draft.amount, draft.currency),
                    templateType = tpl,
                    actorRole = currentStaffRole.value,
                    effectiveEntitlements = effectiveEntitlements.value,
                    idempotencyKey = "draft_${draft.draftId}"
                )
                if (res.isSuccess) {
                    localDb.offlineDraftDao().upsertDraft(draft.copy(syncStatus = "SYNCED", updatedAtMillis = System.currentTimeMillis()))
                    syncedCount++
                } else {
                    Log.w("AppViewModel", "Offline draft sync skipped for ${draft.draftId}: ${res.exceptionOrNull()?.message}")
                }
            }
            if (!silent) {
                _actionState.value = if (syncedCount > 0) {
                    ActionUiState.Success("Synced $syncedCount offline draft(s) to cloud.")
                } else {
                    ActionUiState.Error("Unable to sync offline drafts right now.")
                }
            }
        }
    }

    fun deleteOfflineDraft(draftId: String) {
        viewModelScope.launch {
            localDb.offlineDraftDao().deleteDraft(draftId)
        }
    }

    // --- SUPER ADMIN ACTIONS ---

    fun savePlatformSettingsAdmin(setting: PlatformSetting) {
        val cleanedUrl = setting.emailNotificationWebhookUrl.trim()
        val normalizedSetting = if (cleanedUrl.isNotBlank()) {
            val validated = EmailRelayClient.normalizeAndValidateUrl(cleanedUrl).getOrElse { err ->
                val msg = err.localizedMessage ?: "Invalid Email Relay URL."
                _emailRelayTestStatus.value = EmailRelayTestStatus(isTesting = false, isSuccess = false, message = msg)
                _actionState.value = ActionUiState.Error(msg)
                return
            }
            setting.copy(emailNotificationWebhookUrl = validated.toString())
        } else {
            setting.copy(emailNotificationWebhookUrl = "")
        }
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Updating ReceiptsNow platform settings...")
            repository.savePlatformSettingsAdmin(normalizedSetting)
                .onSuccess { _actionState.value = ActionUiState.Success("Platform settings saved.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to save settings.") }
        }
    }

    fun setBusinessAccountStatusAdmin(businessId: String, status: String) {
        viewModelScope.launch {
            repository.setBusinessAccountStatusAdmin(businessId, status)
                .onSuccess { _actionState.value = ActionUiState.Success("Business account marked $status.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to update account status.") }
        }
    }

    fun publishAnnouncementAdmin(title: String, message: String, category: String) {
        viewModelScope.launch {
            repository.publishAnnouncementAdmin(title, message, category)
                .onSuccess { _actionState.value = ActionUiState.Success("Announcement published.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to publish announcement.") }
        }
    }

    fun verifySubscriptionPaymentAdmin(payment: SubscriptionPayment, verifyViaPaystackApi: Boolean = true) {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Verifying payment & activating subscription...")
            val setting = platformSetting.value
            val secretKey = BuildConfig.PAYSTACK_SECRET_KEY.trim()
                .takeUnless { it.equals("PAYSTACK_SECRET_KEY_PLACEHOLDER", ignoreCase = true) }
                .orEmpty()
            val verifyEndpoint = setting.paystackVerifyEndpoint.trim()
            var apiResult: PaystackVerificationResult? = null
            if (verifyViaPaystackApi && payment.isPaystack() && (secretKey.isNotBlank() || verifyEndpoint.isNotBlank())) {
                val res = PaystackPaymentService.verifyTransaction(
                    reference = payment.effectiveReference(),
                    expectedAmountMinor = payment.effectiveAmountMinor(),
                    verifyEndpointUrl = verifyEndpoint,
                    secretKey = secretKey,
                    client = httpClient
                )
                if (res.verified) {
                    apiResult = res
                } else if (res.rawStatus.isNotBlank()) {
                    _actionState.value = ActionUiState.Error(
                        res.errorMessage.ifBlank { "Paystack verification returned ${res.rawStatus}." }
                    )
                    return@launch
                }
            }
            repository.verifySubscriptionPaymentAdmin(
                payment = payment,
                plans = subscriptionPlans.value,
                entitlements = planEntitlements.value,
                setting = setting,
                paystackVerification = apiResult
            )
                .onSuccess {
                    val suffix = if (apiResult != null) " (Paystack API Confirmed)" else ""
                    _actionState.value = ActionUiState.Success(
                        "Payment verified$suffix! ${payment.businessName} (${payment.planName.ifBlank { payment.planCode }}) is now ACTIVE."
                    )
                }
                .onFailure {
                    _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to verify payment.")
                }
        }
    }

    fun markPaymentStatusAdmin(payment: SubscriptionPayment, targetStatus: String, reason: String) {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Updating payment status to $targetStatus...")
            repository.markPaymentStatusAdmin(payment, targetStatus, reason)
                .onSuccess { _actionState.value = ActionUiState.Success("Payment marked $targetStatus.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to update payment status.") }
        }
    }

    fun rejectSubscriptionPaymentAdmin(payment: SubscriptionPayment, reason: String) {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Rejecting payment submission...")
            repository.rejectSubscriptionPaymentAdmin(payment, reason)
                .onSuccess { _actionState.value = ActionUiState.Success("Payment marked REJECTED.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to reject payment.") }
        }
    }

    fun savePlanAdmin(
        plan: SubscriptionPlan,
        entitlement: PlanEntitlement,
        features: List<PlanFeature>
    ) {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Saving subscription plan & entitlements...")
            subscriptionRepository.savePlanWithEntitlementAdmin(plan, entitlement, features)
                .onSuccess { planId ->
                    _actionState.value = ActionUiState.Success("Plan '${plan.name}' ($planId) saved & published live.")
                }
                .onFailure {
                    _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to save plan.")
                }
        }
    }

    fun setPlanStatusAdmin(plan: SubscriptionPlan, status: PlanStatus, visible: Boolean) {
        viewModelScope.launch {
            subscriptionRepository.setPlanStatusAdmin(plan, status, visible)
                .onSuccess { _actionState.value = ActionUiState.Success("${plan.name} updated to ${status.label}.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to update plan status.") }
        }
    }

    fun saveCustomEntitlementAdmin(business: Business, override: CustomBusinessEntitlement) {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Applying custom business allowances...")
            subscriptionRepository.saveCustomBusinessEntitlementAdmin(business, override)
                .onSuccess {
                    _actionState.value = ActionUiState.Success(
                        "Custom allowances applied for ${business.tradingName.ifBlank { business.registeredName }}."
                    )
                }
                .onFailure {
                    _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to save override.")
                }
        }
    }

    fun suspendOrRestoreSubscriptionAdmin(business: Business, suspend: Boolean, reason: String) {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy(if (suspend) "Suspending subscription..." else "Restoring subscription...")
            subscriptionRepository.suspendOrRestoreSubscriptionAdmin(business, suspend, reason)
                .onSuccess {
                    _actionState.value = ActionUiState.Success(
                        if (suspend) "Subscription suspended (existing data preserved)." else "Subscription restored."
                    )
                }
                .onFailure {
                    _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Action failed.")
                }
        }
    }

    fun processCycleExpiriesAdmin() {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Checking expired cycles & grace periods...")
            subscriptionRepository.processCycleExpiriesAdmin(
                businesses = allBusinessesAdmin.value,
                subscriptions = allSubscriptionsAdmin.value,
                gracePeriodDays = platformSetting.value.gracePeriodDays
            )
                .onSuccess { count ->
                    _actionState.value = ActionUiState.Success("Cycle check finished ($count workspace(s) transitioned).")
                }
                .onFailure {
                    _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed checking expiries.")
                }
        }
    }

    fun updateAutoRenewOrCancellation(
        autoRenew: Boolean,
        cancelAtPeriodEnd: Boolean,
        cancelImmediately: Boolean = false
    ) {
        val biz = activeBusiness.value ?: return
        val sub = activeSubscription.value
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Updating subscription lifecycle...")
            subscriptionRepository.updateAutoRenewOrCancellation(
                business = biz,
                subscription = sub,
                autoRenew = autoRenew,
                cancelAtPeriodEnd = cancelAtPeriodEnd,
                cancelImmediately = cancelImmediately
            )
                .onSuccess {
                    val msg = when {
                        cancelImmediately -> "Subscription cancelled immediately. Historical receipts remain safe."
                        cancelAtPeriodEnd -> "Scheduled cancellation at period end."
                        else -> "Subscription auto-renewal settings updated."
                    }
                    _actionState.value = ActionUiState.Success(msg)
                }
                .onFailure {
                    _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Unable to update subscription.")
                }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themePreferences.setThemeMode(mode)
        }
    }

    fun toggleLightDarkTheme(currentlyDark: Boolean) {
        setThemeMode(if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK)
    }

    fun replySupportTicketAdmin(ticketId: String, status: String, reply: String) {
        viewModelScope.launch {
            repository.replySupportTicketAdmin(ticketId, status, reply)
                .onSuccess { _actionState.value = ActionUiState.Success("Ticket updated.") }
                .onFailure { _actionState.value = ActionUiState.Error(it.localizedMessage ?: "Failed to update ticket.") }
        }
    }

    fun resendTicketNotificationAdmin(ticket: SupportTicket) {
        val setting = platformSetting.value
        val webhookUrl = setting.emailNotificationWebhookUrl.trim()
        val targetMail = setting.notificationEmail.ifBlank { "info.1803consult@gmail.com" }
        if (webhookUrl.isBlank()) {
            _actionState.value = ActionUiState.Error("Configure Email Relay Webhook URL in Settings first to resend complaint alerts.")
            return
        }
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Sending complaint email to $targetMail...")
            val bizLabel = ticket.businessName.ifBlank { ticket.businessId }
            val subject = "[ReceiptsNow Complaint][${ticket.category.ifBlank { "TECHNICAL" }}] ${ticket.subject} - $bizLabel".take(240)
            val body = buildString {
                appendLine("Customer Complaint Notification (Ticket ${ticket.id})")
                appendLine()
                appendLine("Source: ${ticket.source.ifBlank { "HELP_DESK" }}")
                appendLine("Category: ${ticket.category.ifBlank { "TECHNICAL" }}")
                appendLine("Business: $bizLabel (${ticket.businessId})")
                appendLine("Reporter: ${ticket.ownerName} (${ticket.ownerEmail})")
                appendLine("Status: ${ticket.status}")
                appendLine()
                appendLine("Details:")
                appendLine(ticket.description)
            }
            val payload = JSONObject().apply {
                put("eventType", "SUPPORT_COMPLAINT")
                put("ticketId", ticket.id)
                put("paymentId", ticket.id)
                put("toEmail", targetMail)
                put("subject", subject)
                put("body", body)
                put("businessId", ticket.businessId)
                put("businessName", bizLabel)
                put("ownerName", ticket.ownerName)
                put("ownerEmail", ticket.ownerEmail)
                put("planCode", ticket.category.ifBlank { "TECHNICAL" })
                put("txRef", ticket.id)
                put("senderName", ticket.source.ifBlank { "HELP_DESK" })
            }
            postEmailNotificationWebhook(webhookUrl, payload)
                .onSuccess {
                    repository.updateTicketMailDeliveryStatusAdmin(ticket.id, "SENT", "")
                    _actionState.value = ActionUiState.Success("Complaint email sent to $targetMail!")
                }
                .onFailure { err ->
                    val msg = err.localizedMessage ?: "Relay error"
                    repository.updateTicketMailDeliveryStatusAdmin(ticket.id, "FAILED", msg)
                    _actionState.value = ActionUiState.Error("Failed to send complaint email: $msg")
                }
        }
    }

    // --- AI ASSISTANT CHATBOT (FAST FUNCTIONS & COMPLAINT RELAY) ---

    fun clearAiChat() {
        _aiChatMessages.value = emptyList()
        lastAiAttachmentBitmap = null
        lastAiAttachmentName = ""
        lastAiAttachmentSize = 0L
        lastAiAttachmentIsPdf = false
    }

    fun submitQuickComplaintFromChatbot(subject: String, category: String, description: String) {
        val biz = activeBusiness.value ?: return
        val cleanSubject = subject.trim().ifBlank { "App Difficulty Complaint" }
        val cleanDesc = description.trim()
        if (cleanDesc.isBlank()) {
            _actionState.value = ActionUiState.Error("Please describe the difficulty you are experiencing.")
            return
        }
        val userSummary = "Report complaint ($category): $cleanSubject — $cleanDesc"
        _aiChatMessages.update { it + AiChatMessage(isUser = true, text = userSummary) }
        viewModelScope.launch {
            _aiChatBusy.value = true
            submitComplaintAndNotifyAdmin(
                business = biz,
                subject = cleanSubject,
                category = category,
                description = cleanDesc,
                source = "AI_CHATBOT"
            ).onSuccess { (ticketId, delivered, note) ->
                _aiChatMessages.update {
                    it + AiChatMessage(
                        isUser = false,
                        text = note,
                        reportedTicketId = ticketId,
                        ticketEmailDelivered = delivered
                    )
                }
                _actionState.value = ActionUiState.Success(note)
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to submit complaint."
                _aiChatMessages.update { it + AiChatMessage(isUser = false, text = msg, isError = true) }
                _actionState.value = ActionUiState.Error(msg)
            }
            _aiChatBusy.value = false
        }
    }

    fun sendAiChatMessage(
        userText: String,
        imageUri: Uri? = null,
        receiptFlowViewModel: ReceiptFlowViewModel,
        onNavigateToRoute: (String) -> Unit
    ) {
        val biz = activeBusiness.value ?: return
        val trimmed = userText.trim()
        if (trimmed.isBlank() && imageUri == null) return
        if (_aiChatBusy.value) return

        val priorHistory = _aiChatMessages.value.map { AssistantHistoryTurn(isUser = it.isUser, text = it.text) }

        viewModelScope.launch {
            _aiChatBusy.value = true
            try {
                var attachedBitmap: Bitmap? = null
                var attachedPdfBytes: ByteArray? = null
                var attachedDocName = ""
                var attachedDocSize = 0L
                var isPdfDoc = false

                if (imageUri != null) {
                    val context = getApplication<Application>()
                    val mime = context.contentResolver.getType(imageUri).orEmpty()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.query(imageUri, null, null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                                if (nameIdx >= 0) attachedDocName = c.getString(nameIdx).orEmpty()
                                if (sizeIdx >= 0) attachedDocSize = c.getLong(sizeIdx).coerceAtLeast(0L)
                            }
                        }
                    }
                    isPdfDoc = mime.contains("pdf", ignoreCase = true) ||
                        attachedDocName.endsWith(".pdf", ignoreCase = true)
                    if (attachedDocName.isBlank()) {
                        attachedDocName = if (isPdfDoc) "transfer_proof.pdf" else "transfer_proof.jpg"
                    }
                    val resolvedMime = mime.ifBlank { if (isPdfDoc) "application/pdf" else "image/*" }
                    attachedBitmap = receiptOcrService.decodeDocumentPreview(context, imageUri, resolvedMime)
                    if (isPdfDoc) {
                        attachedPdfBytes = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                            }.getOrNull()
                        }
                        if (attachedDocSize <= 0L && attachedPdfBytes != null) {
                            attachedDocSize = attachedPdfBytes.size.toLong()
                        }
                    }

                    val userBubbleText = trimmed.ifBlank {
                        if (isPdfDoc) "Extract details from this bank PDF receipt and generate an official receipt."
                        else "Extract details from this transfer screenshot and generate an official receipt."
                    }
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = true,
                            text = userBubbleText,
                            attachmentBitmap = attachedBitmap,
                            attachmentName = attachedDocName
                        )
                    }

                    if (attachedBitmap == null && attachedPdfBytes == null) {
                        _aiChatMessages.update {
                            it + AiChatMessage(
                                isUser = false,
                                text = "I couldn't read the selected file ($attachedDocName). Please try picking the screenshot or PDF transfer receipt again.",
                                isError = true
                            )
                        }
                        return@launch
                    }

                    lastAiAttachmentBitmap = attachedBitmap
                    lastAiAttachmentName = attachedDocName
                    lastAiAttachmentSize = attachedDocSize
                    lastAiAttachmentIsPdf = isPdfDoc
                } else {
                    _aiChatMessages.update {
                        it + AiChatMessage(isUser = true, text = trimmed)
                    }
                }

                val aiRes = aiAssistantService.sendMessage(
                    userMessage = trimmed,
                    history = priorHistory,
                    business = biz,
                    entitlements = effectiveEntitlements.value,
                    customers = customers.value,
                    attachedImage = attachedBitmap,
                    attachedPdfBytes = attachedPdfBytes
                )

                aiRes.onFailure { err ->
                    if (imageUri != null && (attachedBitmap != null || attachedPdfBytes != null)) {
                        // Fallback directly to ReceiptOcrService so an upload still produces a receipt if the conversational endpoint errored transiently.
                        val ocrFallback = runOcrFallback(imageUri, attachedBitmap, attachedPdfBytes)
                        if (ocrFallback != null && (ocrFallback.amount ?: 0.0) > 0.0) {
                            val fallbackResult = AssistantActionResult(
                                reply = "Extracted details directly from your uploaded proof.",
                                action = AssistantActionType.GENERATE_RECEIPT_NOW,
                                customerName = ocrFallback.senderName.ifBlank { ocrFallback.senderAccountName },
                                amount = ocrFallback.amount,
                                currency = ocrFallback.currency.ifBlank { biz.defaultCurrency.ifBlank { "NGN" } },
                                paymentPurpose = ocrFallback.paymentNarration.ifBlank { "Bank Transfer Payment" },
                                senderName = ocrFallback.senderName,
                                senderAccountName = ocrFallback.senderAccountName,
                                senderBank = ocrFallback.senderBank,
                                beneficiaryBank = ocrFallback.beneficiaryBank,
                                transactionReference = ocrFallback.transactionReference,
                                sessionId = ocrFallback.sessionId,
                                transactionDate = ocrFallback.transactionDate,
                                transactionTime = ocrFallback.transactionTime,
                                transferChannel = ocrFallback.transferChannel,
                                paymentNarration = ocrFallback.paymentNarration
                            )
                            handleAiActionResult(
                                result = fallbackResult,
                                rawUserText = trimmed,
                                hasCurrentAttachment = true,
                                sourceUri = imageUri,
                                business = biz,
                                attachedBitmap = attachedBitmap,
                                attachedPdfBytes = attachedPdfBytes,
                                attachedDocName = attachedDocName,
                                attachedDocSize = attachedDocSize,
                                isPdfAttachment = isPdfDoc,
                                receiptFlowViewModel = receiptFlowViewModel,
                                onNavigateToRoute = onNavigateToRoute
                            )
                            return@onFailure
                        }
                    }
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = err.localizedMessage ?: "AI Assistant could not process your request.",
                            isError = true
                        )
                    }
                }.onSuccess { result ->
                    handleAiActionResult(
                        result = result,
                        rawUserText = trimmed,
                        hasCurrentAttachment = (imageUri != null),
                        sourceUri = imageUri,
                        business = biz,
                        attachedBitmap = attachedBitmap ?: lastAiAttachmentBitmap,
                        attachedPdfBytes = attachedPdfBytes,
                        attachedDocName = attachedDocName.ifBlank { lastAiAttachmentName },
                        attachedDocSize = if (attachedDocSize > 0L) attachedDocSize else lastAiAttachmentSize,
                        isPdfAttachment = if (imageUri != null) isPdfDoc else lastAiAttachmentIsPdf,
                        receiptFlowViewModel = receiptFlowViewModel,
                        onNavigateToRoute = onNavigateToRoute
                    )
                }
            } finally {
                _aiChatBusy.value = false
            }
        }
    }

    private suspend fun runOcrFallback(
        sourceUri: Uri?,
        attachedBitmap: Bitmap?,
        attachedPdfBytes: ByteArray?
    ): com.example.data.model.ExtractedBankReceipt? {
        val context = getApplication<Application>()
        return if (sourceUri != null && (attachedPdfBytes != null || attachedBitmap != null)) {
            receiptOcrService.extractFromDocumentUri(
                context = context,
                uri = sourceUri,
                mimeType = if (attachedPdfBytes != null) "application/pdf" else null,
                preDecodedPreview = attachedBitmap
            ).getOrNull()?.first
        } else if (attachedBitmap != null && !attachedBitmap.isRecycled) {
            runCatching { receiptOcrService.extractWithBitmap(attachedBitmap) }.getOrNull()
        } else {
            null
        }
    }

    private suspend fun handleAiActionResult(
        result: AssistantActionResult,
        rawUserText: String,
        hasCurrentAttachment: Boolean,
        sourceUri: Uri?,
        business: Business,
        attachedBitmap: Bitmap?,
        attachedPdfBytes: ByteArray?,
        attachedDocName: String,
        attachedDocSize: Long,
        isPdfAttachment: Boolean,
        receiptFlowViewModel: ReceiptFlowViewModel,
        onNavigateToRoute: (String) -> Unit
    ) {
        val ent = effectiveEntitlements.value
        val role = currentStaffRole.value

        var enriched = result
        if (hasCurrentAttachment && (enriched.amount == null || enriched.amount <= 0.0 ||
                (enriched.customerName.isBlank() && enriched.senderName.isBlank() && enriched.senderAccountName.isBlank()))
        ) {
            val ocr = runOcrFallback(sourceUri, attachedBitmap, attachedPdfBytes)
            if (ocr != null) {
                val mergedCustomer = enriched.customerName
                    .ifBlank { enriched.senderName }
                    .ifBlank { enriched.senderAccountName }
                    .ifBlank { ocr.senderName }
                    .ifBlank { ocr.senderAccountName }
                enriched = enriched.copy(
                    amount = enriched.amount?.takeIf { it > 0.0 } ?: ocr.amount,
                    currency = enriched.currency.ifBlank { ocr.currency.ifBlank { "NGN" } },
                    customerName = mergedCustomer,
                    senderName = enriched.senderName.ifBlank { ocr.senderName },
                    senderAccountName = enriched.senderAccountName.ifBlank { ocr.senderAccountName },
                    senderBank = enriched.senderBank.ifBlank { ocr.senderBank },
                    beneficiaryBank = enriched.beneficiaryBank.ifBlank { ocr.beneficiaryBank },
                    transactionReference = enriched.transactionReference.ifBlank { ocr.transactionReference },
                    sessionId = enriched.sessionId.ifBlank { ocr.sessionId },
                    transactionDate = enriched.transactionDate.ifBlank { ocr.transactionDate },
                    transactionTime = enriched.transactionTime.ifBlank { ocr.transactionTime },
                    transferChannel = enriched.transferChannel.ifBlank { ocr.transferChannel },
                    paymentNarration = enriched.paymentNarration.ifBlank { ocr.paymentNarration },
                    paymentPurpose = enriched.paymentPurpose.ifBlank { ocr.paymentNarration.ifBlank { "Bank Transfer Payment" } }
                )
            }
        }

        val resolvedCustomerName = enriched.customerName
            .ifBlank { enriched.senderName }
            .ifBlank { enriched.senderAccountName }
            .trim()
        val resolvedPurpose = enriched.paymentPurpose
            .ifBlank { enriched.paymentNarration }
            .ifBlank { if (hasCurrentAttachment) "Bank Transfer Payment" else "" }
            .trim()
        enriched = enriched.copy(
            customerName = resolvedCustomerName,
            paymentPurpose = resolvedPurpose
        )

        val matchedTemplate = resolveAllowedTemplate(enriched.templateType, business, ent)
        val wantsDraftOnly = rawUserText.contains("draft", ignoreCase = true) ||
            rawUserText.contains("edit first", ignoreCase = true) ||
            rawUserText.contains("review first", ignoreCase = true) ||
            rawUserText.contains("pre-fill", ignoreCase = true) ||
            rawUserText.contains("prefill", ignoreCase = true)

        val resolvedAction = when {
            (enriched.action == AssistantActionType.PREPARE_RECEIPT_DRAFT ||
                (hasCurrentAttachment && enriched.action == AssistantActionType.NONE)) &&
                !wantsDraftOnly &&
                (enriched.amount ?: 0.0) > 0.0 &&
                enriched.customerName.isNotBlank() -> AssistantActionType.GENERATE_RECEIPT_NOW
            else -> enriched.action
        }

        when (resolvedAction) {
            AssistantActionType.GENERATE_RECEIPT_NOW -> {
                if (!isCurrentStaffActive.value || !role.canGenerateReceipts()) {
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = "Your current staff role (${role.displayName}) does not have permission to generate receipts.",
                            isError = true
                        )
                    }
                    return
                }
                val amount = enriched.amount
                if (amount == null || amount <= 0.0 || enriched.customerName.isBlank()) {
                    applyAiDraftToFlow(enriched, attachedBitmap, attachedDocName, attachedDocSize, matchedTemplate, receiptFlowViewModel)
                    val missingHint = when {
                        amount == null || amount <= 0.0 -> "I couldn't clearly read the transfer amount from the uploaded file. Please type the amount (e.g., \"Amount is 15000\") or review the pre-filled Generate Receipt form."
                        else -> "I read ₦${"%,.2f".format(amount)} from the transfer proof, but couldn't see the customer/sender name. Reply with the customer name (e.g., \"Customer is Amina Bello\") and I'll generate the receipt right away."
                    }
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = missingHint,
                            preparedDraftNotice = "Pre-filled available details in Generate Receipt",
                            suggestedRoute = "generate"
                        )
                    }
                    return
                }
                val discount = (enriched.discountAmount ?: 0.0).coerceAtLeast(0.0)
                if (discount >= amount) {
                    applyAiDraftToFlow(enriched, attachedBitmap, attachedDocName, attachedDocSize, matchedTemplate, receiptFlowViewModel)
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = "Discount must be less than the amount. I pre-filled your draft so you can adjust the figures.",
                            preparedDraftNotice = "Draft ready for amount adjustment",
                            suggestedRoute = "generate",
                            isError = true
                        )
                    }
                    return
                }
                val purpose = enriched.paymentPurpose.ifBlank {
                    enriched.paymentNarration.ifBlank { "Payment for goods / services" }
                }
                val existingCust = customers.value.firstOrNull {
                    it.fullName.equals(enriched.customerName, ignoreCase = true) ||
                        (enriched.customerPhone.isNotBlank() && it.phone == enriched.customerPhone)
                }
                val currency = enriched.currency.ifBlank { business.defaultCurrency.ifBlank { "NGN" } }
                val netPaid = (amount - discount).coerceAtLeast(0.0)
                val words = AmountInWords.toNairaWords(netPaid, currency)
                val thumbB64 = withContext(Dispatchers.Default) {
                    receiptOcrService.encodeThumbnailBase64(attachedBitmap)
                }
                val docType = when {
                    isPdfAttachment || attachedPdfBytes != null -> "PDF"
                    attachedBitmap != null -> "IMAGE"
                    else -> "AI_FAST_ENTRY"
                }
                val txRecord = BankTransactionRecord(
                    documentType = docType,
                    documentName = attachedDocName,
                    documentSizeBytes = attachedDocSize,
                    thumbnailBase64 = thumbB64,
                    amount = amount,
                    currency = currency,
                    transactionDate = enriched.transactionDate,
                    transactionTime = enriched.transactionTime,
                    transactionReference = enriched.transactionReference,
                    sessionId = enriched.sessionId,
                    paymentNarration = enriched.paymentNarration,
                    transferChannel = enriched.transferChannel.ifBlank {
                        if (enriched.senderBank.isNotBlank() || attachedBitmap != null || attachedPdfBytes != null) "Bank Transfer" else "Fast AI Entry"
                    },
                    senderName = enriched.senderName.ifBlank { enriched.customerName },
                    senderAccountName = enriched.senderAccountName.ifBlank { enriched.senderName.ifBlank { enriched.customerName } },
                    senderBank = enriched.senderBank,
                    beneficiaryBank = enriched.beneficiaryBank,
                    humanReviewed = true
                )
                val customer = Customer(
                    id = existingCust?.id.orEmpty(),
                    fullName = enriched.customerName,
                    phone = enriched.customerPhone.ifBlank { existingCust?.phone.orEmpty() },
                    email = enriched.customerEmail.ifBlank { existingCust?.email.orEmpty() },
                    address = existingCust?.address.orEmpty()
                )
                val idemKey = "ai_${UUID.randomUUID().toString().replace("-", "").take(14)}"
                repository.issueReceiptFromTransaction(
                    business = business,
                    transaction = txRecord,
                    customer = customer,
                    paymentPurpose = purpose,
                    invoiceNumber = enriched.invoiceNumber,
                    orderNumber = "",
                    notes = enriched.notes,
                    amountPaid = netPaid,
                    discountAmount = discount,
                    amountInWords = words,
                    templateType = matchedTemplate,
                    actorRole = role,
                    effectiveEntitlements = ent,
                    idempotencyKey = idemKey
                ).onSuccess { receipt ->
                    lastAiAttachmentBitmap = null
                    lastAiAttachmentName = ""
                    lastAiAttachmentSize = 0L
                    lastAiAttachmentIsPdf = false
                    val bmp = withContext(Dispatchers.Default) {
                        ReceiptRenderer.renderLandscapeReceipt(business, receipt, matchedTemplate)
                    }
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = "${enriched.reply}\n\nReceipt ${receipt.receiptNumber} issued for ${receipt.customerName} (₦${"%,.2f".format(receipt.amountPaid)}).",
                            generatedReceipt = receipt,
                            renderedReceiptBitmap = bmp
                        )
                    }
                }.onFailure { err ->
                    applyAiDraftToFlow(enriched, attachedBitmap, attachedDocName, attachedDocSize, matchedTemplate, receiptFlowViewModel)
                    val msg = err.localizedMessage ?: "Unable to issue receipt."
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = "Could not complete instant generation: $msg",
                            preparedDraftNotice = "Details saved into Generate Receipt draft",
                            suggestedRoute = if (msg.contains("limit", ignoreCase = true) || msg.contains("subscription", ignoreCase = true)) "billing" else "generate",
                            isError = true
                        )
                    }
                }
            }

            AssistantActionType.PREPARE_RECEIPT_DRAFT -> {
                if (!role.canGenerateReceipts()) {
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = "Your role (${role.displayName}) cannot create receipt drafts.",
                            isError = true
                        )
                    }
                    return
                }
                applyAiDraftToFlow(enriched, attachedBitmap, attachedDocName, attachedDocSize, matchedTemplate, receiptFlowViewModel)
                _aiChatMessages.update {
                    it + AiChatMessage(
                        isUser = false,
                        text = enriched.reply,
                        preparedDraftNotice = "Draft pre-filled in Generate Receipt",
                        suggestedRoute = "generate"
                    )
                }
            }

            AssistantActionType.REPORT_COMPLAINT -> {
                val subject = enriched.complaintSubject.ifBlank { "App Difficulty Reported via AI Assistant" }
                val category = enriched.complaintCategory.ifBlank { "TECHNICAL" }
                val desc = enriched.complaintDescription.ifBlank { rawUserText }
                submitComplaintAndNotifyAdmin(
                    business = business,
                    subject = subject,
                    category = category,
                    description = desc,
                    source = "AI_CHATBOT"
                ).onSuccess { (ticketId, delivered, note) ->
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = "${enriched.reply}\n\n$note",
                            reportedTicketId = ticketId,
                            ticketEmailDelivered = delivered
                        )
                    }
                }.onFailure { err ->
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = "I tried to escalate your complaint to the Super Admin, but saving failed: ${err.localizedMessage ?: "Unknown error"}",
                            isError = true
                        )
                    }
                }
            }

            AssistantActionType.CHECK_QUOTA_SUMMARY -> {
                val receiptsLine = if (ent.maxReceiptsPerCycle < 0) {
                    "Receipts: Unlimited (${ent.receiptsUsed} used this cycle)"
                } else {
                    "Receipts: ${ent.receiptsUsed} used of ${ent.totalReceiptAllowance} (${ent.receiptsRemaining} remaining)"
                }
                val ocrLine = if (ent.maxOcrPerCycle < 0) {
                    "AI OCR: Unlimited (${ent.ocrUsed} used)"
                } else {
                    "AI OCR: ${ent.ocrUsed}/${ent.maxOcrPerCycle} used"
                }
                val storageLine = "Storage: ${BillingCalculator.formatBytes(ent.storageUsedBytes)} / ${BillingCalculator.formatBytes(ent.storageLimitBytes)}"
                val isActiveNow = business.isSubscriptionActive(gracePeriodEndMillis = ent.gracePeriodEndMillis)
                val summaryText = buildString {
                    appendLine(enriched.reply)
                    appendLine()
                    appendLine("• Plan: ${ent.planName} (${if (isActiveNow) "Active" else business.effectiveSubscriptionStatus(gracePeriodEndMillis = ent.gracePeriodEndMillis).label})")
                    appendLine("• $receiptsLine")
                    appendLine("• $ocrLine")
                    appendLine("• $storageLine")
                }.trim()
                _aiChatMessages.update {
                    it + AiChatMessage(
                        isUser = false,
                        text = summaryText,
                        suggestedRoute = if (ent.isReceiptQuotaExceeded || ent.isOcrQuotaExceeded || ent.shouldBlockNewUploads || !isActiveNow) "billing" else null
                    )
                }
            }

            AssistantActionType.OPEN_SCREEN -> {
                val route = mapTargetScreenToRoute(enriched.targetScreen)
                _aiChatMessages.update {
                    it + AiChatMessage(
                        isUser = false,
                        text = enriched.reply,
                        suggestedRoute = route
                    )
                }
                if (!route.isNullOrBlank()) {
                    onNavigateToRoute(route)
                }
            }

            AssistantActionType.NONE -> {
                if (hasCurrentAttachment) {
                    applyAiDraftToFlow(enriched, attachedBitmap, attachedDocName, attachedDocSize, matchedTemplate, receiptFlowViewModel)
                    _aiChatMessages.update {
                        it + AiChatMessage(
                            isUser = false,
                            text = "${enriched.reply}\n\nYou can also tell me the customer name and amount here to issue the receipt immediately.",
                            preparedDraftNotice = "Attachment ready — pre-filled into Generate Receipt",
                            suggestedRoute = "generate"
                        )
                    }
                } else {
                    _aiChatMessages.update {
                        it + AiChatMessage(isUser = false, text = enriched.reply)
                    }
                }
            }
        }
    }

    private fun applyAiDraftToFlow(
        result: AssistantActionResult,
        attachedBitmap: Bitmap?,
        attachedDocName: String,
        attachedDocSize: Long,
        template: ReceiptTemplateType,
        receiptFlowViewModel: ReceiptFlowViewModel
    ) {
        val existingCust = if (result.customerName.isNotBlank()) {
            customers.value.firstOrNull {
                it.fullName.equals(result.customerName, ignoreCase = true) ||
                    (result.customerPhone.isNotBlank() && it.phone == result.customerPhone)
            }
        } else null
        receiptFlowViewModel.updateDraft { cur ->
            cur.copy(
                previewBitmap = attachedBitmap ?: cur.previewBitmap,
                documentName = attachedDocName.ifBlank { cur.documentName },
                documentSizeBytes = if (attachedDocSize > 0L) attachedDocSize else cur.documentSizeBytes,
                amountInput = result.amount?.let { amt ->
                    if (amt % 1.0 == 0.0) amt.toLong().toString() else amt.toString()
                } ?: cur.amountInput,
                discountInput = result.discountAmount?.let { d ->
                    if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
                } ?: cur.discountInput,
                currency = result.currency.ifBlank { cur.currency },
                selectedCustomerId = existingCust?.id ?: cur.selectedCustomerId,
                customerName = result.customerName.ifBlank { cur.customerName },
                customerPhone = result.customerPhone.ifBlank { existingCust?.phone ?: cur.customerPhone },
                customerEmail = result.customerEmail.ifBlank { existingCust?.email ?: cur.customerEmail },
                customerAddress = existingCust?.address ?: cur.customerAddress,
                paymentPurpose = result.paymentPurpose.ifBlank { cur.paymentPurpose },
                senderName = result.senderName.ifBlank { cur.senderName },
                senderAccountName = result.senderAccountName.ifBlank { result.senderName.ifBlank { cur.senderAccountName } },
                senderBank = result.senderBank.ifBlank { cur.senderBank },
                beneficiaryBank = result.beneficiaryBank.ifBlank { cur.beneficiaryBank },
                transactionDate = result.transactionDate.ifBlank { cur.transactionDate },
                transactionTime = result.transactionTime.ifBlank { cur.transactionTime },
                transactionReference = result.transactionReference.ifBlank { cur.transactionReference },
                sessionId = result.sessionId.ifBlank { cur.sessionId },
                transferChannel = result.transferChannel.ifBlank { cur.transferChannel },
                paymentNarration = result.paymentNarration.ifBlank { cur.paymentNarration },
                invoiceNumber = result.invoiceNumber.ifBlank { cur.invoiceNumber },
                notes = result.notes.ifBlank { cur.notes },
                selectedTemplate = template
            )
        }
    }

    private fun resolveAllowedTemplate(
        rawTemplate: String,
        business: Business,
        entitlements: EffectiveEntitlements
    ): ReceiptTemplateType {
        val defaultTpl = runCatching { ReceiptTemplateType.valueOf(business.defaultTemplate) }
            .getOrDefault(ReceiptTemplateType.STANDARD)
        val parsed = if (rawTemplate.isBlank()) {
            defaultTpl
        } else {
            runCatching { ReceiptTemplateType.valueOf(rawTemplate.uppercase()) }
                .getOrDefault(defaultTpl)
        }
        val allowed = entitlements.allowedTemplates.ifEmpty { ReceiptTemplateType.entries }
        return if (parsed in allowed) parsed else allowed.first()
    }

    private fun mapTargetScreenToRoute(target: String): String? = when (target.uppercase()) {
        "NONE", "" -> null
        "GENERATE" -> if (currentStaffRole.value.canGenerateReceipts()) "generate" else "receipts"
        "RECEIPTS" -> "receipts"
        "CUSTOMERS" -> if (currentStaffRole.value.canManageCustomers()) "customers" else "receipts"
        "BILLING" -> if (currentStaffRole.value.canManageStaffAndSubscription()) "billing" else null
        "VERIFY_QUEUE" -> if (currentStaffRole.value.canVerifyTransactions()) "verify_queue" else null
        "REPORTS" -> if (currentStaffRole.value.canViewAnalyticsAndReports()) "reports" else null
        "CALCULATOR" -> "calculator"
        "BRANDING" -> if (currentStaffRole.value.canManageSettingsAndBranding()) "branding" else null
        "STORAGE" -> "storage"
        "HELP" -> "help"
        else -> null
    }

    // --- END-OF-DAY RECEIPT CALCULATOR & BREAKDOWN SHEETS ---

    fun setLedgerTitle(title: String) {
        _ledgerTitle.value = title
    }

    fun setLedgerDate(dateStr: String) {
        _ledgerDate.value = dateStr
    }

    fun clearLedgerError() {
        _ledgerError.value = null
    }

    fun clearLedgerDraft() {
        _ledgerEntries.value = emptyList()
        _ledgerLoadedId.value = ""
        _ledgerTitle.value = "End-of-Day Breakdown (${todayDisplayLagos()})"
        _ledgerDate.value = todayDateLagos()
        _ledgerProgress.value = null
        _ledgerError.value = null
    }

    fun addOrUpdateLedgerEntry(entry: LedgerEntryItem) {
        val clean = if (entry.id.isBlank()) {
            entry.copy(id = "le_${UUID.randomUUID().toString().replace("-", "").take(10)}")
        } else entry
        _ledgerEntries.update { cur ->
            val idx = cur.indexOfFirst { it.id == clean.id }
            if (idx >= 0) cur.toMutableList().apply { set(idx, clean) } else listOf(clean) + cur
        }
    }

    fun removeLedgerEntry(entryId: String) {
        _ledgerEntries.update { cur -> cur.filterNot { it.id == entryId } }
    }

    fun analyzeBatchReceiptsForLedger(uris: List<Uri>) {
        val biz = activeBusiness.value ?: return
        if (uris.isEmpty() || _ledgerBusy.value) return
        val context = getApplication<Application>()
        viewModelScope.launch {
            _ledgerBusy.value = true
            _ledgerError.value = null
            var extractedCount = 0
            val errors = mutableListOf<String>()
            try {
                uris.forEachIndexed { index, uri ->
                    _ledgerProgress.value = "Analyzing"
                    receiptLedgerAnalyzer.analyzeDocumentUri(context, uri, biz)
                        .onSuccess { items ->
                            if (items.isNotEmpty()) {
                                extractedCount += items.size
                                _ledgerEntries.update { cur -> cur + items }
                            } else {
                                errors += "Receipt #${index + 1}: no clear monetary amount detected."
                            }
                        }
                        .onFailure { err ->
                            errors += "Receipt #${index + 1}: ${err.localizedMessage ?: "analysis failed"}"
                        }
                }
                if (errors.isNotEmpty()) {
                    _ledgerError.value = errors.joinToString("\n")
                }
                if (extractedCount > 0) {
                    _actionState.value = ActionUiState.Success("Extracted $extractedCount item(s) into your breakdown sheet.")
                } else if (errors.isNotEmpty()) {
                    _actionState.value = ActionUiState.Error(errors.first())
                }
            } finally {
                _ledgerBusy.value = false
                _ledgerProgress.value = null
            }
        }
    }

    fun analyzeCapturedBitmapForLedger(bitmap: Bitmap, label: String = "Camera Receipt") {
        val biz = activeBusiness.value ?: return
        if (_ledgerBusy.value) return
        viewModelScope.launch {
            _ledgerBusy.value = true
            _ledgerError.value = null
            _ledgerProgress.value = "Analyzing"
            try {
                receiptLedgerAnalyzer.analyzeBitmap(bitmap, label, biz)
                    .onSuccess { items ->
                        if (items.isNotEmpty()) {
                            _ledgerEntries.update { cur -> cur + items }
                            _actionState.value = ActionUiState.Success("Added ${items.size} item(s) from $label.")
                        } else {
                            _ledgerError.value = "No clear monetary amount detected in $label."
                        }
                    }
                    .onFailure { err ->
                        val msg = err.localizedMessage ?: "Failed to read $label."
                        _ledgerError.value = msg
                        _actionState.value = ActionUiState.Error(msg)
                    }
            } finally {
                _ledgerBusy.value = false
                _ledgerProgress.value = null
            }
        }
    }

    fun importTodaysIssuedReceiptsToLedger() {
        val biz = activeBusiness.value ?: return
        val tz = TimeZone.getTimeZone("Africa/Lagos")
        val nowCal = java.util.Calendar.getInstance(tz).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDayMs = nowCal.timeInMillis
        val todaysReceipts = receipts.value.filter { r ->
            val createdMs = r.createdAt?.toDate()?.time ?: System.currentTimeMillis()
            val isNotReversed = r.verificationStatus != VerificationStatus.CANCELLED.name &&
                r.verificationStatus != VerificationStatus.REJECTED.name &&
                r.verificationStatus != VerificationStatus.REVERSED.name
            createdMs >= startOfDayMs && isNotReversed && r.amountPaid > 0.0
        }
        if (todaysReceipts.isEmpty()) {
            _actionState.value = ActionUiState.Error("No active receipts found for today yet.")
            return
        }
        val existingRefs = _ledgerEntries.value.map { it.reference }.toSet()
        val newEntries = todaysReceipts
            .filter { r -> r.receiptNumber !in existingRefs }
            .map { r ->
                LedgerEntryItem(
                    id = "iss_${r.id.take(10)}",
                    documentName = r.receiptNumber,
                    merchantOrParty = r.customerName,
                    category = LedgerCategory.SALES_INCOME.name,
                    amount = r.amountPaid,
                    currency = r.currency.ifBlank { biz.defaultCurrency.ifBlank { "NGN" } },
                    paymentMethod = r.transferChannelSnapshot.ifBlank { r.senderBankSnapshot.ifBlank { "Bank Transfer" } },
                    reference = r.receiptNumber,
                    transactionDate = r.transactionDateSnapshot.ifBlank { todayDateLagos() },
                    notes = r.paymentPurpose,
                    confidenceScore = 1.0,
                    sourceType = "ISSUED_RECEIPT"
                )
            }
        if (newEntries.isEmpty()) {
            _actionState.value = ActionUiState.Success("Today's issued receipts are already included in this sheet.")
            return
        }
        _ledgerEntries.update { cur -> newEntries + cur }
        _actionState.value = ActionUiState.Success("Imported ${newEntries.size} receipt(s) issued today into Sales.")
    }

    fun saveCurrentLedgerSheet() {
        val biz = activeBusiness.value ?: return
        val entries = _ledgerEntries.value
        if (entries.isEmpty()) {
            _actionState.value = ActionUiState.Error("Upload or add at least one receipt item before saving.")
            return
        }
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Saving End-of-Day Breakdown Sheet...")
            repository.saveDailyLedgerSheet(
                business = biz,
                title = _ledgerTitle.value,
                ledgerDate = _ledgerDate.value,
                entries = entries,
                existingId = _ledgerLoadedId.value
            ).onSuccess { savedId ->
                _ledgerLoadedId.value = savedId
                _actionState.value = ActionUiState.Success("Breakdown sheet saved to cloud ledger.")
            }.onFailure { err ->
                _actionState.value = ActionUiState.Error(err.localizedMessage ?: "Failed to save breakdown sheet.")
            }
        }
    }

    fun loadSavedLedgerSheet(sheet: DailyLedgerSheet) {
        _ledgerLoadedId.value = sheet.id
        _ledgerTitle.value = sheet.title
        _ledgerDate.value = sheet.ledgerDate.ifBlank { todayDateLagos() }
        _ledgerEntries.value = sheet.entries
        _ledgerError.value = null
        _actionState.value = ActionUiState.Success("Loaded '${sheet.title}'.")
    }

    fun deleteSavedLedgerSheet(ledgerId: String) {
        val biz = activeBusiness.value ?: return
        viewModelScope.launch {
            repository.deleteDailyLedgerSheet(biz.id, ledgerId)
                .onSuccess {
                    if (_ledgerLoadedId.value == ledgerId) {
                        clearLedgerDraft()
                    }
                    _actionState.value = ActionUiState.Success("Saved breakdown sheet deleted.")
                }
                .onFailure { err ->
                    _actionState.value = ActionUiState.Error(err.localizedMessage ?: "Failed to delete sheet.")
                }
        }
    }

    // --- LIVE APP UPDATE & SUPER ADMIN PUBLISH ---

    fun dismissOptionalUpdate() {
        val setting = platformSetting.value
        if (!setting.forceUpdate) {
            _dismissedUpdateTag.value = "${setting.latestVersionCode}_${setting.latestVersionName}_${setting.configRevision}"
        }
    }

    fun applyLiveUpdate(context: Context) {
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Applying latest ReceiptsNow update...")
            val latest = repository.refreshPlatformSettingsOnce().getOrElse { platformSetting.value }
            subscriptionRepository.ensureDefaultLaunchPlansSeededAdmin()
            _liveConfigSyncRevision.value += 1L
            val tag = "${latest.latestVersionCode}_${latest.latestVersionName}_${latest.configRevision}"
            if (!latest.forceUpdate) {
                _dismissedUpdateTag.value = tag
            }

            val url = latest.updateDownloadUrl.trim()
            if (url.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("market://"))) {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onSuccess {
                    _actionState.value = ActionUiState.Success("Synced live config & opened Version ${latest.latestVersionName} installer.")
                }.onFailure {
                    _actionState.value = ActionUiState.Success("Live plans & settings updated! Download Version ${latest.latestVersionName} from: $url")
                }
            } else {
                _actionState.value = ActionUiState.Success(
                    "Live configuration & plans synced to Version ${latest.latestVersionName}! Install the updated APK if prompted by your release link."
                )
            }
        }
    }

    fun publishLiveUpdateAdmin(
        versionName: String,
        versionCode: Int,
        releaseNotes: String,
        downloadUrl: String,
        forceUpdate: Boolean,
        broadcastAnnouncement: Boolean = true
    ) {
        val cleanName = versionName.trim().ifBlank { BuildConfig.VERSION_NAME }
        val cleanCode = versionCode.coerceAtLeast(1)
        val cleanNotes = releaseNotes.trim().ifBlank { "Performance improvements, bug fixes, and latest features." }
        val current = platformSetting.value
        val nextSetting = current.copy(
            latestVersionCode = cleanCode,
            latestVersionName = cleanName,
            updateTitle = "Update to Version $cleanName",
            releaseNotes = cleanNotes,
            updateDownloadUrl = downloadUrl.trim(),
            forceUpdate = forceUpdate,
            configRevision = current.configRevision + 1L,
            publishedAtMillis = System.currentTimeMillis()
        )
        viewModelScope.launch {
            _actionState.value = ActionUiState.Busy("Publishing Version $cleanName live to all users...")
            repository.savePlatformSettingsAdmin(nextSetting)
                .onSuccess {
                    if (broadcastAnnouncement) {
                        repository.publishAnnouncementAdmin(
                            title = "ReceiptsNow v$cleanName Published",
                            message = cleanNotes,
                            category = "APP_UPDATE"
                        )
                    }
                    _actionState.value = ActionUiState.Success(
                        "Published v$cleanName live! All logged-in users will see the update pop-up & live settings immediately."
                    )
                }
                .onFailure { err ->
                    _actionState.value = ActionUiState.Error(err.localizedMessage ?: "Failed to publish live update.")
                }
        }
    }
}
