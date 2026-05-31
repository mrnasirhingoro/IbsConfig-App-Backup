package com.ibs.configapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ibs.configapp.R
import com.ibs.configapp.databinding.ItemPermissionBinding
import com.ibs.configapp.util.PermissionChecker
import com.ibs.configapp.util.PermissionType

data class PermissionRow(
    val type: PermissionType,
    val title: String,
    val guide: String
)

class PermissionAdapter(
    private val items: List<PermissionRow>,
    private val onGrantClick: (PermissionType) -> Unit
) : RecyclerView.Adapter<PermissionAdapter.ViewHolder>() {

    var onStatusChanged: (() -> Unit)? = null

    inner class ViewHolder(private val binding: ItemPermissionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PermissionRow) {
            binding.tvPermissionName.text = item.title
            binding.tvPermissionGuide.text = item.guide
            val granted = PermissionChecker.isGranted(binding.root.context, item.type)
            binding.ivStatus.setImageResource(
                if (granted) R.drawable.ic_granted else R.drawable.ic_pending
            )
            binding.ivStatus.contentDescription = binding.root.context.getString(
                if (granted) R.string.granted else R.string.not_granted
            )
            if (granted) {
                binding.btnGrant.visibility = View.GONE
            } else {
                binding.btnGrant.visibility = View.VISIBLE
                binding.btnGrant.text = binding.root.context.getString(R.string.grant)
                binding.btnGrant.setOnClickListener { onGrantClick(item.type) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPermissionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun refreshAll() {
        notifyDataSetChanged()
        onStatusChanged?.invoke()
    }
}
