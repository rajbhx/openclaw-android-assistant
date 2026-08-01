package com.codex.mobile

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Renders the agent picker list. Each row shows the agent's avatar, name,
 * tagline, category and a status chip (Active / Ready / Coming soon).
 */
class AgentAdapter(
    private val agents: List<Agent>,
    activeAgentId: String,
    private val onClick: (Agent) -> Unit,
) : RecyclerView.Adapter<AgentAdapter.ViewHolder>() {

    private var activeAgentId: String = activeAgentId

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: TextView = itemView.findViewById(R.id.agentAvatar)
        val name: TextView = itemView.findViewById(R.id.agentName)
        val tagline: TextView = itemView.findViewById(R.id.agentTagline)
        val category: TextView = itemView.findViewById(R.id.agentCategory)
        val chip: TextView = itemView.findViewById(R.id.agentChip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_agent_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = agents.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val agent = agents[position]
        val context = holder.itemView.context

        holder.avatar.text = agent.name.first().uppercaseChar().toString()
        holder.avatar.backgroundTintList = ColorStateList.valueOf(
            context.getColor(agent.colorRes)
        )
        holder.name.text = agent.name
        holder.tagline.text = agent.tagline
        holder.category.text = agent.category

        val isActive = agent.id == activeAgentId
        when {
            isActive -> {
                holder.chip.text = context.getString(R.string.agents_chip_active)
                holder.chip.backgroundTintList =
                    ColorStateList.valueOf(context.getColor(R.color.chip_active_bg))
                holder.chip.setTextColor(context.getColor(R.color.chip_active_fg))
            }
            agent.bundled -> {
                holder.chip.text = context.getString(R.string.agents_chip_ready)
                holder.chip.backgroundTintList =
                    ColorStateList.valueOf(context.getColor(R.color.chip_ready_bg))
                holder.chip.setTextColor(context.getColor(R.color.chip_ready_fg))
            }
            else -> {
                holder.chip.text = context.getString(R.string.agents_chip_soon)
                holder.chip.backgroundTintList =
                    ColorStateList.valueOf(context.getColor(R.color.chip_soon_bg))
                holder.chip.setTextColor(context.getColor(R.color.chip_soon_fg))
            }
        }

        holder.itemView.setOnClickListener { onClick(agent) }
    }

    fun updateActiveAgent(newActiveId: String) {
        activeAgentId = newActiveId
        notifyDataSetChanged()
    }
}
