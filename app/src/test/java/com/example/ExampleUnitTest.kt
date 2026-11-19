package com.example

import com.example.data.model.BillingCalculator
import com.example.data.model.BillingFrequency
import com.example.data.model.Business
import com.example.data.model.BusinessSubscription
import com.example.data.model.LedgerCalculator
import com.example.data.model.LedgerCategory
import com.example.data.model.LedgerEntryItem
import com.example.data.model.PlanChangeType
import com.example.data.model.PlanEntitlement
import com.example.data.model.PlanStatus
import com.example.data.model.PlatformSetting
import com.example.data.model.ReceiptTemplateType
import com.example.data.model.SubscriptionPlan
import com.example.data.model.SubscriptionStatus
import com.example.data.model.UsageQuota
import com.example.data.billing.PaystackPaymentService
import com.example.ui.components.ColorWheelMath
import com.example.util.AmountInWords
import com.example.util.EmailRelayClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun amountInWords_handlesStandardAndKoboRounding() {
        assertEquals(
            "One Hundred and Twenty-Three Naira and Ninety-Nine Kobo Only",
            AmountInWords.toNairaWords(123.994, "NGN")
        )
        assertEquals(
            "Zero Naira and Fifty Kobo Only",
            AmountInWords.toNairaWords(0.50, "NGN")
        )
        assertEquals(
            "One Hundred and Fifty Dollars Only",
            AmountInWords.toNairaWords(150.0, "USD")
        )
    }

    @Test
    fun amountInWords_handlesTrillionsAndInvalidBounds() {
        assertEquals(
            "One Trillion, Five Hundred Billion Naira Only",
            AmountInWords.toNairaWords(1_500_000_000_000.0, "NGN")
        )
        assertEquals("Invalid Amount", AmountInWords.toNairaWords(-10.0, "NGN"))
        assertEquals("Invalid Amount", AmountInWords.toNairaWords(Double.NaN, "NGN"))
        assertEquals("Invalid Amount", AmountInWords.toNairaWords(Double.POSITIVE_INFINITY, "NGN"))
    }

    @Test
    fun launchPricing_mustRemainFixedAt1500MonthlyAnd18000Annual() {
        val launchPlans = BillingCalculator.defaultLaunchPlans()
        val monthly = launchPlans.first { it.planCode == "MONTHLY" }
        val annual = launchPlans.first { it.planCode == "ANNUAL" }

        assertEquals(150_000L, monthly.priceMinor)
        assertEquals(1_800_000L, annual.priceMinor)
        assertEquals(12L * monthly.priceMinor, annual.priceMinor)
        assertEquals(30, monthly.intervalDays)
        assertEquals(365, annual.intervalDays)
        assertEquals("₦1,500", monthly.formattedPrice())
        assertEquals("₦18,000", annual.formattedPrice())
    }

    @Test
    fun prorationQuote_computesExactMinorUnitsForMidcycleUpgrade() {
        val launchPlans = BillingCalculator.defaultLaunchPlans()
        val monthly = launchPlans.first { it.planCode == "MONTHLY" }
        val annual = launchPlans.first { it.planCode == "ANNUAL" }

        val startMs = 1_700_000_000_000L
        val endMs = startMs + 30L * 86_400_000L
        val midpointMs = startMs + 15L * 86_400_000L
        val currentSub = BusinessSubscription(
            businessId = "biz_1",
            planId = monthly.planId,
            planCode = monthly.planCode,
            planNameSnapshot = monthly.name,
            agreedPriceMinor = 150_000L,
            agreedIntervalDays = 30,
            status = SubscriptionStatus.ACTIVE.name,
            cycleStartMillis = startMs,
            cycleEndMillis = endMs
        )

        val quote = BillingCalculator.calculateProrationQuote(
            currentSub = currentSub,
            targetPlan = annual,
            nowMillis = midpointMs,
            prorationEnabled = true
        )

        assertEquals(PlanChangeType.UPGRADE, quote.changeType)
        assertEquals(1_800_000L, quote.targetPlanPriceMinor)
        assertEquals(75_000L, quote.prorationCreditMinor) // exact half of ₦1,500 = ₦750 (75,000 kobo)
        assertEquals(1_725_000L, quote.netPayableMinor)
    }

    @Test
    fun thresholdEvaluation_andNonDestructiveDowngradeWarnings() {
        val firstPass = BillingCalculator.evaluateNewlyTriggeredThresholds(
            used = 76L,
            allocated = 100L,
            alreadySent = emptyList(),
            configuredThresholds = listOf(50, 75, 90, 100)
        )
        assertEquals(listOf(50, 75), firstPass)

        val secondPass = BillingCalculator.evaluateNewlyTriggeredThresholds(
            used = 92L,
            allocated = 100L,
            alreadySent = firstPass,
            configuredThresholds = listOf(50, 75, 90, 100)
        )
        assertEquals(listOf(90), secondPass)

        val downgradedPlan = SubscriptionPlan(
            planId = "plan_starter",
            planCode = "STARTER",
            name = "Starter",
            priceMinor = 150_000L,
            billingFrequency = BillingFrequency.MONTHLY.name,
            intervalDays = 30,
            status = PlanStatus.ACTIVE.name
        )
        val downgradedEnt = PlanEntitlement(
            planId = "plan_starter",
            maxReceiptsPerCycle = 50,
            storageLimitBytes = 50L * 1024L * 1024L,
            maxStaffAccounts = 2,
            maxCustomers = 10
        )
        val effective = BillingCalculator.resolveEffectiveEntitlements(
            business = Business(id = "biz_down", storageUsedBytes = 80L * 1024L * 1024L),
            subscription = BusinessSubscription(businessId = "biz_down", planId = "plan_starter", planCode = "STARTER", planNameSnapshot = "Starter"),
            plans = listOf(downgradedPlan),
            entitlements = listOf(downgradedEnt),
            quota = UsageQuota(receiptsAllocated = 50, receiptsUsed = 75),
            storage = null,
            custom = null,
            currentStaffCount = 4,
            currentCustomerCount = 25
        )
        assertEquals(4, effective.overLimitWarnings.size)
        assertTrue(effective.overLimitWarnings.any { it.contains("Existing receipts remain safe") })
        assertTrue(effective.overLimitWarnings.any { it.contains("No files were deleted") })
    }

    @Test
    fun mrrAndArrSummary_aggregatesAcrossPlansInMinorUnits() {
        val launchPlans = BillingCalculator.defaultLaunchPlans()
        val subs = listOf(
            BusinessSubscription(
                businessId = "b1",
                planId = BillingCalculator.LAUNCH_MONTHLY_PLAN_ID,
                planCode = "MONTHLY",
                agreedPriceMinor = 150_000L,
                agreedIntervalDays = 30,
                status = SubscriptionStatus.ACTIVE.name,
                cycleEndMillis = Long.MAX_VALUE
            ),
            BusinessSubscription(
                businessId = "b2",
                planId = BillingCalculator.LAUNCH_ANNUAL_PLAN_ID,
                planCode = "ANNUAL",
                agreedPriceMinor = 1_800_000L,
                agreedBillingFrequency = BillingFrequency.ANNUAL.name,
                agreedIntervalDays = 365,
                status = SubscriptionStatus.ACTIVE.name,
                cycleEndMillis = Long.MAX_VALUE
            )
        )
        val summary = BillingCalculator.computeMrrAndArr(subs, launchPlans)
        assertEquals(2, summary.activeSubscribers)
        assertEquals(150_000L + 150_000L, summary.mrrMinor) // ₦1,500 + (₦18,000 / 12) = ₦3,000 MRR
        assertEquals(3_600_000L, summary.arrMinor) // ₦36,000 ARR
    }

    @Test
    fun emailRelayClient_normalizesAppsScriptAndRejectsInvalidUrls() {
        val multiUserUrl = "  \"https://script.google.com/macros/u/0/s/AKfycby12345/exec\"\n "
        val normalized = EmailRelayClient.normalizeAndValidateUrl(multiUserUrl)
        assertTrue(normalized.isSuccess)
        assertEquals("https://script.google.com/macros/s/AKfycby12345/exec", normalized.getOrThrow().toString())

        val noScheme = EmailRelayClient.normalizeAndValidateUrl("example.com/relay")
        assertTrue(noScheme.isSuccess)
        assertEquals("https://example.com/relay", noScheme.getOrThrow().toString())

        val editErr = EmailRelayClient.normalizeAndValidateUrl("https://script.google.com/d/abc123/edit")
        assertTrue(editErr.isFailure)
        assertTrue(editErr.exceptionOrNull()?.message.orEmpty().contains("/exec"))

        val devErr = EmailRelayClient.normalizeAndValidateUrl("https://script.google.com/macros/s/AKfycby123/dev")
        assertTrue(devErr.isFailure)
        assertTrue(devErr.exceptionOrNull()?.message.orEmpty().contains("/dev"))

        val hostingErr = EmailRelayClient.normalizeAndValidateUrl("https://gen-lang-client-0777428447.web.app/")
        assertTrue(hostingErr.isFailure)
        assertTrue(hostingErr.exceptionOrNull()?.message.orEmpty().contains("405"))
    }

    @Test
    fun colorWheelMath_roundTripsArbitraryBrandHexColorsViaHsv() {
        val sampleColors = listOf(
            "#FF0000", "#047857", "#0B203B", "#C89B3C",
            "#7C3AED", "#0284C7", "#FFFFFF", "#000000"
        )
        for (hex in sampleColors) {
            val hsv = ColorWheelMath.parseHexToHsv(hex)
            assertNotNull("Expected valid HSV for $hex", hsv)
            val colorInt = ColorWheelMath.hsvToColorInt(hsv!![0], hsv[1], hsv[2])
            assertEquals(hex, ColorWheelMath.colorIntToHex(colorInt))
        }
    }

    @Test
    fun legacyThreeTemplatePlan_unlocksAllSixReceiptTemplates() {
        val legacyPlan = SubscriptionPlan(
            planId = BillingCalculator.LAUNCH_MONTHLY_PLAN_ID,
            planCode = "MONTHLY",
            name = "Monthly",
            priceMinor = 150_000L,
            status = PlanStatus.ACTIVE.name
        )
        val legacyEnt = PlanEntitlement(
            planId = BillingCalculator.LAUNCH_MONTHLY_PLAN_ID,
            allowedTemplates = listOf("STANDARD", "PREMIUM_CORPORATE", "COMPACT")
        )
        val effective = BillingCalculator.resolveEffectiveEntitlements(
            business = Business(id = "biz_tpl"),
            subscription = BusinessSubscription(
                businessId = "biz_tpl",
                planId = legacyPlan.planId,
                planCode = legacyPlan.planCode,
                planNameSnapshot = legacyPlan.name
            ),
            plans = listOf(legacyPlan),
            entitlements = listOf(legacyEnt),
            quota = null,
            storage = null,
            custom = null
        )
        assertEquals(ReceiptTemplateType.entries.size, effective.allowedTemplates.size)
        assertTrue(ReceiptTemplateType.MODERN_CLASSIC in effective.allowedTemplates)
        assertTrue(ReceiptTemplateType.ROYAL_CERTIFICATE in effective.allowedTemplates)
        assertTrue(ReceiptTemplateType.FINTECH_WAVE in effective.allowedTemplates)
    }

    @Test
    fun aiSchemaEnums_neverContainEmptyStrings_andTransientErrorDetectsSpikes() {
        assertTrue(com.example.data.ai.AiAssistantService.ACTION_ENUM_VALUES.all { it.isNotBlank() })
        assertTrue(com.example.data.ai.AiAssistantService.COMPLAINT_CATEGORY_VALUES.all { it.isNotBlank() })
        assertTrue(com.example.data.ai.AiAssistantService.TARGET_SCREEN_VALUES.all { it.isNotBlank() })
        assertTrue("NONE" in com.example.data.ai.AiAssistantService.TARGET_SCREEN_VALUES)

        assertTrue(
            com.example.data.ai.AiAssistantService.isTransientModelError(
                RuntimeException("This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.")
            )
        )
        assertTrue(
            com.example.data.ai.AiAssistantService.isTransientModelError(
                RuntimeException("Wrapper error", IllegalStateException("HTTP 503 Service Unavailable"))
            )
        )
        assertTrue(
            com.example.data.ai.AiAssistantService.isTransientModelError(
                RuntimeException("429 Resource exhausted")
            )
        )
        assertTrue(
            !com.example.data.ai.AiAssistantService.isTransientModelError(
                IllegalArgumentException("GenerateContentRequest.generation_config.response_schema.properties[targetScreen].enum[0]: cannot be empty")
            )
        )

        val quotaErr = RuntimeException(
            "You exceeded your current quota, please check your plan and billing details. " +
                "Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, " +
                "limit: 20, model: gemini-3.8-flash. Please retry in 9h53m15.420472623s."
        )
        assertTrue(com.example.data.ai.AiAssistantService.isQuotaOrRateLimitError(quotaErr))
        assertTrue(com.example.data.ai.AiAssistantService.isTransientModelError(quotaErr))
        val friendly = com.example.data.ai.AiAssistantService.formatFriendlyAiError(quotaErr)
        assertTrue(!friendly.contains("generate_content_free_tier_requests"))
        assertTrue(friendly.contains("quota"))
        assertTrue("CALCULATOR" in com.example.data.ai.AiAssistantService.TARGET_SCREEN_VALUES)
    }

    @Test
    fun ledgerCalculator_separatesSalesPurchasesExpenditureAndComputesProfit() {
        val entries = listOf(
            LedgerEntryItem(id = "1", merchantOrParty = "Chinedu", category = LedgerCategory.SALES_INCOME.name, amount = 120_000.0),
            LedgerEntryItem(id = "2", merchantOrParty = "Amina", category = LedgerCategory.SALES_INCOME.name, amount = 80_000.0),
            LedgerEntryItem(id = "3", merchantOrParty = "Balogun Wholesale", category = LedgerCategory.PURCHASE.name, amount = 90_000.0),
            LedgerEntryItem(id = "4", merchantOrParty = "Total Diesel", category = LedgerCategory.EXPENDITURE.name, amount = 18_500.0),
            LedgerEntryItem(id = "5", merchantOrParty = "IKEDC Power", category = LedgerCategory.EXPENDITURE.name, amount = 11_500.0),
            LedgerEntryItem(id = "6", merchantOrParty = "Customer Refund", category = LedgerCategory.REFUND_REVERSAL.name, amount = 5_000.0),
            LedgerEntryItem(id = "7", merchantOrParty = "Bank Levy", category = LedgerCategory.OTHER.name, amount = 5_000.0)
        )
        val summary = LedgerCalculator.summarize(entries)
        assertEquals(200_000.0, summary.totalSales, 0.001)
        assertEquals(90_000.0, summary.totalPurchases, 0.001)
        assertEquals(30_000.0, summary.totalExpenditure, 0.001)
        assertEquals(5_000.0, summary.totalRefunds, 0.001)
        assertEquals(5_000.0, summary.totalOther, 0.001)
        assertEquals(130_000.0, summary.totalOutflow, 0.001)
        assertEquals(110_000.0, summary.grossProfit, 0.001)
        assertEquals(70_000.0, summary.netProfit, 0.001)
        assertEquals(35.0, summary.profitMarginPercent, 0.001)
        assertEquals(7, summary.entryCount)
    }

    @Test
    fun platformSetting_updateAvailabilityChecksVersionCodeAndSemver() {
        val sameBuild = PlatformSetting(latestVersionCode = 2, latestVersionName = "1.20")
        assertFalse(sameBuild.isUpdateAvailable(2, "1.20"))

        val olderBuild = PlatformSetting(latestVersionCode = 1, latestVersionName = "1.0")
        assertFalse(olderBuild.isUpdateAvailable(2, "1.20"))

        val newerCode = PlatformSetting(latestVersionCode = 3, latestVersionName = "1.30")
        assertTrue(newerCode.isUpdateAvailable(2, "1.20"))

        val newerSemverOnly = PlatformSetting(latestVersionCode = 2, latestVersionName = "1.25")
        assertTrue(newerSemverOnly.isUpdateAvailable(2, "1.20"))
    }

    @Test
    fun paystackPaymentService_resolvesExactLinksDetectsCompletionAndParsesVerification() {
        val setting = PlatformSetting()
        val launchPlans = BillingCalculator.defaultLaunchPlans()
        val monthly = launchPlans.first { it.planCode == "MONTHLY" }
        val annual = launchPlans.first { it.planCode == "ANNUAL" }

        assertEquals(
            "https://paystack.shop/pay/1803reciepts",
            PaystackPaymentService.resolvePlanUrl(monthly, setting)
        )
        assertEquals(
            "https://paystack.shop/pay/1803receipt",
            PaystackPaymentService.resolvePlanUrl(annual, setting)
        )

        val prefilledUrl = PaystackPaymentService.buildCheckoutUrl(
            "https://paystack.shop/pay/1803reciepts",
            "owner@1803consult.ng"
        )
        assertTrue(prefilledUrl.startsWith("https://paystack.shop/pay/1803reciepts?email="))
        assertTrue(prefilledUrl.contains("owner%401803consult.ng"))

        assertEquals(
            "PSK_TRX_889900",
            PaystackPaymentService.extractReferenceFromUrl("https://checkout.paystack.com/callback?trxref=PSK_TRX_889900&reference=PSK_TRX_889900")
        )
        assertNull(PaystackPaymentService.extractReferenceFromUrl("https://paystack.shop/pay/1803receipt"))

        val detected = PaystackPaymentService.detectCompletionFromPageText(
            url = "https://paystack.shop/pay/1803reciepts",
            pageText = "Payment Successful\nThank you for your payment.\nReference: T948271635\nVisa ending 4081"
        )
        assertNotNull(detected)
        assertEquals("T948271635", detected!!.reference)
        assertEquals("card", detected.channelHint)

        assertNull(
            PaystackPaymentService.detectCompletionFromPageText(
                url = "https://paystack.shop/pay/1803reciepts",
                pageText = "Card declined - Your payment could not be processed."
            )
        )
        assertNull(
            PaystackPaymentService.detectCompletionFromPageText(
                url = "https://untrusted-site.example.com/success",
                pageText = "Payment Successful Reference: FAKE_REF_123"
            )
        )

        val validJson = """
            {
              "status": true,
              "message": "Verification successful",
              "data": {
                "id": 4455667788,
                "status": "success",
                "reference": "T948271635",
                "amount": 1800000,
                "currency": "NGN",
                "channel": "card",
                "gateway_response": "Successful",
                "paid_at": "2026-11-18T10:15:30.000Z",
                "authorization": { "brand": "visa", "last4": "4081", "bank": "ZENITH BANK" },
                "customer": { "email": "owner@1803consult.ng" }
              }
            }
        """.trimIndent()
        val okRes = PaystackPaymentService.parsePaystackVerifyJson(validJson, expectedAmountMinor = 1_800_000L)
        assertTrue(okRes.verified)
        assertEquals("T948271635", okRes.reference)
        assertEquals("4455667788", okRes.transactionId)
        assertEquals("card", okRes.channel)
        assertEquals("ZENITH BANK", okRes.bank)
        assertEquals("Visa •••• 4081", okRes.cardSummary)
        assertEquals("owner@1803consult.ng", okRes.customerEmail)
        assertEquals(PaystackPaymentService.SOURCE_PAYSTACK_API, okRes.verificationSource)

        val underpaidRes = PaystackPaymentService.parsePaystackVerifyJson(validJson, expectedAmountMinor = 2_000_000L)
        assertFalse(underpaidRes.verified)
        assertTrue(underpaidRes.errorMessage.contains("below required"))

        val failedJson = """
            {
              "status": true,
              "message": "Verification successful",
              "data": { "status": "failed", "reference": "T_FAIL_1", "amount": 150000, "gateway_response": "Declined" }
            }
        """.trimIndent()
        val failRes = PaystackPaymentService.parsePaystackVerifyJson(failedJson, expectedAmountMinor = 150_000L)
        assertFalse(failRes.verified)
        assertEquals("failed", failRes.rawStatus)
    }

    @Test
    fun formatReceiptNumber_andStripMarkdownFences_handleEdgeCases() {
        assertEquals(
            "RCN/RCT/2026/000001",
            com.example.data.repository.BusinessRepository.formatReceiptNumber(
                numberingFormat = "",
                prefix = "",
                year = 2026,
                sequence = 1L
            )
        )
        assertEquals(
            "ACME-2026-000042",
            com.example.data.repository.BusinessRepository.formatReceiptNumber(
                numberingFormat = "{PREFIX}-{YEAR}-{SEQ}",
                prefix = " acme ",
                year = 2026,
                sequence = 42L
            )
        )
        assertEquals(
            "INV/ACME/2026/000007",
            com.example.data.repository.BusinessRepository.formatReceiptNumber(
                numberingFormat = "INV/{PREFIX}/{YEAR}",
                prefix = "acme",
                year = 2026,
                sequence = 7L
            )
        )

        val fenced = "```json\n{\"reply\":\"Done\",\"action\":\"NONE\",\"targetScreen\":\"NONE\"}\n```"
        val stripped = com.example.data.ai.AiAssistantService.stripJsonMarkdownFences(fenced)
        assertEquals("{\"reply\":\"Done\",\"action\":\"NONE\",\"targetScreen\":\"NONE\"}", stripped)
    }

    @Test
    fun ninetyDayFreeTrial_expiryProrationEntitlementsAndMrrExclusion() {
        val now = 1_700_000_000_000L
        val trialEnd = now + BillingCalculator.FREE_TRIAL_MILLIS

        val activeTrial = Business(
            id = "biz_trial",
            subscriptionStatus = SubscriptionStatus.TRIALING.name,
            subscriptionPlanId = BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID,
            subscriptionPlanCode = "FREE_TRIAL",
            subscriptionActivatedMillis = now,
            subscriptionExpiryMillis = trialEnd
        )
        assertEquals(SubscriptionStatus.TRIALING, activeTrial.effectiveSubscriptionStatus(now))
        assertTrue(activeTrial.isSubscriptionActive(now))
        assertFalse(activeTrial.isTrialExpired(now))
        assertEquals(90, activeTrial.trialDaysRemaining(now))
        assertEquals(45, activeTrial.trialDaysRemaining(now + 45L * 86_400_000L))

        val expiredTrial = activeTrial.copy(subscriptionExpiryMillis = now - 1000L)
        assertEquals(SubscriptionStatus.EXPIRED, expiredTrial.effectiveSubscriptionStatus(now, gracePeriodEndMillis = now + 86_400_000L))
        assertFalse(expiredTrial.isSubscriptionActive(now))
        assertTrue(expiredTrial.isTrialExpired(now))
        assertEquals(0, expiredTrial.trialDaysRemaining(now))

        val noExpiryTrial = activeTrial.copy(subscriptionExpiryMillis = 0L)
        assertEquals(SubscriptionStatus.EXPIRED, noExpiryTrial.effectiveSubscriptionStatus(now))
        assertFalse(noExpiryTrial.isSubscriptionActive(now))

        // Trial entitlements resolve to 90-Day Free Trial defaults
        val effective = BillingCalculator.resolveEffectiveEntitlements(
            business = activeTrial,
            subscription = BusinessSubscription(
                businessId = "biz_trial",
                planId = BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID,
                planCode = "FREE_TRIAL",
                planNameSnapshot = "90-Day Free Trial",
                agreedPriceMinor = 0L,
                agreedIntervalDays = BillingCalculator.FREE_TRIAL_DAYS,
                status = SubscriptionStatus.TRIALING.name,
                cycleStartMillis = now,
                cycleEndMillis = trialEnd
            ),
            plans = BillingCalculator.defaultLaunchPlans(),
            entitlements = BillingCalculator.defaultLaunchEntitlements(),
            quota = null,
            storage = null,
            custom = null
        )
        assertEquals("90-Day Free Trial", effective.planName)
        assertEquals(300, effective.maxReceiptsPerCycle)
        assertEquals(ReceiptTemplateType.entries.size, effective.allowedTemplates.size)

        // Upgrading from free trial computes zero proration credit and full plan price
        val monthlyPlan = BillingCalculator.defaultLaunchPlans().first { it.planCode == "MONTHLY" }
        val quote = BillingCalculator.calculateProrationQuote(
            currentSub = BusinessSubscription(
                businessId = "biz_trial",
                planId = BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID,
                planCode = "FREE_TRIAL",
                agreedPriceMinor = 0L,
                agreedIntervalDays = 90,
                status = SubscriptionStatus.TRIALING.name,
                cycleStartMillis = now,
                cycleEndMillis = trialEnd
            ),
            targetPlan = monthlyPlan,
            nowMillis = now + 10L * 86_400_000L,
            prorationEnabled = true
        )
        assertEquals(PlanChangeType.NEW, quote.changeType)
        assertEquals(0L, quote.prorationCreditMinor)
        assertEquals(BillingCalculator.LAUNCH_MONTHLY_PRICE_MINOR, quote.netPayableMinor)

        // computeMrrAndArr excludes free trial from MRR/ARR and paid active subscriber counts
        val mrrSummary = BillingCalculator.computeMrrAndArr(
            subscriptions = listOf(
                BusinessSubscription(
                    businessId = "biz_trial",
                    planId = BillingCalculator.LAUNCH_FREE_TRIAL_PLAN_ID,
                    planCode = "FREE_TRIAL",
                    agreedPriceMinor = 0L,
                    agreedIntervalDays = 90,
                    status = SubscriptionStatus.TRIALING.name,
                    cycleStartMillis = now,
                    cycleEndMillis = trialEnd
                ),
                BusinessSubscription(
                    businessId = "biz_paid",
                    planId = BillingCalculator.LAUNCH_MONTHLY_PLAN_ID,
                    planCode = "MONTHLY",
                    agreedPriceMinor = 150_000L,
                    agreedBillingFrequency = BillingFrequency.MONTHLY.name,
                    agreedIntervalDays = 30,
                    status = SubscriptionStatus.ACTIVE.name,
                    cycleStartMillis = now,
                    cycleEndMillis = now + 30L * 86_400_000L
                )
            ),
            plans = BillingCalculator.defaultLaunchPlans(),
            nowMillis = now
        )
        assertEquals(1, mrrSummary.activeSubscribers)
        assertEquals(150_000L, mrrSummary.mrrMinor)
        assertEquals(1_800_000L, mrrSummary.arrMinor)
    }
}
