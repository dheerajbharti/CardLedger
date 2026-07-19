package com.dheerajbharti.cardledger.data

object CategoryClassifier {
    fun classify(merchant: String, currency: String): SpendingCategory {
        val normalized = normalizeMerchant(merchant)
        return when {
            containsAny(normalized, "ZOMATO", "SWIGGY", "RESTAURANT", "CAFE", "PIZZA", "BURGER", "DOMINOS", "MCDONALD", "KFC", "FOOD") ->
                SpendingCategory.FOOD_DINING
            containsAny(normalized, "BIGBASKET", "BLINKIT", "ZEPTO", "GROCERY", "SUPERMARKET", "DMART", "RELIANCE FRESH") ->
                SpendingCategory.GROCERIES
            containsAny(normalized, "OPENAI", "CHATGPT", "NETFLIX", "SPOTIFY", "YOUTUBE", "PRIME", "HOTSTAR", "ADOBE", "MICROSOFT 365", "GOOGLE ONE", "APPLE COM BILL") ->
                SpendingCategory.SUBSCRIPTIONS
            containsAny(normalized, "UBER", "OLA", "RAPIDO", "IRCTC", "AIR INDIA", "INDIGO", "MAKEMYTRIP", "GOIBIBO", "CLEARTRIP", "HOTEL", "AIRBNB", "METRO") ->
                SpendingCategory.TRAVEL
            // Recognized retail brands remain Shopping even when the charge currency is foreign.
            containsAny(normalized, "AMAZON", "FLIPKART", "MYNTRA", "AJIO", "CROMA", "RELIANCE DIGITAL") ->
                SpendingCategory.SHOPPING
            containsAny(normalized, "AIRTEL", "JIO", "VODAFONE", "BSNL", "ELECTRICITY", "WATER", "GAS", "BROADBAND", "DTH", "UTILITY") ->
                SpendingCategory.UTILITIES
            containsAny(normalized, "UDEMY", "COURSERA", "SCHOOL", "COLLEGE", "UNIVERSITY", "BOOK", "EDUCATION", "EXAM", "TUITION") ->
                SpendingCategory.EDUCATION
            containsAny(normalized, "APOLLO", "PHARMACY", "HOSPITAL", "CLINIC", "MEDICAL", "HEALTH", "LAB") ->
                SpendingCategory.HEALTH
            containsAny(normalized, "PETROL", "DIESEL", "FUEL", "INDIAN OIL", "BHARAT PETROLEUM", "HPCL", "SHELL") ->
                SpendingCategory.FUEL
            // An otherwise-unrecognized foreign-currency merchant uses the International fallback.
            !currency.equals("INR", ignoreCase = true) -> SpendingCategory.INTERNATIONAL
            // Generic retail words are deliberately evaluated after the foreign-currency fallback.
            containsAny(normalized, "SHOP", "STORE", "MALL") -> SpendingCategory.SHOPPING
            else -> SpendingCategory.OTHER
        }
    }

    fun normalizeMerchant(merchant: String): String = merchant
        .uppercase()
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun containsAny(value: String, vararg needles: String): Boolean =
        needles.any { value.contains(it) }
}
