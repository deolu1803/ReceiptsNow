package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AiAssistantService
import com.example.data.ai.AssistantActionType
import com.example.data.ai.ReceiptLedgerAnalyzerService
import com.example.data.billing.PaystackPaymentService
import com.example.data.local.ThemeMode
import com.example.data.local.ThemePreferences
import com.example.data.model.Business
import com.example.data.model.BusinessSubscription
import com.example.data.model.LedgerCalculator
import com.example.data.model.LedgerCategory
import com.example.data.model.LedgerEntryItem
import com.example.data.model.ReceiptRecord
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.StaffRole
import com.example.data.model.StorageUsage
import com.example.data.model.SubscriptionInvoice
import com.example.data.model.SubscriptionStatus
import com.example.data.model.UsageQuota
import com.example.data.repository.BusinessRepository
import com.example.ui.viewmodel.ReceiptFlowViewModel
import com.example.util.EmailRelayClient
import com.example.util.ReceiptRenderer
import com.example.util.ReportExporter
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Before
    fun resetFileProviderCache() {
        runCatching {
            val field = FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    private fun ensureFirebase(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.example.app")
                    .setProjectId("demo-no-project")
                    .setApiKey("fake-api-key")
                    .build()
            )
        }
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ReceiptsNow", appName)
    }

    @Test
    fun `renderLandscapeReceipt succeeds across all templates with long text`() {
        val business = Business(
            id = "biz_1",
            registeredName = "1803 Consult Enterprise Nigeria Limited",
            tradingName = "1803 Consult & Advisory Services Lagos",
            address = "Plot 42 Victoria Island Commercial Avenue, Lagos State, Nigeria",
            phone = "+234 800 1803 000",
            email = "info.1803consult@gmail.com",
            authorisedRepName = "Oluwaseun Adeyemi",
            businessSlogan = "Precision Financial Traceability for Modern Nigerian Enterprises",
            stampText = "OFFICIAL VERIFIED RECEIPT"
        )
        val receipt = ReceiptRecord(
            id = "rct_1",
            receiptNumber = "1803C/RCT/2026/000042",
            verificationCode = "1803R-ABCDEF1234",
            customerName = "Adebayo Chinedu Ibrahim Ventures & Logistics Ltd",
            customerPhone = "+2348091112233",
            customerEmail = "accounts@adebayoventures.ng",
            paymentPurpose = "Annual Enterprise Software Licensing, Advisory Onboarding, Staff Training, and Custom Receipt Automation Package",
            amountPaid = 2_450_000.50,
            discountAmount = 50_000.0,
            currency = "NGN",
            senderBankSnapshot = "Guaranty Trust Bank (GTBank)",
            senderAccountNameSnapshot = "ADEBAYO CHINEDU VENTURES",
            beneficiaryBankSnapshot = "KUDA MICROFINANCE BANK",
            transactionRefSnapshot = "000013260114091522000099112233",
            sessionIdSnapshot = "0000132601140915220000991122334455",
            narrationSnapshot = "TRF/1803 CONSULT ENTERPRISE/INV-2026-089/PHASE 2 SETTLEMENT",
            verificationStatus = "VERIFIED"
        )

        for (tpl in ReceiptTemplateType.entries) {
            val bmp = ReceiptRenderer.renderLandscapeReceipt(business, receipt, tpl)
            assertEquals(1600, bmp.width)
            assertTrue("Expected height >= 1120 for $tpl", bmp.height >= 1120)
            bmp.recycle()
        }
    }

    @Test
    fun `startAmendmentFrom restores gross amount and rejects invalid discounts`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ensureFirebase(app)
        val vm = ReceiptFlowViewModel(app)
        val original = ReceiptRecord(
            id = "rct_orig",
            amountPaid = 9500.0,
            discountAmount = 500.0,
            currency = "NGN",
            customerId = "cust_1",
            customerName = "Chidi Okafor",
            paymentPurpose = "Advisory Fee"
        )
        vm.startAmendmentFrom(original)
        assertEquals("10000", vm.draft.value.amountInput)
        assertEquals("500", vm.draft.value.discountInput)

        val repo = BusinessRepository(app)
        val biz = Business(id = "biz_1", ownerUid = "u1")

        // Confirm review checkbox, test discount >= amount
        vm.updateDraft { it.copy(humanReviewed = true, discountInput = "10000") }
        vm.issueReceipt(repo, biz, StaffRole.OWNER)
        assertEquals("Discount must be less than the transaction amount.", vm.issueError.value)

        // Test negative discount
        vm.updateDraft { it.copy(discountInput = "-50") }
        vm.issueReceipt(repo, biz, StaffRole.OWNER)
        assertEquals("Discount cannot be negative.", vm.issueError.value)
    }

    @Test
    fun `reportExporter generates valid xlsx and pdf files`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val headers = listOf("Code", "Phone", "Amount", "BadNum")
        val rows = listOf(
            listOf("001", "08012345678", "0.50", "NaN"),
            listOf("002", "07098765432", "15000.00", "Infinity")
        )
        val xlsxRes = ReportExporter.exportXlsxAndShare(activity, "Test_Rep", "Sheet1", headers, rows)
        assertTrue("XLSX export failed: ${xlsxRes.exceptionOrNull()}", xlsxRes.isSuccess)

        val exportsDir = File(activity.cacheDir, "exports")
        val xlsxFile = exportsDir.listFiles()?.firstOrNull { it.name.endsWith(".xlsx") }
        assertNotNull(xlsxFile)
        ZipFile(xlsxFile!!).use { zip ->
            val sheetXml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
            // Leading zero phone numbers must be inlineStr, not stripped numeric
            assertTrue(sheetXml.contains("<is><t>08012345678</t></is>"))
            // 0.50 is numeric
            assertTrue(sheetXml.contains("<v>0.5</v>"))
            // NaN / Infinity must be inlineStr, never invalid <v>NaN</v>
            assertTrue(sheetXml.contains("<is><t>NaN</t></is>"))
            assertTrue(sheetXml.contains("<is><t>Infinity</t></is>"))
        }

        val csvRes = ReportExporter.exportCsvAndShare(
            context = activity,
            filePrefix = "Test_Csv",
            headers = headers,
            rows = listOf(listOf("001", "08012345678", "1,500.00", "Line1\nLine2"))
        )
        assertTrue("CSV export failed: ${csvRes.exceptionOrNull()}", csvRes.isSuccess)
        val csvFile = exportsDir.listFiles()?.firstOrNull { it.name.endsWith(".csv") }
        assertNotNull(csvFile)
        val csvText = csvFile!!.readText()
        assertTrue(csvText.contains("\"1,500.00\""))
        assertTrue(csvText.contains("Line1 Line2"))

        // Verify WhatsApp receipt sharing produces an opaque JPEG FileProvider URI with ClipData and sanitized filename
        val business = Business(
            id = "biz_share",
            registeredName = "Zaria Ventures Ltd",
            tradingName = "Zaria Ventures"
        )
        val receipt = ReceiptRecord(
            id = "rct_share",
            receiptNumber = "ZV/RCT #2026/001?",
            verificationCode = "1803R-SHARE123",
            customerName = "Musa Bello",
            amountPaid = 25000.0,
            paymentPurpose = "Consultation",
            verificationStatus = "VERIFIED"
        )
        val bmp = ReceiptRenderer.renderLandscapeReceipt(business, receipt, ReceiptTemplateType.STANDARD)
        val uriRes = ReportExporter.writeReceiptCacheUri(activity, bmp, receipt.receiptNumber, asJpeg = true)
        assertTrue("Expected valid receipt cache URI: ${uriRes.exceptionOrNull()}", uriRes.isSuccess)
        val uri = uriRes.getOrThrow()
        assertTrue(uri.toString().endsWith(".jpg"))
        assertEquals("image/jpeg", activity.contentResolver.getType(uri))

        val shareRes = ReportExporter.shareReceiptViaWhatsApp(activity, bmp, business, receipt)
        assertTrue("WhatsApp share failed: ${shareRes.exceptionOrNull()}", shareRes.isSuccess)
        val startedWa = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(startedWa)
        @Suppress("DEPRECATION")
        val waInner = if (startedWa.action == Intent.ACTION_CHOOSER) {
            startedWa.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        } else startedWa
        assertNotNull(waInner)
        assertEquals(Intent.ACTION_SEND, waInner!!.action)
        assertEquals("image/jpeg", waInner.type)
        assertNotNull(waInner.clipData)
        assertTrue((waInner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        @Suppress("DEPRECATION")
        val waStreamUri = waInner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull(waStreamUri)
        bmp.recycle()

        // Verify transfer proof email fallback attaches receipt image addressed to super admin
        val sampleJpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val b64 = Base64.encodeToString(sampleJpgBytes, Base64.NO_WRAP)
        val emailRes = ReportExporter.shareTransferProofViaEmail(
            context = activity,
            toEmail = "info.1803consult@gmail.com",
            subject = "New 1803Receipts MONTHLY Transfer Payment - Test Biz",
            body = "Business Name: Test Biz\nPlan: MONTHLY\nAmount: NGN 1,500.00",
            receiptImageBase64 = b64,
            receiptFileName = "kuda_transfer.png"
        )
        assertTrue("Expected email share intent to launch: ${emailRes.exceptionOrNull()}", emailRes.isSuccess)
        val startedEmail = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(startedEmail)
        @Suppress("DEPRECATION")
        val emailInner = if (startedEmail.action == Intent.ACTION_CHOOSER) {
            startedEmail.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        } else startedEmail
        assertNotNull(emailInner)
        assertEquals(Intent.ACTION_SEND, emailInner!!.action)
        assertEquals("image/jpeg", emailInner.type)
        assertEquals("info.1803consult@gmail.com", emailInner.getStringArrayExtra(Intent.EXTRA_EMAIL)?.firstOrNull())
        assertNotNull(emailInner.clipData)
        assertTrue((emailInner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }

    @Test
    fun `themePreferences persists light and dark mode selections`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ThemePreferences(context)
        assertEquals(ThemeMode.SYSTEM, prefs.themeModeFlow.first())
        prefs.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, prefs.themeModeFlow.first())
        prefs.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, prefs.themeModeFlow.first())
        prefs.setThemeMode(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, prefs.themeModeFlow.first())
    }

    @Test
    fun `reportExporter generates subscription PDF invoice and admin CSV report`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val business = Business(
            id = "biz_inv",
            registeredName = "Lagos Prime Services Ltd",
            tradingName = "Lagos Prime",
            ownerEmail = "owner@lagosprime.ng"
        )
        val invoice = SubscriptionInvoice(
            id = "inv_1",
            invoiceNumber = "INV-1803R-0001",
            businessId = business.id,
            businessName = "Lagos Prime",
            ownerEmail = business.ownerEmail,
            paymentId = "pay_001",
            planCode = "ANNUAL",
            planNameSnapshot = "Annual",
            billingFrequencySnapshot = "ANNUAL",
            subtotalMinor = 1_800_000L,
            prorationCreditMinor = 75_000L,
            taxMinor = 0L,
            totalMinor = 1_725_000L,
            currency = "NGN",
            status = "PAID",
            cycleStartMillis = 1_700_000_000_000L,
            cycleEndMillis = 1_731_536_000_000L,
            issuedAtMillis = 1_700_000_000_000L
        )

        val pdfRes = ReportExporter.exportSubscriptionInvoicePdfAndShare(activity, invoice, business)
        assertTrue("Invoice PDF export failed: ${pdfRes.exceptionOrNull()}", pdfRes.isSuccess)
        val exportsDir = File(activity.cacheDir, "exports")
        val pdfFile = exportsDir.listFiles()?.firstOrNull { it.name.startsWith("Invoice_") && it.name.endsWith(".pdf") }
        assertNotNull(pdfFile)
        assertTrue(pdfFile!!.length() > 100L)

        val sub = BusinessSubscription(
            businessId = business.id,
            planCode = "ANNUAL",
            planNameSnapshot = "Annual",
            agreedPriceMinor = 1_800_000L,
            status = SubscriptionStatus.ACTIVE.name,
            cycleEndMillis = 1_731_536_000_000L
        )
        val quota = UsageQuota(businessId = business.id, receiptsAllocated = 3600, receiptsUsed = 42)
        val storage = StorageUsage(businessId = business.id, allocatedBytes = 2048L * 1024L * 1024L, usedBytes = 256L * 1024L)
        val adminCsvRes = ReportExporter.exportAdminSubscriptionsCsvAndShare(
            context = activity,
            businesses = listOf(business),
            subscriptions = listOf(sub),
            invoices = listOf(invoice),
            quotas = listOf(quota),
            storageUsages = listOf(storage)
        )
        assertTrue("Admin subscriptions CSV export failed: ${adminCsvRes.exceptionOrNull()}", adminCsvRes.isSuccess)
        val adminCsvFile = exportsDir.listFiles()?.firstOrNull { it.name.startsWith("ReceiptsNow_Subscriptions_") && it.name.endsWith(".csv") }
        assertNotNull(adminCsvFile)
        val csvText = adminCsvFile!!.readText()
        assertTrue(csvText.contains("INV-1803R-0001"))
        assertTrue(csvText.contains("18000.00"))
    }

    @Test
    fun `emailRelayClient preserves POST across 302 redirects and diagnoses HTTP 40x responses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        try {
            server.createContext("/ok") { ex ->
                val bodyBytes = """{"ok":true,"toEmail":"info.1803consult@gmail.com"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bodyBytes.size.toLong())
                ex.responseBody.use { it.write(bodyBytes) }
            }
            server.createContext("/redirect-post") { ex ->
                ex.responseHeaders.add("Location", "$baseUrl/post-only")
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            server.createContext("/post-only") { ex ->
                if (!ex.requestMethod.equals("POST", ignoreCase = true)) {
                    val err = """{"ok":false,"error":"Method not allowed"}""".toByteArray()
                    ex.sendResponseHeaders(405, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                } else {
                    val ok = """{"ok":true}""".toByteArray()
                    ex.sendResponseHeaders(200, ok.size.toLong())
                    ex.responseBody.use { it.write(ok) }
                }
            }
            server.createContext("/signin-redirect") { ex ->
                ex.responseHeaders.add("Location", "https://accounts.google.com/ServiceLogin")
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            server.createContext("/html-login") { ex ->
                val html = "<!doctype html><html><title>Sign in - Google Accounts</title><body>ServiceLogin</body></html>".toByteArray()
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.sendResponseHeaders(200, html.size.toLong())
                ex.responseBody.use { it.write(html) }
            }
            listOf(400, 401, 403, 404, 405).forEach { code ->
                server.createContext("/err/$code") { ex ->
                    val msg = """{"ok":false,"error":"Relay error $code"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(code, msg.size.toLong())
                    ex.responseBody.use { it.write(msg) }
                }
            }
            server.start()

            val client = OkHttpClient()
            val payload = JSONObject().put("toEmail", "info.1803consult@gmail.com")

            assertTrue(EmailRelayClient.postJson(client, "$baseUrl/ok", payload).isSuccess)
            // Standard OkHttp would downgrade POST->GET on 302 and trigger HTTP 405 on /post-only; EmailRelayClient preserves POST.
            assertTrue(EmailRelayClient.postJson(client, "$baseUrl/redirect-post", payload).isSuccess)

            val signinRes = EmailRelayClient.postJson(client, "$baseUrl/signin-redirect", payload)
            assertTrue(signinRes.isFailure)
            assertTrue(signinRes.exceptionOrNull()?.message.orEmpty().contains("Who has access: Anyone"))

            val htmlRes = EmailRelayClient.postJson(client, "$baseUrl/html-login", payload)
            assertTrue(htmlRes.isFailure)
            assertTrue(htmlRes.exceptionOrNull()?.message.orEmpty().contains("Sign-In page"))

            listOf(400, 401, 403, 404, 405).forEach { code ->
                val errRes = EmailRelayClient.postJson(client, "$baseUrl/err/$code", payload)
                assertTrue(errRes.isFailure)
                val msg = errRes.exceptionOrNull()?.message.orEmpty()
                assertTrue("Expected HTTP $code in '$msg'", msg.contains("HTTP $code"))
                assertTrue("Expected server detail in '$msg'", msg.contains("Relay error $code"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `aiAssistantService parses fast receipt generation and complaint escalation actions`() {
        val genJson = """
            {
              "reply": "Issuing your receipt now.",
              "action": "GENERATE_RECEIPT_NOW",
              "customerName": "Adaobi Okafor",
              "customerPhone": "08012345678",
              "customerEmail": "ada@example.com",
              "amount": 45000.0,
              "discountAmount": 2500.0,
              "currency": "NGN",
              "paymentPurpose": "Brand Identity Design",
              "senderName": "Adaobi Okafor",
              "senderAccountName": "ADAOBI OKAFOR",
              "senderBank": "OPay",
              "beneficiaryBank": "Kuda Microfinance Bank",
              "transactionReference": "OPAY-998877",
              "sessionId": "100004260501998877",
              "transactionDate": "2026-05-01",
              "transactionTime": "14:22:10",
              "transferChannel": "Mobile App",
              "templateType": "FINTECH_WAVE"
            }
        """.trimIndent()
        val genRes = AiAssistantService.parseAssistantResponseJson(genJson)
        assertEquals(AssistantActionType.GENERATE_RECEIPT_NOW, genRes.action)
        assertEquals("Adaobi Okafor", genRes.customerName)
        assertEquals(45000.0, genRes.amount ?: 0.0, 0.001)
        assertEquals(2500.0, genRes.discountAmount ?: 0.0, 0.001)
        assertEquals("Brand Identity Design", genRes.paymentPurpose)
        assertEquals("ADAOBI OKAFOR", genRes.senderAccountName)
        assertEquals("OPay", genRes.senderBank)
        assertEquals("Kuda Microfinance Bank", genRes.beneficiaryBank)
        assertEquals("100004260501998877", genRes.sessionId)
        assertEquals("2026-05-01", genRes.transactionDate)
        assertEquals("14:22:10", genRes.transactionTime)
        assertEquals("Mobile App", genRes.transferChannel)
        assertEquals("FINTECH_WAVE", genRes.templateType)

        val complaintJson = """
            {
              "reply": "I have escalated this difficulty to Super Admin.",
              "action": "REPORT_COMPLAINT",
              "complaintSubject": "WhatsApp receipt share difficulty",
              "complaintCategory": "WHATSAPP_SHARE",
              "complaintDescription": "Business owner reports error when sharing PDF to WhatsApp."
            }
        """.trimIndent()
        val complaintRes = AiAssistantService.parseAssistantResponseJson(complaintJson)
        assertEquals(AssistantActionType.REPORT_COMPLAINT, complaintRes.action)
        assertEquals("WHATSAPP_SHARE", complaintRes.complaintCategory)
        assertEquals("WhatsApp receipt share difficulty", complaintRes.complaintSubject)
        assertTrue(complaintRes.complaintDescription.contains("WhatsApp"))

        val navJson = """
            {
              "reply": "Opening Billing & Plans.",
              "action": "OPEN_SCREEN",
              "targetScreen": "BILLING"
            }
        """.trimIndent()
        val navRes = AiAssistantService.parseAssistantResponseJson(navJson)
        assertEquals(AssistantActionType.OPEN_SCREEN, navRes.action)
        assertEquals("BILLING", navRes.targetScreen)

        val noneNavJson = """
            {
              "reply": "Ready to help.",
              "action": "NONE",
              "targetScreen": "NONE"
            }
        """.trimIndent()
        val noneNavRes = AiAssistantService.parseAssistantResponseJson(noneNavJson)
        assertEquals(AssistantActionType.NONE, noneNavRes.action)
        assertEquals("", noneNavRes.targetScreen)
    }

    @Test
    fun `receiptLedgerAnalyzer parses categorized items and reportExporter builds PDF and XLSX sheets`() {
        val json = """
            {
              "items": [
                {
                  "merchantOrParty": "Oluwaseun Adedeji",
                  "category": "SALES_INCOME",
                  "amount": 55000.0,
                  "currency": "NGN",
                  "paymentMethod": "OPay",
                  "reference": "OP-1234",
                  "transactionDate": "2026-11-17",
                  "notes": "Product Supply"
                },
                {
                  "merchantOrParty": "Alaba Wholesale Hub",
                  "category": "PURCHASE",
                  "amount": 20000.0,
                  "currency": "NGN",
                  "paymentMethod": "GTBank",
                  "reference": "GT-5678",
                  "transactionDate": "2026-11-17",
                  "notes": "Restock cartons"
                },
                {
                  "merchantOrParty": "NNPC Fuel Station",
                  "category": "EXPENDITURE",
                  "amount": 8500.0,
                  "currency": "NGN",
                  "paymentMethod": "POS",
                  "reference": "POS-991",
                  "transactionDate": "2026-11-17",
                  "notes": "Generator fuel"
                },
                {
                  "merchantOrParty": "Invalid Zero Item",
                  "category": "OTHER",
                  "amount": 0.0
                }
              ]
            }
        """.trimIndent()

        val parsed = ReceiptLedgerAnalyzerService.parseLedgerItemsJson(json, "batch_1.jpg", "NGN")
        assertEquals(3, parsed.size)
        assertEquals(LedgerCategory.SALES_INCOME.name, parsed[0].category)
        assertEquals(LedgerCategory.PURCHASE.name, parsed[1].category)
        assertEquals(LedgerCategory.EXPENDITURE.name, parsed[2].category)

        val summary = LedgerCalculator.summarize(parsed)
        assertEquals(55_000.0, summary.totalSales, 0.001)
        assertEquals(20_000.0, summary.totalPurchases, 0.001)
        assertEquals(8_500.0, summary.totalExpenditure, 0.001)
        assertEquals(35_000.0, summary.grossProfit, 0.001)
        assertEquals(26_500.0, summary.netProfit, 0.001)

        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val biz = Business(id = "biz_tally", tradingName = "Express Consult")
        val pdfRes = ReportExporter.exportDailyLedgerPdfAndShare(
            context = activity,
            business = biz,
            title = "End-of-Day Breakdown (17 Nov 2026)",
            ledgerDate = "2026-11-17",
            entries = parsed,
            summary = summary
        )
        assertTrue("Daily ledger PDF failed: ${pdfRes.exceptionOrNull()}", pdfRes.isSuccess)
        val exportsDir = File(activity.cacheDir, "exports")
        val pdfFile = exportsDir.listFiles()?.firstOrNull { it.name.startsWith("ReceiptsNow_DayTally_") && it.name.endsWith(".pdf") }
        assertNotNull(pdfFile)
        assertTrue(pdfFile!!.length() > 100L)

        val xlsxRes = ReportExporter.exportDailyLedgerXlsxAndShare(
            context = activity,
            business = biz,
            title = "End-of-Day Breakdown (17 Nov 2026)",
            ledgerDate = "2026-11-17",
            entries = parsed,
            summary = summary
        )
        assertTrue("Daily ledger XLSX failed: ${xlsxRes.exceptionOrNull()}", xlsxRes.isSuccess)
        val xlsxFile = exportsDir.listFiles()?.firstOrNull { it.name.startsWith("ReceiptsNow_DayTally_") && it.name.endsWith(".xlsx") }
        assertNotNull(xlsxFile)
        ZipFile(xlsxFile!!).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
            assertTrue(xml.contains("Oluwaseun Adedeji"))
            assertTrue(xml.contains("Net Profit"))
            assertTrue(xml.contains("<v>26500.0</v>"))
        }
    }

    @Test
    fun `paystackPaymentService verifyTransaction verifies 200 payload and rejects failed or underpaid payments`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        try {
            server.createContext("/verify-ok") { ex ->
                val reqBody = ex.requestBody.bufferedReader().readText()
                assertTrue(reqBody.contains("PSK_OK_101"))
                val body = """
                    {
                      "status": true,
                      "message": "Verification successful",
                      "data": {
                        "id": 987654321,
                        "status": "success",
                        "reference": "PSK_OK_101",
                        "amount": 150000,
                        "currency": "NGN",
                        "channel": "bank_transfer",
                        "gateway_response": "Approved",
                        "authorization": { "bank": "Kuda Bank", "last4": "" },
                        "customer": { "email": "buyer@example.ng" }
                      }
                    }
                """.trimIndent().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.createContext("/verify-failed") { ex ->
                val body = """
                    {
                      "status": true,
                      "message": "Verification successful",
                      "data": {
                        "id": 112233,
                        "status": "abandoned",
                        "reference": "PSK_ABANDONED",
                        "amount": 150000,
                        "gateway_response": "Abandoned"
                      }
                    }
                """.trimIndent().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.start()

            val client = OkHttpClient()
            val ok = PaystackPaymentService.verifyTransaction(
                reference = "PSK_OK_101",
                expectedAmountMinor = 150_000L,
                verifyEndpointUrl = "$baseUrl/verify-ok",
                client = client
            )
            assertTrue(ok.verified)
            assertEquals("PSK_OK_101", ok.reference)
            assertEquals("bank_transfer", ok.channel)
            assertEquals("Kuda Bank", ok.bank)
            assertEquals("buyer@example.ng", ok.customerEmail)

            val underpaid = PaystackPaymentService.verifyTransaction(
                reference = "PSK_OK_101",
                expectedAmountMinor = 1_800_000L,
                verifyEndpointUrl = "$baseUrl/verify-ok",
                client = client
            )
            assertTrue(!underpaid.verified)
            assertTrue(underpaid.errorMessage.contains("below required"))

            val abandoned = PaystackPaymentService.verifyTransaction(
                reference = "PSK_ABANDONED",
                expectedAmountMinor = 150_000L,
                verifyEndpointUrl = "$baseUrl/verify-failed",
                client = client
            )
            assertTrue(!abandoned.verified)
            assertEquals("abandoned", abandoned.rawStatus)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `encodeLogoBase64 respects Firestore size bound and ledger analyzer strips markdown fences`() {
        val largeBmp = android.graphics.Bitmap.createBitmap(1600, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(largeBmp)
        val paint = android.graphics.Paint()
        for (i in 0 until 40) {
            paint.color = android.graphics.Color.rgb((i * 37) % 255, (i * 73) % 255, (i * 19) % 255)
            canvas.drawRect((i * 40).toFloat(), 0f, ((i + 1) * 40).toFloat(), 1600f, paint)
        }
        val encoded = com.example.util.BrandColorExtractor.encodeLogoBase64(largeBmp)
        largeBmp.recycle()
        assertTrue(encoded.isNotBlank())
        assertTrue("Expected encoded logo <= 420000 chars, got ${encoded.length}", encoded.length <= 420_000)

        val fencedLedger = """
            ```json
            {
              "items": [
                {
                  "merchantOrParty": "Eko Power",
                  "category": "EXPENDITURE",
                  "amount": 12500.0,
                  "currency": "NGN"
                }
              ]
            }
            ```
        """.trimIndent()
        val parsed = ReceiptLedgerAnalyzerService.parseLedgerItemsJson(fencedLedger, "power.jpg", "NGN")
        assertEquals(1, parsed.size)
        assertEquals("Eko Power", parsed[0].merchantOrParty)
        assertEquals(LedgerCategory.EXPENDITURE.name, parsed[0].category)

        val fencedAssistant = "```json\n{\"reply\":\"Done\",\"action\":\"NONE\",\"targetScreen\":\"NONE\"}\n```"
        val parsedAssistant = AiAssistantService.parseAssistantResponseJson(fencedAssistant)
        assertEquals("Done", parsedAssistant.reply)
        assertEquals(AssistantActionType.NONE, parsedAssistant.action)
    }
}
