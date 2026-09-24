package com.smartkeyboard.ime.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Emoji panel loaded from assets/emoji.json with categories + recently used.
 * Fully theme-aware (Light / Dark / Monet) via colors passed from the IME.
 */
class EmojiPanelController(private val context: Context) {

    private val categories = linkedMapOf<String, List<EmojiEntry>>()
    private val recentPrefs = context.getSharedPreferences("smart_kb_emoji", Context.MODE_PRIVATE)
    private val recent = ArrayDeque<String>(MAX_RECENT)

    data class EmojiEntry(val emoji: String, val name: String)

    data class PanelColors(
        val bg: Int,
        val surface: Int,
        val text: Int,
        val muted: Int,
        val primary: Int
    )

    init {
        loadAssets()
        loadRecent()
    }

    fun createEmojiPanel(
        onEmojiSelected: (String) -> Unit,
        onBack: () -> Unit,
        onDelete: (() -> Unit)? = null,
        colors: PanelColors = defaultColors(),
        bottomOffsetPx: Int = 0
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.bg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Match letter-keyboard gap from navigation bar
            setPadding(0, 0, 0, bottomOffsetPx)
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        header.addView(TextView(context).apply {
            text = "Emoji"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colors.text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(header)

        // Category tabs
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val catNames = listOf("Terbaru") + categories.keys.toList()
        var selectedTab: TextView? = null

        fun styleTab(tv: TextView, selected: Boolean) {
            tv.setTextColor(if (selected) 0xFFFFFFFF.toInt() else colors.text)
            tv.background = rounded(if (selected) colors.primary else colors.surface, 20f)
        }

        catNames.forEachIndexed { index, title ->
            val tab = TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    selectedTab?.let { styleTab(it, false) }
                    styleTab(this, true)
                    selectedTab = this
                    if (title == "Terbaru") showRecent(contentContainer, onEmojiSelected, colors)
                    else showCategory(contentContainer, title, onEmojiSelected, colors)
                }
            }
            styleTab(tab, index == 0)
            if (index == 0) selectedTab = tab
            tabs.addView(tab)
            if (index == 0) showRecent(contentContainer, onEmojiSelected, colors)
        }
        tabScroll.addView(tabs)
        root.addView(tabScroll)
        root.addView(contentContainer)

        // Bottom: Hapus + Kembali — same height, weight, rounded Material 3 style
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }

        val keyH = dp(48)
        bottomRow.addView(makeActionKey(
            label = "⌫",
            contentDesc = "Hapus",
            weight = 1f,
            height = keyH,
            colors = colors,
            marginEnd = dp(6)
        ).also { key ->
            key.setOnTouchListener(repeatTouchListener(key, colors) { onDelete?.invoke() })
        })

        bottomRow.addView(makeActionKey(
            label = "ABC",
            contentDesc = "Kembali ke keyboard",
            weight = 2f,
            height = keyH,
            colors = colors,
            marginStart = dp(6)
        ).also { key ->
            key.setOnClickListener { onBack() }
        })

        root.addView(bottomRow)
        return root
    }

    private fun makeActionKey(
        label: String,
        contentDesc: String,
        weight: Float,
        height: Int,
        colors: PanelColors,
        marginStart: Int = 0,
        marginEnd: Int = 0
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(colors.text)
            background = rounded(colors.surface, 14f)
            contentDescription = contentDesc
            isClickable = true
            isFocusable = false
            stateListAnimator = null
            isSoundEffectsEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, height, weight).apply {
                this.marginStart = marginStart
                this.marginEnd = marginEnd
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun repeatTouchListener(
        view: TextView,
        colors: PanelColors,
        action: () -> Unit
    ): View.OnTouchListener {
        val handler = Handler(Looper.getMainLooper())
        var repeating = false
        val repeater = object : Runnable {
            override fun run() {
                if (repeating) {
                    action()
                    handler.postDelayed(this, 50)
                }
            }
        }
        return View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.background = rounded(colors.primary, 14f)
                    (v as? TextView)?.setTextColor(0xFFFFFFFF.toInt())
                    action()
                    repeating = true
                    handler.removeCallbacks(repeater)
                    handler.postDelayed(repeater, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeating = false
                    handler.removeCallbacks(repeater)
                    v.background = rounded(colors.surface, 14f)
                    (v as? TextView)?.setTextColor(colors.text)
                    true
                }
                else -> true
            }
        }
    }

    private fun onPick(emoji: String, onEmojiSelected: (String) -> Unit) {
        remember(emoji)
        onEmojiSelected(emoji)
    }

    private fun showRecent(
        container: LinearLayout,
        onEmojiSelected: (String) -> Unit,
        colors: PanelColors
    ) {
        container.removeAllViews()
        val list = if (recent.isEmpty()) {
            categories["Sering"]?.map { it.emoji } ?: emptyList()
        } else {
            recent.toList()
        }
        container.addView(buildGrid(list, colors) { onPick(it, onEmojiSelected) })
    }

    private fun showCategory(
        container: LinearLayout,
        category: String,
        onEmojiSelected: (String) -> Unit,
        colors: PanelColors
    ) {
        container.removeAllViews()
        val list = categories[category]?.map { it.emoji } ?: return
        container.addView(buildGrid(list, colors) { onPick(it, onEmojiSelected) })
    }

    private fun buildGrid(
        emojis: List<String>,
        colors: PanelColors,
        onClick: (String) -> Unit
    ): ScrollView {
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220)
            )
        }
        val grid = GridLayout(context).apply {
            columnCount = 6
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        emojis.forEach { emoji ->
            grid.addView(TextView(context).apply {
                text = emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = rounded(colors.surface, 12f)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(44)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                isClickable = true
                setOnClickListener { onClick(emoji) }
            })
        }
        scroll.addView(grid)
        return scroll
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp,
                context.resources.displayMetrics
            )
        }
    }

    private fun defaultColors(): PanelColors {
        // Dark fallback; caller should pass theme-aware colors
        return PanelColors(
            bg = 0xFF1C1C1E.toInt(),
            surface = 0xFF2C2C2E.toInt(),
            text = 0xFFFFFFFF.toInt(),
            muted = 0xFFAEAEB2.toInt(),
            primary = 0xFF0A84FF.toInt()
        )
    }

    private fun remember(emoji: String) {
        recent.remove(emoji)
        recent.addFirst(emoji)
        while (recent.size > MAX_RECENT) recent.removeLast()
        recentPrefs.edit().putString("recent", recent.joinToString("|")).apply()
    }

    private fun loadRecent() {
        recent.clear()
        recentPrefs.getString("recent", null)?.split("|")?.filter { it.isNotEmpty() }?.forEach {
            recent.addLast(it)
        }
    }

    private fun loadAssets() {
        try {
            val json = context.assets.open("emoji.json").bufferedReader().readText()
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val cat = keys.next()
                val arr = obj.getJSONArray(cat)
                val list = mutableListOf<EmojiEntry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("n", "").ifEmpty { o.optString("k", "") }
                    list.add(EmojiEntry(o.getString("e"), name))
                }
                categories[cat] = list
            }
        } catch (e: Exception) {
            Log.w("EmojiPanel", "loadAssets failed", e)
            categories["Sering"] = listOf(
                EmojiEntry("😀", "senyum"), EmojiEntry("😂", "tertawa"),
                EmojiEntry("❤️", "hati"), EmojiEntry("👍", "bagus")
            )
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val MAX_RECENT = 32
    }
}
