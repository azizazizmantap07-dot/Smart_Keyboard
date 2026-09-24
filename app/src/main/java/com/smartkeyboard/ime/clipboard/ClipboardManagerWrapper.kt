package com.smartkeyboard.ime.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent clipboard history with pin + delete.
 * Preview uses TextView + explicit colors so text is always visible.
 * Deleted items are not re-imported from system clipboard until it changes.
 */
class ClipboardManagerWrapper(private val context: Context) {

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val store = context.getSharedPreferences("smart_kb_clipboard", Context.MODE_PRIVATE)
    private val items = mutableListOf<ClipItem>()

    /** Primary clip suppressed after user deleted it from history. */
    private var suppressedPrimary: String? = null

    data class ClipItem(val id: Long, val text: String, var pinned: Boolean)

    private var nextId = 1L

    // Theme colors supplied by caller (keyboard palette)
    private var panelColorBg = COLOR_BG
    private var panelColorChip = COLOR_CHIP
    private var panelColorText = COLOR_TEXT
    private var panelColorMuted = COLOR_MUTED
    private var panelColorAccent = COLOR_ACCENT

    init {
        load()
    }

    /**
     * Theme-aware clipboard panel. Colors follow the same palette as the main keyboard
     * (resolveThemeColors / Monet). Corners match key radius (14dp).
     */
    fun createClipboardPanel(
        onPaste: (String) -> Unit,
        onBack: () -> Unit,
        colorBg: Int = COLOR_BG,
        colorChip: Int = COLOR_CHIP,
        colorText: Int = COLOR_TEXT,
        colorMuted: Int = COLOR_MUTED,
        colorAccent: Int = COLOR_ACCENT,
        bottomOffsetPx: Int = 0
    ): View {
        try {
            capturePrimaryClip()
        } catch (e: Exception) {
            android.util.Log.w("ClipboardWrap", "capturePrimaryClip", e)
        }

        // Stash for nested helpers
        panelColorBg = colorBg
        panelColorChip = colorChip
        panelColorText = colorText
        panelColorMuted = colorMuted
        panelColorAccent = colorAccent

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Match letter-keyboard gap from navigation bar (+ side padding)
            setPadding(dp(12), dp(12), dp(12), dp(12) + bottomOffsetPx)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(context).apply {
            text = "Clipboard"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(panelColorText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        val listHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refreshList() {
            listHost.removeAllViews()
            val ordered = items.sortedWith(
                compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.id }
            )
            if (ordered.isEmpty()) {
                listHost.addView(TextView(context).apply {
                    text = "Belum ada item clipboard"
                    setTextColor(panelColorMuted)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                })
            } else {
                ordered.forEach { item ->
                    listHost.addView(createRow(item, onPaste, onBack, ::refreshList))
                }
            }
        }

        headerRow.addView(makeActionChip("Hapus semua") {
            val primary = readPrimaryClip()
            if (primary != null) {
                suppressedPrimary = primary
                clearSystemClipboard()
            }
            items.clear()
            save()
            refreshList()
            Toast.makeText(context, "Clipboard dikosongkan", Toast.LENGTH_SHORT).show()
        })
        headerRow.addView(makeActionChip("Kembali", filled = true) { onBack() })
        root.addView(headerRow)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220)
            ).apply { topMargin = dp(8) }
        }
        scroll.addView(listHost)
        root.addView(scroll)

        // Tombol "Kembali" sudah ada di header atas — tidak perlu duplikat di bawah

        refreshList()
        return root
    }

    private fun createRow(
        item: ClipItem,
        onPaste: (String) -> Unit,
        onBack: () -> Unit,
        refresh: () -> Unit
    ): LinearLayout {
        val preview = formatPreview(item.text)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(4)) }

            addView(TextView(context).apply {
                text = preview
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextColor(panelColorText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = roundedChip(panelColorChip)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                minHeight = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onPaste(item.text)
                    onBack()
                }
            })

            addView(TextView(context).apply {
                text = if (item.pinned) "📌" else "📍"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = roundedChip(panelColorChip)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginStart = dp(4)
                }
                isClickable = true
                setOnClickListener {
                    item.pinned = !item.pinned
                    save()
                    refresh()
                }
            })

            addView(TextView(context).apply {
                text = "🗑"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = roundedChip(panelColorChip)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginStart = dp(4)
                }
                isClickable = true
                setOnClickListener {
                    val primary = readPrimaryClip()
                    if (primary != null && primary == item.text) {
                        suppressedPrimary = item.text
                        clearSystemClipboard()
                    }
                    items.removeAll { it.id == item.id }
                    save()
                    refresh()
                    Toast.makeText(context, "Dihapus", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun makeActionChip(
        label: String,
        filled: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (filled) Color.WHITE else panelColorText)
            background = roundedChip(if (filled) panelColorAccent else panelColorChip)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }
    }

    private fun readPrimaryClip(): String? {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val plain = item.text?.toString()
        if (!plain.isNullOrBlank()) return plain
        val coerced = item.coerceToText(context)?.toString()
        if (!coerced.isNullOrBlank()) return coerced
        val html = item.htmlText
        if (!html.isNullOrBlank()) {
            return html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        }
        return null
    }

    private fun formatPreview(text: String): String {
        val preview = text
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "(teks kosong)" }
        return if (preview.length > 60) preview.take(57) + "…" else preview
    }

    private fun clearSystemClipboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) { android.util.Log.w("ClipboardWrap", "op failed", e) }
    }

    /**
     * Capture current system clipboard into history.
     * @param fromUserCopy true when called from OnPrimaryClipChangedListener
     *        (user just copied) — allows re-adding text that was deleted from history.
     */
    fun capturePrimaryClip(fromUserCopy: Boolean = false) {
        try {
            val clipText = readPrimaryClip() ?: return
            addToHistory(clipText, allowResuppressed = fromUserCopy)
        } catch (e: Exception) { android.util.Log.w("ClipboardWrap", "op failed", e) }
    }

    private fun addToHistory(text: String, allowResuppressed: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        // After user deleted this text from history, don't re-import from stale system clip
        // unless they explicitly copied again (fromUserCopy / allowResuppressed).
        if (clean == suppressedPrimary || text == suppressedPrimary) {
            if (!allowResuppressed) return
            suppressedPrimary = null
        }
        if (looksLikeSecret(clean)) return
        // Already newest item — no-op (unless re-copy after delete, handled above)
        if (items.firstOrNull()?.text == text || items.firstOrNull()?.text == clean) return

        suppressedPrimary = null
        items.removeAll { it.text == text || it.text == clean }
        val stored = if (text.isNotBlank()) text else clean
        items.add(0, ClipItem(nextId++, stored, pinned = false))
        val pinned = items.filter { it.pinned }
        val unpinned = items.filter { !it.pinned }.take(MAX_HISTORY)
        items.clear()
        items.addAll(pinned + unpinned)
        save()
    }

    /** Only filter short pure OTP codes, not normal copied text. */
    private fun looksLikeSecret(s: String): Boolean {
        val t = s.trim()
        // 4–8 digit OTP only
        if (t.length in 4..8 && t.all { it.isDigit() }) return true
        return false
    }

    private fun load() {
        items.clear()
        try {
            val raw = store.getString("items", "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optLong("id", nextId++)
                if (id >= nextId) nextId = id + 1
                val t = o.optString("t", "")
                if (t.isNotEmpty()) {
                    items.add(ClipItem(id, t, o.optBoolean("p", false)))
                }
            }
        } catch (e: Exception) { android.util.Log.w("ClipboardWrap", "op failed", e) }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("t", it.text).put("p", it.pinned))
        }
        store.edit().putString("items", arr.toString()).apply()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }


    private fun roundedChip(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 14f, context.resources.displayMetrics
            )
        }
    }

    companion object {
        private const val MAX_HISTORY = 40
        private const val COLOR_BG = 0xFF1C1C1E.toInt()
        private const val COLOR_CHIP = 0xFF2C2C2E.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFFAEAEB2.toInt()
        private const val COLOR_ACCENT = 0xFF0A84FF.toInt()
    }
}
