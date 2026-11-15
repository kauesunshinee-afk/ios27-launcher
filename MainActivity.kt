package com.ioslauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

data class AppEntry(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val icon: Drawable,
    val category: String
)

class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var root: FrameLayout
    private lateinit var dotsRow: LinearLayout
    private lateinit var pager: Pager
    private lateinit var pagesRow: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var bottomBox: LinearLayout

    private var apps: List<AppEntry> = emptyList()
    private var overlay: View? = null
    private var topInset = 0
    private var bottomInset = 0
    private var pageCount = 1

    // ---------- Pager com "snap" de página ----------
    inner class Pager(ctx: Context) : HorizontalScrollView(ctx) {
        private var startPage = 0

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }

        fun currentPage(): Int = if (width == 0) 0 else (scrollX + width / 2) / width

        override fun fling(velocityX: Int) {}

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) startPage = currentPage()
            val r = super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                snapFrom(startPage)
            }
            return r
        }

        private fun snapFrom(start: Int) {
            val w = width
            if (w == 0) return
            val delta = scrollX - start * w
            val t = when {
                delta > w * 0.15f -> start + 1
                delta < -w * 0.15f -> start - 1
                else -> start
            }
            val target = t.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            smoothScrollTo(target * w, 0)
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            updateDots()
        }
    }

    // ---------- Ciclo de vida ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF1B2A6B.toInt(), 0xFF6A3FA0.toInt(), 0xFFE8728F.toInt())
        )

        dotsRow = LinearLayout(this).apply { gravity = Gravity.CENTER }

        pager = Pager(this)
        pagesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pager.addView(pagesRow, FrameLayout.LayoutParams(WRAP, MATCH))
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        val search = TextView(this).apply {
            text = "🔍  Buscar"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = glass(dp(18), 0x33)
            setPadding(dp(28), dp(7), dp(28), dp(7))
            setOnClickListener { showSearch() }
        }

        dock = LinearLayout(this).apply {
            background = glass(dp(34), 0x38)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(14), dp(10), dp(14))
        }

        bottomBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        bottomBox.addView(dotsRow, LinearLayout.LayoutParams(WRAP, dp(14)))
        bottomBox.addView(search, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
        })
        bottomBox.addView(dock, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(14), 0, dp(14), dp(10))
        })
        root.addView(bottomBox, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            val t = ins.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val b = ins.systemWindowInsetBottom
            if (t != topInset || b != bottomInset) {
                topInset = t
                bottomInset = b
                pager.setPadding(0, t, 0, 0)
                bottomBox.setPadding(0, 0, 0, b)
                root.post { rebuild() }
            }
            ins
        }

        setContentView(root)
        rebuild()
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            rebuild()
        }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(pkgReceiver, f)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(pkgReceiver)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hideOverlay()
        pager.smoothScrollTo(0, 0)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (overlay != null) hideOverlay() else pager.smoothScrollTo(0, 0)
    }

    // ---------- Dados ----------
    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { ri ->
                AppEntry(
                    ri.loadLabel(pm).toString(),
                    ri.activityInfo.packageName,
                    ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    ri.loadIcon(pm),
                    categoryOf(ri.activityInfo.applicationInfo)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun categoryOf(ai: ApplicationInfo): String = when (ai.category) {
        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
        ApplicationInfo.CATEGORY_GAME -> "Jogos"
        ApplicationInfo.CATEGORY_AUDIO,
        ApplicationInfo.CATEGORY_VIDEO,
        ApplicationInfo.CATEGORY_IMAGE -> "Entretenimento"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Produtividade"
        ApplicationInfo.CATEGORY_NEWS -> "Notícias"
        ApplicationInfo.CATEGORY_MAPS -> "Viagens"
        else -> "Utilidades"
    }

    private fun pickDock(): List<AppEntry> {
        val pm = packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        val out = mutableListOf<AppEntry>()
        for (i in intents) {
            val pkg = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName ?: continue
            val a = apps.firstOrNull { it.pkg == pkg } ?: continue
            if (out.none { it.pkg == a.pkg }) out.add(a)
        }
        if (out.isEmpty()) out.addAll(apps.take(4))
        return out
    }

    // ---------- Construção da tela ----------
    private fun rebuild() {
        apps = loadApps()
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val cellW = (w - dp(32)) / 4
        val icon = (cellW * 0.72f).toInt()
        val rowH = icon + dp(34)
        val reserved = topInset + bottomInset + dp(190)
        val rows = ((h - reserved) / rowH).coerceIn(3, 6)

        val dockApps = pickDock()
        val dockComps = dockApps.map { it.component }.toSet()
        val grid = apps.filter { it.component !in dockComps }

        pagesRow.removeAllViews()
        val cap0 = (rows - 2).coerceAtLeast(1) * 4
        val first = grid.take(cap0)
        val rest = grid.drop(cap0).chunked(rows * 4)

        pagesRow.addView(homePage(first, icon, rowH), LinearLayout.LayoutParams(w, MATCH))
        for (p in rest) pagesRow.addView(iconPage(p, icon, rowH), LinearLayout.LayoutParams(w, MATCH))
        pagesRow.addView(libraryPage(w), LinearLayout.LayoutParams(w, MATCH))

        dock.removeAllViews()
        for (a in dockApps) {
            dock.addView(appView(a, icon, false), LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        pageCount = pagesRow.childCount
        dotsRow.removeAllViews()
        repeat(pageCount) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                alpha = 0.4f
            }
            dotsRow.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        updateDots()
    }

    private fun updateDots() {
        val cur = pager.currentPage().coerceIn(0, (dotsRow.childCount - 1).coerceAtLeast(0))
        for (i in 0 until dotsRow.childCount) {
            dotsRow.getChildAt(i).alpha = if (i == cur) 1f else 0.4f
        }
    }

    private fun homePage(list: List<AppEntry>, icon: Int, rowH: Int): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        col.addView(widgetCard(), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })
        col.addView(buildGrid(list, 4, icon, rowH, true))
        return col
    }

    private fun iconPage(list: List<AppEntry>, icon: Int, rowH: Int): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        col.addView(buildGrid(list, 4, icon, rowH, true))
        return col
    }

    private fun widgetCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dp(30), 0x30)
            setPadding(dp(22), dp(16), dp(22), dp(18))
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d 'de' MMMM"
            format24Hour = "EEEE, d 'de' MMMM"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 15f
        }
        val time = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            textSize = 64f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        }
        card.addView(date)
        card.addView(time)
        return card
    }

    private fun libraryPage(w: Int): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        col.addView(TextView(this).apply {
            text = "Biblioteca de Apps"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })

        val tileW = (w - dp(32)) / 2 - dp(14)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val groups = apps.groupBy { it.category }.toSortedMap()
        for (pair in groups.entries.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (e in pair) {
                row.addView(categoryTile(e.key, e.value, tileW), LinearLayout.LayoutParams(tileW, WRAP).apply {
                    setMargins(dp(7), 0, dp(7), dp(14))
                })
            }
            inner.addView(row)
        }
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(200))
            addView(inner)
        }
        col.addView(sv, LinearLayout.LayoutParams(MATCH, MATCH))
        return col
    }

    private fun categoryTile(name: String, list: List<AppEntry>, tileW: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(12)
        val mini = (tileW - pad * 2 - dp(12)) / 2
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dp(26), 0x30)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { showFolder(name, list) }
        }
        for (r in 0..1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0..1) {
                val idx = r * 2 + c
                val lp = LinearLayout.LayoutParams(mini, mini).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                val cell: View = when {
                    idx == 3 && list.size > 4 -> TextView(this).apply {
                        text = "+${list.size - 3}"
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        background = glass((mini * 0.225f).toInt(), 0x40)
                    }
                    idx < list.size -> roundedIcon(list[idx], mini).apply {
                        setOnClickListener { launch(list[idx], it) }
                    }
                    else -> Space(this)
                }
                row.addView(cell, lp)
            }
            card.addView(row)
        }
        box.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(TextView(this).apply {
            text = name
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        return box
    }

    private fun buildGrid(list: List<AppEntry>, cols: Int, icon: Int, rowH: Int, labels: Boolean): LinearLayout {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (chunk in list.chunked(cols)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (a in chunk) row.addView(appView(a, icon, labels), LinearLayout.LayoutParams(0, rowH, 1f))
            repeat(cols - chunk.size) { row.addView(Space(this), LinearLayout.LayoutParams(0, rowH, 1f)) }
            g.addView(row, LinearLayout.LayoutParams(MATCH, rowH))
        }
        return g
    }

    // ---------- Ícones e células ----------
    private fun iconDrawable(d: Drawable, size: Int): Drawable {
        if (d is AdaptiveIconDrawable) {
            val bg = d.background
            val fg = d.foreground
            if (bg != null && fg != null) {
                val ld = LayerDrawable(
                    arrayOf(
                        bg.constantState?.newDrawable()?.mutate() ?: bg,
                        fg.constantState?.newDrawable()?.mutate() ?: fg
                    )
                )
                val inset = -(size / 4)
                ld.setLayerInset(0, inset, inset, inset, inset)
                ld.setLayerInset(1, inset, inset, inset, inset)
                return ld
            }
        }
        return d.constantState?.newDrawable()?.mutate() ?: d
    }

    private fun roundedIcon(a: AppEntry, size: Int): ImageView = ImageView(this).apply {
        setImageDrawable(iconDrawable(a.icon, size))
        scaleType = ImageView.ScaleType.FIT_XY
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.225f)
            }
        }
        if (a.icon !is AdaptiveIconDrawable) {
            setBackgroundColor(0xFF2C2C2E.toInt())
            val p = size / 9
            setPadding(p, p, p, p)
        }
    }

    private fun appView(a: AppEntry, size: Int, labels: Boolean): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        v.addView(roundedIcon(a, size), LinearLayout.LayoutParams(size, size))
        if (labels) {
            v.addView(TextView(this).apply {
                text = a.label
                textSize = 11f
                setTextColor(Color.WHITE)
                setShadowLayer(3f, 0f, 1f, 0x66000000)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(2), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        }
        v.setOnClickListener { launch(a, it) }
        v.setOnLongClickListener { showMenu(it, a); true }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        return v
    }

    private fun glass(radius: Int, alpha: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(Color.argb(alpha, 255, 255, 255))
        setStroke(dp(1), Color.argb(0x55, 255, 255, 255))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ---------- Ações ----------
    private fun launch(a: AppEntry, from: View) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(a.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
            startActivity(i, opts.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o app", Toast.LENGTH_SHORT).show()
        }
        hideOverlay()
    }

    private fun showMenu(v: View, a: AppEntry) {
        val m = PopupMenu(this, v)
        m.menu.add(0, 1, 0, "Informações do app")
        m.menu.add(0, 2, 1, "Desinstalar app")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.pkg, null))
                )
                2 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
            }
            true
        }
        m.show()
    }

    // ---------- Overlays (busca e pastas) ----------
    private fun showOverlay(content: View, gravity: Int) {
        hideOverlay()
        val o = FrameLayout(this).apply {
            setBackgroundColor(0xB0000000.toInt())
            isClickable = true
            alpha = 0f
            setOnClickListener { hideOverlay() }
        }
        o.addView(content, FrameLayout.LayoutParams(MATCH, WRAP, gravity).apply {
            setMargins(dp(24), topInset + dp(40), dp(24), bottomInset + dp(40))
        })
        root.addView(o, FrameLayout.LayoutParams(MATCH, MATCH))
        o.animate().alpha(1f).setDuration(180).start()
        overlay = o
    }

    private fun hideOverlay() {
        overlay?.let { root.removeView(it) }
        overlay = null
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun showFolder(name: String, list: List<AppEntry>) {
        val w = resources.displayMetrics.widthPixels
        val cellW = (w - dp(64)) / 4
        val icon = (cellW * 0.72f).toInt()
        val rowH = icon + dp(34)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        val card = ScrollView(this).apply {
            background = glass(dp(34), 0x40)
            isClickable = true
            setPadding(dp(10), dp(14), dp(10), dp(10))
        }
        card.addView(buildGrid(list, 4, icon, rowH, true))
        box.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        showOverlay(box, Gravity.CENTER)
    }

    private fun resultRow(a: AppEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        row.addView(roundedIcon(a, dp(44)), LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(TextView(this).apply {
            text = a.label
            setTextColor(Color.WHITE)
            textSize = 17f
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(14) })
        row.setOnClickListener { launch(a, it) }
        return row
    }

    private fun showSearch() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(this).apply {
            hint = "Buscar"
            setHintTextColor(0xAAFFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 17f
            isSingleLine = true
            background = glass(dp(22), 0x40)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(results) }
        box.addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                results.removeAllViews()
                val q = s?.toString()?.trim().orEmpty()
                if (q.isEmpty()) return
                apps.filter { it.label.contains(q, ignoreCase = true) }
                    .take(8)
                    .forEach { results.addView(resultRow(it)) }
            }
        })

        showOverlay(box, Gravity.TOP)
        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, 0)
        }, 150)
    }
}
