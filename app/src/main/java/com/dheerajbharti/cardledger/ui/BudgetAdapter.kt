package com.dheerajbharti.cardledger.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dheerajbharti.cardledger.data.SpendingCategory
import com.dheerajbharti.cardledger.databinding.ItemBudgetBinding
import java.math.BigDecimal
import java.math.RoundingMode

class BudgetAdapter(
    private val onClick: (BudgetRow) -> Unit
) : RecyclerView.Adapter<BudgetAdapter.ViewHolder>() {
    data class BudgetRow(
        val category: SpendingCategory,
        val spent: BigDecimal,
        val limit: BigDecimal?
    )

    private val items = mutableListOf<BudgetRow>()
    private var privacyMode = false

    fun submitList(rows: List<BudgetRow>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    fun setPrivacyMode(enabled: Boolean) {
        privacyMode = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemBudgetBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onClick
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], privacyMode)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemBudgetBinding,
        private val onClick: (BudgetRow) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BudgetRow, privacyMode: Boolean) {
            binding.categoryName.text = "${row.category.emoji}  ${row.category.label}"
            val limit = row.limit
            val percentage = if (limit != null && limit > BigDecimal.ZERO) {
                row.spent.multiply(BigDecimal("100"))
                    .divide(limit, 0, RoundingMode.HALF_UP)
                    .toInt()
                    .coerceIn(0, 200)
            } else 0
            binding.budgetProgress.progress = percentage.coerceAtMost(100)
            val status = if (limit == null) {
                "Spent ${TransactionAdapter.formatInr(row.spent.toPlainString())}  •  Tap to set budget"
            } else {
                val remaining = limit.subtract(row.spent)
                "${TransactionAdapter.formatInr(row.spent.toPlainString())} of ${TransactionAdapter.formatInr(limit.toPlainString())}" +
                    if (remaining >= BigDecimal.ZERO) {
                        "  •  ${TransactionAdapter.formatInr(remaining.toPlainString())} left"
                    } else {
                        "  •  ${TransactionAdapter.formatInr(remaining.abs().toPlainString())} over"
                    }
            }
            binding.budgetStatus.text = privacyAware(status, privacyMode)
            binding.budgetPercent.text = if (limit == null) "SET" else privacyAware("$percentage%", privacyMode)
            binding.root.setOnClickListener { onClick(row) }
        }
    }

    companion object {
        private fun privacyAware(text: String, enabled: Boolean): String =
            if (enabled) text.replace(Regex("\\d"), "•") else text
    }
}
