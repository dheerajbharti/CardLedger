package com.dheerajbharti.cardledger.data

enum class SpendingCategory(val label: String, val emoji: String) {
    FOOD_DINING("Food & dining", "🍽"),
    GROCERIES("Groceries", "🛒"),
    SUBSCRIPTIONS("Subscriptions", "🔁"),
    TRAVEL("Travel", "✈"),
    SHOPPING("Shopping", "🛍"),
    UTILITIES("Utilities", "💡"),
    EDUCATION("Education", "📚"),
    HEALTH("Health", "❤"),
    FUEL("Fuel", "⛽"),
    INTERNATIONAL("International", "🌍"),
    OTHER("Other", "•");

    companion object {
        fun fromStored(value: String?): SpendingCategory =
            entries.firstOrNull { it.name == value } ?: OTHER
    }
}
