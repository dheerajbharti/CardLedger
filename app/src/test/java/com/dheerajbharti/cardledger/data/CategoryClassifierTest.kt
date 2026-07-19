package com.dheerajbharti.cardledger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryClassifierTest {
    @Test
    fun classifiesKnownMerchants() {
        assertEquals(SpendingCategory.FOOD_DINING, CategoryClassifier.classify("ZOMATO CYBS", "INR"))
        assertEquals(SpendingCategory.SUBSCRIPTIONS, CategoryClassifier.classify("OPENAI *CHATGPT SUBSCR", "BRL"))
        assertEquals(SpendingCategory.TRAVEL, CategoryClassifier.classify("UBER INDIA", "INR"))
    }

    @Test
    fun usesInternationalAsForeignCurrencyFallback() {
        assertEquals(SpendingCategory.INTERNATIONAL, CategoryClassifier.classify("UNKNOWN SHOP", "BRL"))
    }

    @Test
    fun retainsKnownForeignRetailBrandAsShopping() {
        assertEquals(SpendingCategory.SHOPPING, CategoryClassifier.classify("AMAZON MARKETPLACE", "USD"))
    }

    @Test
    fun treatsGenericDomesticShopAsShopping() {
        assertEquals(SpendingCategory.SHOPPING, CategoryClassifier.classify("NEIGHBOURHOOD SHOP", "INR"))
    }
}
