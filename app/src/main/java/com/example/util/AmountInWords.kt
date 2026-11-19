package com.example.util

import java.math.BigDecimal
import java.math.RoundingMode

object AmountInWords {

    private val units = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen"
    )

    private val tens = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    fun toNairaWords(amount: Double, currencyCode: String = "NGN"): String {
        if (!amount.isFinite() || amount < 0.0) return "Invalid Amount"
        if (amount > 999_999_999_999_999.99) {
            return "${currencyCode.uppercase()} ${"%,.2f".format(amount)} Only"
        }
        val scaled = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)
        val totalMinor = scaled.movePointRight(2).longValueExact()
        val major = totalMinor / 100L
        val minor = (totalMinor % 100L).toInt()

        val majorUnit = when (currencyCode.uppercase()) {
            "NGN" -> "Naira"
            "USD" -> "Dollars"
            "GBP" -> "Pounds"
            "EUR" -> "Euros"
            else -> currencyCode.uppercase()
        }
        val minorUnit = when (currencyCode.uppercase()) {
            "NGN" -> "Kobo"
            "GBP" -> "Pence"
            else -> "Cents"
        }

        val majorWords = if (major == 0L) "Zero" else convertLong(major)
        return if (minor > 0) {
            "$majorWords $majorUnit and ${convertInt(minor)} $minorUnit Only"
        } else {
            "$majorWords $majorUnit Only"
        }
    }

    private fun convertLong(n: Long): String {
        if (n == 0L) return ""
        val parts = mutableListOf<String>()
        var rem = n

        val trillions = (rem / 1_000_000_000_000L).toInt()
        if (trillions > 0) {
            parts.add("${convertInt(trillions)} Trillion")
            rem %= 1_000_000_000_000L
        }

        val billions = (rem / 1_000_000_000L).toInt()
        if (billions > 0) {
            parts.add("${convertInt(billions)} Billion")
            rem %= 1_000_000_000L
        }

        val millions = (rem / 1_000_000L).toInt()
        if (millions > 0) {
            parts.add("${convertInt(millions)} Million")
            rem %= 1_000_000L
        }

        val thousands = (rem / 1_000L).toInt()
        if (thousands > 0) {
            parts.add("${convertInt(thousands)} Thousand")
            rem %= 1_000L
        }

        val rest = rem.toInt()
        if (rest > 0) {
            if (parts.isNotEmpty() && rest < 100) {
                parts.add("and ${convertInt(rest)}")
            } else {
                parts.add(convertInt(rest))
            }
        }
        return parts.joinToString(", ").replace(", and ", " and ")
    }

    private fun convertInt(n: Int): String {
        return when {
            n == 0 -> ""
            n < 20 -> units[n]
            n < 100 -> {
                val t = tens[n / 10]
                val u = units[n % 10]
                if (u.isEmpty()) t else "$t-$u"
            }
            else -> {
                val h = "${units[n / 100]} Hundred"
                val rem = n % 100
                if (rem == 0) h else "$h and ${convertInt(rem)}"
            }
        }
    }
}
