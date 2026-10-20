package com.lumina.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppItem(val label: String, val component: ComponentName, val icon: Drawable) {
    val key: String get() = component.flattenToString()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

class AppAdapter(
    private val showLabels: Boolean,
    private val onClick: (AppItem) -> Unit,
    private val onLong: (AppItem, View) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    var items: List<AppItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(val box: LinearLayout, val icon: ImageView, val label: TextView) :
        RecyclerView.ViewHolder(box)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, c.dp(8), 0, c.dp(8))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val icon = ImageView(c).apply {
            layoutParams = LinearLayout.LayoutParams(c.dp(56), c.dp(56))
        }
        val label = TextView(c).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.parseColor("#88000000"))
            setPadding(c.dp(2), c.dp(6), c.dp(2), 0)
            visibility = if (showLabels) View.VISIBLE else View.GONE
        }
        box.addView(icon)
        box.addView(label)
        return VH(box, icon, label)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.icon.setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
        holder.label.text = app.label
        holder.box.setOnClickListener { onClick(app) }
        holder.box.setOnLongClickListener {
            onLong(app, holder.box)
            true
        }
    }
}
