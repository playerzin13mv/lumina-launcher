package com.lumina.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class MainActivity : Activity() {

    companion object {
        const val COLS = 5          // colunas da grade (tela inicial e gaveta)
        const val DOCK_MAX = 5
        const val BLUR_MAX = 90
    }

    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var pager: RecyclerView
    private lateinit var snap: PagerSnapHelper
    private lateinit var dots: LinearLayout
    private lateinit var dockList: RecyclerView
    private lateinit var drawer: LinearLayout
    private lateinit var drawerList: RecyclerView
    private lateinit var search: EditText
    private lateinit var pagesAdapter: PagesAdapter
    private lateinit var dockAdapter: AppAdapter
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var detector: GestureDetector

    private var apps: List<AppItem> = emptyList()
    private var drawerOpen = false
    private var lastPageSize = 25
    private var lastDot = -1
    private var lastDotCount = -1
    private val prefs by lazy { getSharedPreferences("lumina", MODE_PRIVATE) }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    private fun iconDp() = prefs.getInt("iconDp", 56)
    private fun labelsOn() = prefs.getBoolean("labels", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        buildUi()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, pkgReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) < abs(dx)) return false
                val min = dp(80)
                if (!drawerOpen && vy < -900 && dy < -min) {
                    openDrawer()
                } else if (!drawerOpen && vy > 900 && dy > min) {
                    // One UI 7: topo-esquerdo = notificações, topo-direito = configurações rápidas
                    if (e1.x > root.width / 2f) expandPanel(true) else expandPanel(false)
                } else if (drawerOpen && vy > 1500 && dy > min && !drawerList.canScrollVertically(-1)) {
                    closeDrawer()
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (!drawerOpen && !isOverApp(root, e.rawX, e.rawY)) showHomeSettings()
            }
        })

        loadApps()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (drawerOpen) closeDrawer()
        else pager.smoothScrollToPosition(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
    }

    // ---------- UI ----------

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun isOverApp(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val inside = x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
        if (!inside) return false
        if (v.tag == "app") return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (isOverApp(v.getChildAt(i), x, y)) return true
        }
        return false
    }

    private fun buildUi() {
        root = FrameLayout(this)

        // ----- Tela inicial: páginas + indicador + dock -----
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        pagesAdapter = PagesAdapter(COLS, ::launch, ::showMenu) { Pair(iconDp(), labelsOn()) }
        pager = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            adapter = pagesAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        snap = PagerSnapHelper()
        snap.attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateDots(false)
            }
        })
        pager.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot && apps.isNotEmpty()) {
                pager.post { if (pageSize() != lastPageSize) refresh() }
            }
        }
        home.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))

        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        home.addView(dots, LinearLayout.LayoutParams(-1, dp(16)).apply { bottomMargin = dp(8) })

        dockAdapter = AppAdapter(false, iconDp(), ::launch, ::showMenu)
        dockList = RecyclerView(this).apply {
            layoutManager = object : GridLayoutManager(this@MainActivity, DOCK_MAX) {
                override fun canScrollVertically() = false
            }
            adapter = dockAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            background = rounded(Color.parseColor("#40FFFFFF"), 30)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        home.addView(dockList, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(4); rightMargin = dp(4)
        })

        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        // ----- Tela de apps: rolagem vertical, busca embaixo, fundo desfocado -----
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#66000000"))
            visibility = View.GONE
            isClickable = true
        }
        drawerAdapter = AppAdapter(labelsOn(), iconDp(), ::launch, ::showMenu)
        drawerList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, COLS)
            adapter = drawerAdapter
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        drawer.addView(drawerList, LinearLayout.LayoutParams(-1, 0, 1f))

        val searchIcon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_search)
            ?.mutate()?.apply { setTint(Color.WHITE) }
        search = EditText(this).apply {
            hint = "Pesquisar"
            setHintTextColor(Color.parseColor("#AAFFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(18), 0, dp(18), 0)
            compoundDrawablePadding = dp(12)
            setCompoundDrawablesRelativeWithIntrinsicBounds(searchIcon, null, null, null)
            background = rounded(Color.parseColor("#33FFFFFF"), 26)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterApps(s?.toString().orEmpty())
                }
            })
            setOnEditorActionListener { v, _, _ ->
                drawerAdapter.items.firstOrNull()?.let { launch(it, v) }
                true
            }
        }
        drawer.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply {
            leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(12)
        })

        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            home.setPadding(dp(12), bars.top + dp(16), dp(12), bars.bottom + dp(12))
            drawer.setPadding(0, bars.top + dp(16), 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun pageSize(): Int {
        val h = pager.height
        val itemH = dp(iconDp() + 16 + if (labelsOn()) 28 else 0)
        val rows = if (h <= 0) 5 else maxOf(1, h / itemH)
        return COLS * rows
    }

    private fun updateDots(force: Boolean) {
        val n = pagesAdapter.itemCount
        val lm = pager.layoutManager as LinearLayoutManager
        val cur = snap.findSnapView(lm)?.let { lm.getPosition(it) } ?: 0
        if (!force && cur == lastDot && n == lastDotCount) return
        lastDot = cur
        lastDotCount = n
        dots.removeAllViews()
        if (n <= 1) return
        for (i in 0 until n) {
            val d = View(this)
            d.background = rounded(if (i == cur) Color.WHITE else Color.parseColor("#77FFFFFF"), 4)
            dots.addView(d, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }
    }

    // ---------- Apps ----------

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    AppItem(
                        it.loadLabel(pm).toString(),
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                        it.loadIcon(pm)
                    )
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps = list
                seedIfNeeded()
                refresh()
            }
        }.start()
    }

    private fun readList(key: String): List<String> =
        prefs.getString(key, null)?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()

    private fun writeList(key: String, list: List<String>) {
        prefs.edit().putString(key, list.joinToString("|")).apply()
    }

    private fun seedIfNeeded() {
        if (prefs.contains("dock") || apps.isEmpty()) return
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        val dock = intents
            .mapNotNull { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }
            .mapNotNull { ai -> apps.firstOrNull { it.component.packageName == ai.packageName }?.key }
            .distinct()
        writeList("dock", dock)
        writeList("favs", apps.map { it.key }.filter { it !in dock }.take(15))
    }

    private fun refresh() {
        val size = iconDp()
        val labels = labelsOn()
        dockAdapter.iconDp = size
        drawerAdapter.iconDp = size
        drawerAdapter.showLabels = labels

        val map = apps.associateBy { it.key }
        val dockItems = readList("dock").mapNotNull { map[it] }
        (dockList.layoutManager as GridLayoutManager).spanCount = maxOf(1, dockItems.size)
        dockList.visibility = if (dockItems.isEmpty()) View.GONE else View.VISIBLE
        dockAdapter.items = dockItems

        val fav = readList("favs").mapNotNull { map[it] }
        lastPageSize = pageSize()
        val pages = fav.chunked(lastPageSize)
        pagesAdapter.pages = if (pages.isEmpty()) listOf(emptyList()) else pages

        filterApps(search.text.toString())
        pager.post { updateDots(true) }
    }

    private fun filterApps(q: String) {
        drawerAdapter.items =
            if (q.isBlank()) apps else apps.filter { it.label.contains(q.trim(), ignoreCase = true) }
    }

    private fun launch(app: AppItem, view: View) {
        val i = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = app.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        try {
            val opts = try {
                ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height).toBundle()
            } catch (_: Exception) {
                null
            }
            startActivity(i, opts)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show()
        }
        if (drawerOpen) closeDrawer()
    }

    private fun showMenu(app: AppItem, anchor: View) {
        val inHome = readList("favs").contains(app.key)
        val inDock = readList("dock").contains(app.key)
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, if (inHome) "Remover da tela inicial" else "Adicionar à tela inicial")
        pm.menu.add(0, 2, 1, if (inDock) "Remover da dock" else "Adicionar à dock")
        pm.menu.add(0, 3, 2, "Informações do app")
        pm.menu.add(0, 4, 3, "Desinstalar")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val l = readList("favs").toMutableList()
                    if (inHome) l.remove(app.key) else l.add(app.key)
                    writeList("favs", l)
                    refresh()
                }
                2 -> {
                    val l = readList("dock").toMutableList()
                    if (inDock) l.remove(app.key)
                    else if (l.size >= DOCK_MAX)
                        Toast.makeText(this, "A dock já tem $DOCK_MAX apps", Toast.LENGTH_SHORT).show()
                    else l.add(app.key)
                    writeList("dock", l)
                    refresh()
                }
                3 -> startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.component.packageName)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                4 -> startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            true
        }
        pm.show()
    }

    // ---------- Configurações da tela inicial (segurar em área vazia) ----------

    private fun showHomeSettings() {
        val options = arrayOf(
            "Papel de parede",
            "Tamanho dos ícones",
            if (labelsOn()) "Ocultar nomes dos apps" else "Mostrar nomes dos apps"
        )
        AlertDialog.Builder(this).setItems(options) { _, which ->
            when (which) {
                0 -> startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Papel de parede")
                )
                1 -> AlertDialog.Builder(this)
                    .setItems(arrayOf("Pequeno", "Médio", "Grande")) { _, w ->
                        prefs.edit().putInt("iconDp", intArrayOf(48, 56, 64)[w]).apply()
                        refresh()
                    }.show()
                2 -> {
                    prefs.edit().putBoolean("labels", !labelsOn()).apply()
                    refresh()
                }
            }
        }.show()
    }

    // ---------- Tela de apps ----------

    private fun setBlur(radius: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            try { window.setBackgroundBlurRadius(radius) } catch (_: Exception) {}
        }
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        if (search.text.isNotEmpty()) search.setText("")
        drawer.visibility = View.VISIBLE
        drawer.alpha = 0f
        drawer.translationY = dp(60).toFloat()
        drawer.animate().alpha(1f).translationY(0f).setDuration(260)
            .setUpdateListener { setBlur((it.animatedFraction * BLUR_MAX).toInt()) }
            .withEndAction { drawer.animate().setUpdateListener(null) }
            .start()
        home.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(260).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        drawer.animate().alpha(0f).translationY(dp(60).toFloat()).setDuration(220)
            .setUpdateListener { setBlur(((1f - it.animatedFraction) * BLUR_MAX).toInt()) }
            .withEndAction {
                drawer.animate().setUpdateListener(null)
                drawer.visibility = View.GONE
                setBlur(0)
            }.start()
        home.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun expandPanel(settings: Boolean) {
        try {
            val sb = getSystemService("statusbar")
            val cls = Class.forName("android.app.StatusBarManager")
            val name = if (settings) "expandSettingsPanel" else "expandNotificationsPanel"
            cls.getMethod(name).invoke(sb)
        } catch (_: Exception) {
            try {
                val sb = getSystemService("statusbar")
                Class.forName("android.app.StatusBarManager")
                    .getMethod("expandNotificationsPanel").invoke(sb)
            } catch (_: Exception) {
            }
        }
    }
}
