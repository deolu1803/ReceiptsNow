package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.ReceiptOcrService
import com.example.data.local.AppLocalDatabase
import com.example.data.local.OfflineDraftEntity
import com.example.data.model.BankTransactionRecord
import com.example.data.model.Business
import com.example.data.model.Customer
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.ExtractedBankReceipt
import com.example.data.model.ReceiptLineItem
import com.example.data.model.ReceiptRecord
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StaffRole
import com.example.data.repository.BusinessRepository
import com.example.data.repository.SubscriptionRepository
import com.example.util.AmountInWords
import com.example.util.ReceiptRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface OcrUiState {
    data object Idle : OcrUiState
    data object Analyzing : OcrUiState
    data class Extracted(val data: ExtractedBankReceipt) : OcrUiState
    data class Error(val message: String) : OcrUiState
}

data class ReceiptDraftState(
    val documentUriString: String = "",
    val documentName: String = "",
    val documentType: String = "IMAGE",
    val documentSizeBytes: Long = 0L,
    val previewBitmap: Bitmap? = null,
    val amountInput: String = "",
    val currency: String = "NGN",
    val transactionDate: String = "",
    val transactionTime: String = "",
    val transactionReference: String = "",
    val sessionId: String = "",
    val transactionId: String = "",
    val bankReceiptNumber: String = "",
    val paymentNarration: String = "",
    val transferChannel: String = "",
    val bankTransactionStatus: String = "",
    val transferFeeInput: String = "",
    val senderName: String = "",
    val senderAccountName: String = "",
    val senderBank: String = "",
    val senderAccountNumber: String = "",
    val beneficiaryName: String = "",
    val beneficiaryAccountName: String = "",
    val beneficiaryBank: String = "",
    val beneficiaryAccountNumber: String = "",
    val ocrConfidenceScore: Double = 0.0,
    val uncertainFields: List<String> = emptyList(),
    val extractionNotes: String = "",
    val humanReviewed: Boolean = false,
    val selectedCustomerId: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    val customerEmail: String = "",
    val customerAddress: String = "",
    val paymentPurpose: String = "",
    val invoiceNumber: String = "",
    val orderNumber: String = "",
    val notes: String = "",
    val discountInput: String = "0",
    val lineItems: List<ReceiptLineItem> = emptyList(),
    val selectedTemplate: ReceiptTemplateType = ReceiptTemplateType.STANDARD,
    val amendedFromReceiptId: String = "",
    val lastMimeType: String? = null,
    val idempotencyKey: String = UUID.randomUUID().toString().replace("-", "").take(16)
)

class ReceiptFlowViewModel(application: Application) : AndroidViewModel(application) {

    private val ocrService = ReceiptOcrService()
    private val localDb = AppLocalDatabase.getInstance(application)

    private val _ocrState = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val ocrState: StateFlow<OcrUiState> = _ocrState.asStateFlow()

    private val _draft = MutableStateFlow(ReceiptDraftState())
    val draft: StateFlow<ReceiptDraftState> = _draft.asStateFlow()

    private val _issuedReceipt = MutableStateFlow<ReceiptRecord?>(null)
    val issuedReceipt: StateFlow<ReceiptRecord?> = _issuedReceipt.asStateFlow()

    private val _renderedBitmap = MutableStateFlow<Bitmap?>(null)
    val renderedBitmap: StateFlow<Bitmap?> = _renderedBitmap.asStateFlow()

    private val _isIssuing = MutableStateFlow(false)
    val isIssuing: StateFlow<Boolean> = _isIssuing.asStateFlow()

    private val _issueError = MutableStateFlow<String?>(null)
    val issueError: StateFlow<String?> = _issueError.asStateFlow()

    private var ocrJob: Job? = null

    fun resetFlow(defaultTemplate: ReceiptTemplateType = ReceiptTemplateType.STANDARD) {
        ocrJob?.cancel()
        ocrJob = null
        _ocrState.value = OcrUiState.Idle
        _draft.value = ReceiptDraftState(
            selectedTemplate = defaultTemplate,
            idempotencyKey = UUID.randomUUID().toString().replace("-", "").take(16)
        )
        _issuedReceipt.value = null
        _renderedBitmap.value = null
        _isIssuing.value = false
        _issueError.value = null
    }

