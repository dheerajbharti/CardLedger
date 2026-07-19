package com.dheerajbharti.cardledger.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dheerajbharti.cardledger.data.SubscriptionDetector
import com.dheerajbharti.cardledger.databinding.ItemSubscriptionBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SubscriptionAdapter : RecyclerView.Adapter<SubscriptionAdapter.ViewHolder>() {
    private val items = mutableListOf<SubscriptionDetector.SubscriptionInsight>()
    private var privacyMode = false

    fun submitList(rows: List<SubscriptionDetector.SubscriptionInsight>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    fun setPrivacyMode(enabled: Boolean) {
        privacyMode = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemSubscriptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], privacyMode)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val binding: ItemSubscriptionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SubscriptionDetector.SubscriptionInsight, privacyMode: Boolean) {
            binding.subscriptionMerchant.text = privacyAware(item.merchant, privacyMode)
            binding.subscriptionMeta.text = privacyAware(
                "${item.occurrences} recurring payments  •  Next around ${DATE_FORMAT.format(Instant.ofEpochMilli(item.nextExpectedEpochMillis).atZone(ZONE))}",
                privacyMode
            )
            binding.subscriptionAmount.text = privacyAware(
                item.typicalInrAmount?.let { "Typical ${TransactionAdapter.formatInr(it.toPlainString())}" }
                    ?: "Amount varies",
                privacyMode
            )
            binding.subscriptionAnnual.text = privacyAware(
                item.estimatedAnnualInr?.let { "About ${TransactionAdapter.formatInr(it.toPlainString())} per year" }
                    ?: "Annual estimate unavailable",
                privacyMode
            )
        }
    }

    companion object {
        private val ZONE = ZoneId.of("Asia/Kolkata")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)
        private fun privacyAware(text: String, enabled: Boolean): String =
            if (enabled) text.replace(Regex("\\d"), "•") else text
    }
}
