package com.lumina.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
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
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerList: RecyclerView
    private lateinit var search: EditText
    private lateinit var favAdapter: AppAdapter
    private lateinit var dockAdapter: AppAdapter
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var detector: GestureDetector

    private var apps: List<AppItem> = emptyList()
    private var drawerOpen = false
    private val prefs by lazy { getSharedPreferences("lumina", MODE_PRIVATE) }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

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
                if (!drawerOpen && vy < -900 && dy < -min) openDrawer(false)
                else if (!drawerOpen && vy > 900 && dy > min) expandNotifications()
                else if (drawerOpen && vy > 1500 && dy > min && !drawerList.canScrollVertically(-1)) closeDrawer()
                return false
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
        closeDrawer()
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

    private fun buildUi() {
        root = FrameLayout(this)

        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 76f)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
            setShadowLayer(10f, 0f, 2f, Color.parseColor("#55000000"))
            setOnLongClickListener { pickWallpaper(); true }
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER_HORIZONTAL
            setShadowLayer(8f, 0f, 1f, Color.parseColor("#55000000"))
        }
        home.addView(clock, LinearLayout.LayoutParams(-1, -2))
        home.addView(date, LinearLayout.LayoutParams(-1, -2))

        favAdapter = AppAdapter(true, ::launch, ::showMenu)
        val favList = RecyclerView(this).apply {
            layoutManager = object : GridLayoutManager(this@MainActivity, 4) {
                override fun canScrollVertically() = false
            }
            adapter = favAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        home.addView(favList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) })

        home.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val pill = TextView(this).apply {
            text = "Pesquisar"
            setTextColor(Color.parseColor("#DDFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            background = rounded(Color.parseColor("#44FFFFFF"), 26)
            setOnClickListener { openDrawer(true) }
        }
        home.addView(pill, LinearLayout.LayoutParams(-1, dp(52)).apply {
            bottomMargin = dp(12)
            leftMargin = dp(4); rightMargin = dp(4)
        })

        dockAdapter = AppAdapter(false, ::launch, ::showMenu)
        val dockList = RecyclerView(this).apply {
            layoutManager = object : GridLayoutManager(this@MainActivity, 4) {
                override fun canScrollVertically() = false
            }
            adapter = dockAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            background = rounded(Color.parseColor("#33FFFFFF"), 32)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        home.addView(dockList, LinearLayout.LayoutParams(-1, -2))

        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        // Gaveta de apps
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val r = dp(32).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE16161A"))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            visibility = View.GONE
            isClickable = true
        }
        search = EditText(this).apply {
            hint = "Pesquisar apps"
            setHintTextColor(Color.parseColor("#99FFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(20), 0, dp(20), 0)
            background = rounded(Color.parseColor("#22FFFFFF"), 26)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filterApps(s?.toString().orEmpty()) }
            })
            setOnEditorActionListener { _, _, _ ->
                drawerAdapter.items.firstOrNull()?.let { launch(it) }
                true
            }
        }
        drawer.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(20); leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(8)
        })

        drawerAdapter = AppAdapter(true, ::launch, ::showMenu)
        drawerList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = drawerAdapter
            clipToPadding = false
            setPadding(dp(12), 0, dp(12), 0)
        }
        drawer.addView(drawerList, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(drawer, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(48) })
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            home.setPadding(dp(16), bars.top + dp(32), dp(16), bars.bottom + dp(12))
            drawer.setPadding(0, 0, 0, maxOf(bars.bottom, ime.bottom))
            insets
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
        writeList("favs", apps.map { it.key }.filter { it !in dock }.take(8))
    }

    private fun refresh() {
        val map = apps.associateBy { it.key }
        dockAdapter.items = readList("dock").mapNotNull { map[it] }
        favAdapter.items = readList("favs").mapNotNull { map[it] }
        filterApps(if (::search.isInitialized) search.text.toString() else "")
    }

    private fun filterApps(q: String) {
        drawerAdapter.items =
            if (q.isBlank()) apps else apps.filter { it.label.contains(q.trim(), ignoreCase = true) }
    }

    private fun launch(app: AppItem) {
        val i = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = app.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        try {
            startActivity(i)
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
                    else if (l.size >= 4) Toast.makeText(this, "A dock já tem 4 apps", Toast.LENGTH_SHORT).show()
                    else l.add(app.key)
                    writeList("dock", l)
                    refresh()
                }
                3 -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.component.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    // ---------- Gaveta ----------

    private fun openDrawer(focusSearch: Boolean) {
        if (drawerOpen) return
        drawerOpen = true
        search.setText("")
        drawer.visibility = View.VISIBLE
        drawer.translationY = root.height.toFloat()
        drawer.animate().translationY(0f).setDuration(260).withEndAction {
            if (focusSearch) {
                search.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }
        }.start()
        home.animate().alpha(0f).setDuration(260).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        drawerList.scrollToPosition(0)
        drawer.animate().translationY(root.height.toFloat()).setDuration(220)
            .withEndAction { drawer.visibility = View.GONE }.start()
        home.animate().alpha(1f).setDuration(220).start()
    }

    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Exception) {
        }
    }

    private fun pickWallpaper() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Papel de parede"))
    }
}