    private fun formatPlainAmount(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    fun startAmendmentFrom(receipt: ReceiptRecord) {
        _ocrState.value = OcrUiState.Idle
        _issuedReceipt.value = null
        _renderedBitmap.value = null
        _issueError.value = null
        val grossAmount = (receipt.amountPaid + receipt.discountAmount).coerceAtLeast(0.0)
        _draft.value = ReceiptDraftState(
            amountInput = formatPlainAmount(grossAmount),
            currency = receipt.currency,
            transactionDate = receipt.transactionDateSnapshot,
            transactionTime = receipt.transactionTimeSnapshot,
            transactionReference = receipt.transactionRefSnapshot,
            sessionId = receipt.sessionIdSnapshot,
            paymentNarration = receipt.narrationSnapshot,
            transferChannel = receipt.transferChannelSnapshot,
            senderAccountName = receipt.senderAccountNameSnapshot,
            senderBank = receipt.senderBankSnapshot,
            beneficiaryBank = receipt.beneficiaryBankSnapshot,
            selectedCustomerId = receipt.customerId,
            customerName = receipt.customerName,
            customerPhone = receipt.customerPhone,
            customerEmail = receipt.customerEmail,
            customerAddress = receipt.customerAddress,
            paymentPurpose = receipt.paymentPurpose,
            invoiceNumber = receipt.invoiceNumber,
            orderNumber = receipt.orderNumber,
            notes = receipt.notes,
            discountInput = formatPlainAmount(receipt.discountAmount.coerceAtLeast(0.0)),
            selectedTemplate = runCatching { ReceiptTemplateType.valueOf(receipt.templateType) }
                .getOrDefault(ReceiptTemplateType.STANDARD),
            amendedFromReceiptId = receipt.id,
            humanReviewed = false,
            idempotencyKey = UUID.randomUUID().toString().replace("-", "").take(16)
        )
    }

    fun retryLastDocumentOcr(
        business: Business? = null,
        subscriptionRepository: SubscriptionRepository? = null,
        effectiveEntitlements: EffectiveEntitlements? = null
    ) {
        val uriStr = _draft.value.documentUriString
        if (uriStr.isBlank()) return
        analyzeDocument(
            uri = Uri.parse(uriStr),
            mimeType = _draft.value.lastMimeType,
            business = business,
            subscriptionRepository = subscriptionRepository,
            effectiveEntitlements = effectiveEntitlements
        )
    }

    fun analyzeDocument(
        uri: Uri,
        mimeType: String? = null,
        business: Business? = null,
        subscriptionRepository: SubscriptionRepository? = null,
        effectiveEntitlements: EffectiveEntitlements? = null
    ) {
        val context = getApplication<Application>()
        ocrJob?.cancel()
        ocrJob = viewModelScope.launch {
            _ocrState.value = OcrUiState.Analyzing
            _issueError.value = null
            val (fileName, fileSize, resolvedMime) = queryFileMeta(uri, mimeType)
            val docType = if (resolvedMime.contains("pdf", ignoreCase = true)) "PDF" else "IMAGE"
            val decodedPreview = ocrService.decodeDocumentPreview(context, uri, resolvedMime)

            if (effectiveEntitlements != null && effectiveEntitlements.isOcrQuotaExceeded) {
                _ocrState.value = OcrUiState.Error(
                    "Monthly OCR allowance reached (${effectiveEntitlements.ocrUsed}/${effectiveEntitlements.maxOcrPerCycle}). Upgrade your plan or enter transaction details manually."
                )
                return@launch
            }

            if (effectiveEntitlements != null &&
                effectiveEntitlements.storageLimitBytes >= 0L &&
                (effectiveEntitlements.storageUsedBytes + fileSize) > effectiveEntitlements.storageLimitBytes &&
                effectiveEntitlements.storageOverLimitAction == com.example.data.model.StorageOverLimitAction.BLOCK_NEW_UPLOADS
            ) {
                _ocrState.value = OcrUiState.Error(
                    "Cloud storage limit reached. Existing documents remain safe; upgrade or switch plan to upload new receipts."
                )
                return@launch
            }

            _draft.update {
                it.copy(
                    documentUriString = uri.toString(),
                    documentName = fileName,
                    documentType = docType,
                    documentSizeBytes = fileSize,
                    lastMimeType = resolvedMime.ifBlank { mimeType },
                    previewBitmap = decodedPreview ?: it.previewBitmap
                )
            }

            val result = ocrService.extractFromDocumentUri(context, uri, resolvedMime, preDecodedPreview = decodedPreview)
            result.onSuccess { (extracted, preview) ->
                if (business != null && subscriptionRepository != null && effectiveEntitlements != null) {
                    subscriptionRepository.checkAndConsumeOcrQuota(
                        business = business,
                        effective = effectiveEntitlements,
                        idempotencyKey = "ocr_${_draft.value.idempotencyKey}_${uri.toString().hashCode()}"
                    )
                }
                _ocrState.value = OcrUiState.Extracted(extracted)
                _draft.update { cur ->
                    cur.copy(
                        previewBitmap = preview ?: cur.previewBitmap,
                        amountInput = extracted.amount?.let { amt ->
                            formatPlainAmount(amt)
                        } ?: cur.amountInput,
                        currency = extracted.currency.ifBlank { "NGN" },
                        transactionDate = extracted.transactionDate,
                        transactionTime = extracted.transactionTime,
                        transactionReference = extracted.transactionReference,
                        sessionId = extracted.sessionId,
                        transactionId = extracted.transactionId,
                        bankReceiptNumber = extracted.bankReceiptNumber,
                        paymentNarration = extracted.paymentNarration,
                        transferChannel = extracted.transferChannel,
                        bankTransactionStatus = extracted.transactionStatus,
                        transferFeeInput = extracted.transferFee?.toString().orEmpty(),
                        senderName = extracted.senderName,
                        senderAccountName = extracted.senderAccountName,
                        senderBank = extracted.senderBank,
                        senderAccountNumber = extracted.senderAccountNumber,
                        beneficiaryName = extracted.beneficiaryName,
                        beneficiaryAccountName = extracted.beneficiaryAccountName,
                        beneficiaryBank = extracted.beneficiaryBank,
                        beneficiaryAccountNumber = extracted.beneficiaryAccountNumber,
                        ocrConfidenceScore = extracted.confidenceScore,
                        uncertainFields = extracted.uncertainFields,
                        extractionNotes = extracted.extractionNotes,
                        humanReviewed = false
                    )
                }
            }.onFailure { err ->
                _ocrState.value = OcrUiState.Error(err.localizedMessage ?: "OCR extraction failed. You can still enter fields manually.")
            }
        }
    }

    fun updateDraft(transform: (ReceiptDraftState) -> ReceiptDraftState) {
        _draft.update(transform)
    }

    fun selectExistingCustomer(customer: Customer) {
        _draft.update {
            it.copy(
                selectedCustomerId = customer.id,
                customerName = customer.fullName,
                customerPhone = customer.phone,
                customerEmail = customer.email,
                customerAddress = customer.address
            )
        }
    }

    fun addLineItem(item: ReceiptLineItem) {
        _draft.update { cur ->
            val updated = cur.lineItems + item
            val summary = updated.joinToString(", ") { "${it.quantity}x ${it.description}" }
            val sum = updated.sumOf { it.lineTotal }
            cur.copy(
                lineItems = updated,
                paymentPurpose = if (cur.paymentPurpose.isBlank()) summary else cur.paymentPurpose,
                amountInput = if (cur.amountInput.isBlank() && sum > 0.0) formatPlainAmount(sum) else cur.amountInput
            )
        }
    }

    fun renderPreviewForReceipt(business: Business, receipt: ReceiptRecord, templateOverride: ReceiptTemplateType? = null) {
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = ReceiptRenderer.renderLandscapeReceipt(business, receipt, templateOverride)
            _renderedBitmap.value = bmp
        }
    }

