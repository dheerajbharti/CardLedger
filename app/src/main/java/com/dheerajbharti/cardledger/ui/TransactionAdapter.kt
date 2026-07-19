package com.dheerajbharti.cardledger.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dheerajbharti.cardledger.data.CardTransaction
import com.dheerajbharti.cardledger.data.InrAmountSource
import com.dheerajbharti.cardledger.databinding.ItemTransactionBinding
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

class TransactionAdapter : RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {
    private val items = mutableListOf<CardTransaction>()

    fun submitList(newItems: List<CardTransaction>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransactionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemTransactionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CardTransaction) {
            binding.merchant.text = item.merchant
            binding.amount.text = if (item.currency.equals("INR", ignoreCase = true)) {
                formatInr(item.amount)
            } else {
                formatMoney(item.currency, item.amount)
            }
            binding.dateAndCard.text = "${formatDate(item.transactionEpochMillis)}  •  Card XX${item.cardLast4}"
            binding.limit.text = item.availableLimitInr?.let {
                "Available limit after alert: ${formatInr(it)}"
            }.orEmpty()

            when (item.inrAmountSource) {
                InrAmountSource.AVAILABLE_LIMIT_DIFFERENCE -> {
                    binding.inrEquivalent.visibility = View.VISIBLE
                    binding.inrEquivalent.text = "Estimated INR debit: ${formatInr(item.inrAmount!!)}"
                }
                InrAmountSource.UNAVAILABLE -> {
                    if (item.currency.equals("INR", ignoreCase = true)) {
                        binding.inrEquivalent.visibility = View.GONE
                    } else {
                        binding.inrEquivalent.visibility = View.VISIBLE
                        binding.inrEquivalent.text = "INR debit estimate unavailable"
                    }
                }
                InrAmountSource.EXACT_INR_ALERT -> binding.inrEquivalent.visibility = View.GONE
            }
        }

        private fun formatDate(epochMillis: Long): String {
            return DATE_FORMAT.format(
                Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Asia/Kolkata"))
            )
        }
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)

        fun formatMoney(currencyCode: String, amount: String): String {
            val fractionDigits = runCatching {
                Currency.getInstance(currencyCode).defaultFractionDigits
            }.getOrDefault(2).coerceAtLeast(0)
            return "$currencyCode ${formatDecimal(amount, fractionDigits)}"
        }

        fun formatInr(amount: String): String = "₹${formatDecimal(amount, 2)}"

        fun formatDecimal(amount: String, fractionDigits: Int): String {
            val symbols = DecimalFormatSymbols(Locale.ENGLISH)
            val pattern = buildString {
                append("#,##0")
                if (fractionDigits > 0) {
                    append('.')
                    repeat(fractionDigits) { append('0') }
                }
            }
            return DecimalFormat(pattern, symbols).format(BigDecimal(amount))
        }
    }
}
