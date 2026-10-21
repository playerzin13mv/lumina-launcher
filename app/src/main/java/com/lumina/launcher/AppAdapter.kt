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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class AppItem(val label: String, val component: ComponentName, val icon: Drawable) {
    val key: String get() = component.flattenToString()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

class AppAdapter(
    var showLabels: Boolean,
    var iconDp: Int,
    private val onClick: (AppItem, View) -> Unit,
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
            tag = "app"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, c.dp(8), 0, c.dp(8))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val icon = ImageView(c)
        val label = TextView(c).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.parseColor("#66000000"))
            setPadding(c.dp(2), c.dp(6), c.dp(2), 0)
        }
        box.addView(icon, LinearLayout.LayoutParams(c.dp(iconDp), c.dp(iconDp)))
        box.addView(label)
        return VH(box, icon, label)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        val c = holder.box.context
        holder.icon.layoutParams = LinearLayout.LayoutParams(c.dp(iconDp), c.dp(iconDp))
        holder.icon.setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
        holder.label.text = app.label
        holder.label.visibility = if (showLabels) View.VISIBLE else View.GONE
        holder.box.setOnClickListener { onClick(app, holder.box) }
        holder.box.setOnLongClickListener {
            onLong(app, holder.box)
            true
        }
    }
}

/** Páginas da tela inicial: cada página é uma grade de apps. */
class PagesAdapter(
    private val cols: Int,
    private val onClick: (AppItem, View) -> Unit,
    private val onLong: (AppItem, View) -> Unit,
    private val config: () -> Pair<Int, Boolean>
) : RecyclerView.Adapter<PagesAdapter.VH>() {

    var pages: List<List<AppItem>> = listOf(emptyList())
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(val rv: RecyclerView) : RecyclerView.ViewHolder(rv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val rv = RecyclerView(c).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = object : GridLayoutManager(c, cols) {
                override fun canScrollVertically() = false
            }
            overScrollMode = View.OVER_SCROLL_NEVER
            val (size, labels) = config()
            adapter = AppAdapter(labels, size, onClick, onLong)
        }
        return VH(rv)
    }

    override fun getItemCount(): Int = pages.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = holder.rv.adapter as AppAdapter
        val (size, labels) = config()
        a.iconDp = size
        a.showLabels = labels
        a.items = pages[position]
    }
}