    fun issueReceipt(
        repository: BusinessRepository,
        business: Business,
        actorRole: StaffRole,
        effectiveEntitlements: EffectiveEntitlements? = null
    ) {
        val state = _draft.value
        val amount = state.amountInput.replace(",", "").toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            _issueError.value = "Enter a valid transaction amount greater than 0."
            return
        }
        if (state.customerName.isBlank()) {
            _issueError.value = "Customer full name is required."
            return
        }
        if (state.paymentPurpose.isBlank()) {
            _issueError.value = "Payment purpose / description is required."
            return
        }
        if (!state.humanReviewed) {
            _issueError.value = "Please review the extracted transaction fields and confirm the review checkbox."
            return
        }

        val discount = state.discountInput.replace(",", "").toDoubleOrNull() ?: 0.0
        if (discount < 0.0 || !discount.isFinite()) {
            _issueError.value = "Discount cannot be negative."
            return
        }
        if (discount >= amount) {
            _issueError.value = "Discount must be less than the transaction amount."
            return
        }

        if (_isIssuing.value) return
        _isIssuing.value = true
        _issueError.value = null

        viewModelScope.launch {
            try {
                val thumbBase64 = withContext(Dispatchers.Default) {
                    ocrService.encodeThumbnailBase64(state.previewBitmap)
                }
            val transferFee = state.transferFeeInput.replace(",", "").toDoubleOrNull() ?: 0.0
            val netPaid = (amount - discount).coerceAtLeast(0.0)
            val words = AmountInWords.toNairaWords(netPaid, state.currency)

            val txRecord = BankTransactionRecord(
                documentType = state.documentType,
                documentName = state.documentName,
                documentSizeBytes = state.documentSizeBytes,
                thumbnailBase64 = thumbBase64,
                amount = amount,
                currency = state.currency.ifBlank { business.defaultCurrency },
                transactionDate = state.transactionDate,
                transactionTime = state.transactionTime,
                transactionReference = state.transactionReference,
                sessionId = state.sessionId,
                transactionId = state.transactionId,
                bankReceiptNumber = state.bankReceiptNumber,
                paymentNarration = state.paymentNarration,
                transferChannel = state.transferChannel,
                bankTransactionStatus = state.bankTransactionStatus,
                transferFee = transferFee,
                senderName = state.senderName,
                senderAccountName = state.senderAccountName,
                senderBank = state.senderBank,
                senderAccountNumber = state.senderAccountNumber,
                beneficiaryName = state.beneficiaryName,
                beneficiaryAccountName = state.beneficiaryAccountName,
                beneficiaryBank = state.beneficiaryBank,
                beneficiaryAccountNumber = state.beneficiaryAccountNumber,
                ocrConfidenceScore = state.ocrConfidenceScore,
                humanReviewed = true
            )
            val customer = Customer(
                id = state.selectedCustomerId,
                fullName = state.customerName.trim(),
                phone = state.customerPhone.trim(),
                email = state.customerEmail.trim(),
                address = state.customerAddress.trim()
            )

            repository.issueReceiptFromTransaction(
                business = business,
                transaction = txRecord,
                customer = customer,
                paymentPurpose = state.paymentPurpose.trim(),
                invoiceNumber = state.invoiceNumber.trim(),
                orderNumber = state.orderNumber.trim(),
                notes = state.notes.trim(),
                amountPaid = netPaid,
                discountAmount = discount,
                amountInWords = words,
                templateType = state.selectedTemplate,
                actorRole = actorRole,
                amendedFromReceiptId = state.amendedFromReceiptId,
                effectiveEntitlements = effectiveEntitlements,
                idempotencyKey = state.idempotencyKey
            ).onSuccess { receipt ->
                _issuedReceipt.value = receipt
                renderPreviewForReceipt(business, receipt, state.selectedTemplate)
            }.onFailure { err ->
                _issueError.value = err.localizedMessage ?: "Failed to issue official receipt."
            }
            } finally {
                _isIssuing.value = false
            }
        }
    }

    fun saveAsOfflineDraft(business: Business, onSaved: () -> Unit) {
        val state = _draft.value
        val amount = state.amountInput.replace(",", "").toDoubleOrNull() ?: 0.0
        viewModelScope.launch {
            val entity = OfflineDraftEntity(
                draftId = "draft_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                businessId = business.id,
                ownerUid = business.ownerUid,
                customerName = state.customerName.trim(),
                customerPhone = state.customerPhone.trim(),
                customerEmail = state.customerEmail.trim(),
                customerAddress = state.customerAddress.trim(),
                paymentPurpose = state.paymentPurpose.trim(),
                invoiceNumber = state.invoiceNumber.trim(),
                orderNumber = state.orderNumber.trim(),
                notes = state.notes.trim(),
                amount = amount,
                currency = state.currency.ifBlank { business.defaultCurrency },
                senderName = state.senderAccountName.ifBlank { state.senderName },
                senderBank = state.senderBank,
                beneficiaryBank = state.beneficiaryBank,
                transactionReference = state.transactionReference,
                sessionId = state.sessionId,
                transactionDate = state.transactionDate,
                transactionTime = state.transactionTime,
                transferChannel = state.transferChannel,
                paymentNarration = state.paymentNarration,
                documentLocalUri = state.documentUriString,
                documentName = state.documentName,
                templateType = state.selectedTemplate.name,
                syncStatus = "PENDING_SYNC",
                updatedAtMillis = System.currentTimeMillis()
            )
            localDb.offlineDraftDao().upsertDraft(entity)
            onSaved()
        }
    }

    private fun queryFileMeta(uri: Uri, fallbackMime: String?): Triple<String, Long, String> {
        val resolver = getApplication<Application>().contentResolver
        val mime = fallbackMime ?: resolver.getType(uri).orEmpty()
        var name = "bank_receipt"
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return Triple(name, size, mime)
    }
}
