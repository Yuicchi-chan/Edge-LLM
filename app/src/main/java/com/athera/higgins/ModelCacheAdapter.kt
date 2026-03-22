package com.athera.higgins

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class CachedModel(
    val name: String,
    val uri: String
)

class ModelCacheAdapter(
    private val models: List<CachedModel>,
    private val onClick: (CachedModel) -> Unit,
) : RecyclerView.Adapter<ModelCacheAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cached_model, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = models[position]
        holder.name.text = model.name
        holder.uri.text = model.uri
        holder.itemView.setOnClickListener { onClick(model) }
    }

    override fun getItemCount(): Int = models.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.model_name)
        val uri: TextView = view.findViewById(R.id.model_uri)
    }
}
