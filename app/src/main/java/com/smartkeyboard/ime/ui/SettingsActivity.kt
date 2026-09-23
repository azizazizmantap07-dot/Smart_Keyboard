package com.smartkeyboard.ime.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.smartkeyboard.ime.R

import com.smartkeyboard.ime.translation.TranslationEngine
import com.smartkeyboard.ime.utils.PreferencesHelper
import kotlinx.coroutines.*
import android.widget.Toast

/**
 * Material 3 settings — minimal, neat, short labels.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesHelper
    private lateinit var translationEngine: TranslationEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesHelper(this)
        translationEngine = TranslationEngine(this)
        applyThemeMode(prefs.themeMode)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Top app bar ──
        val toolbar = MaterialToolbar(this).apply {
            title = "Smart Keyboard"
        }
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        scroll.addView(content)
        root.addView(scroll)

        // ── Sticky test typing area (always visible at bottom) ──
        val testCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(4), dp(12), dp(12))
            }
        }
        val testInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        testInner.addView(TextView(this).apply {
            text = "Uji ketik / tes layout"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.75f
            setPadding(0, 0, 0, dp(6))
        })
        val testInput = EditText(this).apply {
            hint = "Ketik di sini untuk menguji keyboard…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            isSingleLine = false
            // Prefer this app's IME when focusing
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        testInner.addView(testInput)
        testInner.addView(TextView(this).apply {
            text = "Atur slider di atas, lalu ketik di sini. Tutup–buka keyboard agar ukuran baru diterapkan."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = 0.6f
            setPadding(0, dp(6), 0, 0)
        })
        testCard.addView(testInner)
        root.addView(testCard)

        setContentView(root)

        // ── Setup ──
        content.addView(sectionLabel("Setup"))
        content.addView(card {
            addView(bodyText("Aktifkan di pengaturan sistem, lalu pilih sebagai keyboard default."))
            addView(MaterialButton(this@SettingsActivity).apply {
                text = "Buka pengaturan keyboard"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            })
        })

        // ── Size ──
        content.addView(sectionLabel("Ukuran & Jarak Tombol"))
        content.addView(card {
            addView(sliderRow(
                title = "Tinggi tombol",
                valueText = { "${prefs.keyboardHeightPercent}%" },
                valueFrom = PreferencesHelper.HEIGHT_MIN.toFloat(),
                valueTo = PreferencesHelper.HEIGHT_MAX.toFloat(),
                step = 5f,
                current = prefs.keyboardHeightPercent.toFloat(),
                onChange = { prefs.keyboardHeightPercent = it.toInt() }
            ))
            addView(divider())
            addView(sliderRow(
                title = "Lebar keyboard",
                valueText = { "${prefs.keyboardWidthPercent}%" },
                valueFrom = PreferencesHelper.WIDTH_MIN.toFloat(),
                valueTo = PreferencesHelper.WIDTH_MAX.toFloat(),
                step = 5f,
                current = prefs.keyboardWidthPercent.toFloat(),
                onChange = { prefs.keyboardWidthPercent = it.toInt() }
            ))
            addView(divider())
            addView(sliderRow(
                title = "Ukuran tombol / teks",
                valueText = { "${prefs.keySizePercent}%" },
                valueFrom = PreferencesHelper.KEY_SIZE_MIN.toFloat(),
                valueTo = PreferencesHelper.KEY_SIZE_MAX.toFloat(),
                step = 5f,
                current = prefs.keySizePercent.toFloat(),
                onChange = { prefs.keySizePercent = it.toInt() }
            ))
            addView(divider())
            addView(sliderRow(
                title = "Kerapatan (jarak antar tombol)",
                valueText = { "${prefs.keySpacingPercent}%" },
                valueFrom = PreferencesHelper.SPACING_MIN.toFloat(),
                valueTo = PreferencesHelper.SPACING_MAX.toFloat(),
                step = 10f,
                current = prefs.keySpacingPercent.toFloat(),
                onChange = { prefs.keySpacingPercent = it.toInt() }
            ))
            addView(divider())
            addView(sliderRow(
                title = "Jarak bawah",
                valueText = { "${prefs.keyboardBottomOffsetDp} dp" },
                valueFrom = PreferencesHelper.OFFSET_MIN.toFloat(),
                valueTo = PreferencesHelper.OFFSET_MAX.toFloat(),
                step = 2f,
                current = prefs.keyboardBottomOffsetDp.toFloat(),
                onChange = { prefs.keyboardBottomOffsetDp = it.toInt() }
            ))
            addView(hintText("Perubahan diterapkan saat keyboard dibuka ulang. Kerapatan 0% = rapat, 100% = default, lebih tinggi = lebih longgar."))
        })

        // ── Features ──
        content.addView(sectionLabel("Fitur"))
        content.addView(card {
            addView(switchRow("Terjemahan", "ID → EN saat mengetik", prefs.isTranslationModeEnabled) {
                prefs.isTranslationModeEnabled = it
            })
            addView(divider())
            addView(switchRow("Clipboard", "Riwayat salin-tempel", prefs.isClipboardEnabled) {
                prefs.isClipboardEnabled = it
            })
            addView(switchRow("Auto-koreksi", "Perbaiki typo otomatis (Trie + konteks)", prefs.isAutoCorrectEnabled) {
                prefs.isAutoCorrectEnabled = it
            })
            addView(divider())
            addView(switchRow("Getar", "Haptic pada tombol", prefs.isHapticEnabled) {
                prefs.isHapticEnabled = it
            })
            addView(divider())
            addView(switchRow("Suara", "Klik tombol", prefs.isSoundEnabled) {
                prefs.isSoundEnabled = it
            })
        })

        // ── Model Terjemahan (manual download) ──
        content.addView(sectionLabel("Model Terjemahan"))
        content.addView(card {
            addView(hintText("Unduh model bahasa yang kamu butuhkan (~30 MB per bahasa). Bahasa yang belum diunduh tidak bisa dipilih di keyboard."))

            val downloadAllBtn = MaterialButton(this@SettingsActivity).apply {
                text = "Unduh semua bahasa"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            addView(downloadAllBtn)

            val listHost = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(listHost)

            fun rebuildLangList() {
                listHost.removeAllViews()
                TranslationEngine.SUPPORTED_TARGETS.forEach { lang ->
                    val downloaded = translationEngine.isLanguageDownloaded(lang.code)
                    val row = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, dp(6))
                    }
                    val nameTv = TextView(this@SettingsActivity).apply {
                        text = buildString {
                            append(lang.labelId)
                            if (downloaded) append("  ✓")
                        }
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        alpha = if (downloaded) 1f else 0.7f
                    }
                    row.addView(nameTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    val btn = MaterialButton(
                        this@SettingsActivity,
                        null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle
                    ).apply {
                        text = if (downloaded) "Siap" else "Unduh"
                        isEnabled = !downloaded
                        minimumWidth = 0
                        minWidth = 0
                        setPadding(dp(12), 0, dp(12), 0)
                        setOnClickListener {
                            isEnabled = false
                            text = "…"
                            translationEngine.downloadModelFor(lang.code, switchTo = false) { success, message ->
                                runOnUiThread {
                                    if (success) {
                                        text = "Siap"
                                        isEnabled = false
                                        nameTv.text = "${lang.labelId}  ✓"
                                        nameTv.alpha = 1f
                                        Toast.makeText(
                                            this@SettingsActivity,
                                            message ?: "${lang.labelId} siap",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        text = "Unduh"
                                        isEnabled = true
                                        Toast.makeText(
                                            this@SettingsActivity,
                                            message ?: "Gagal mengunduh ${lang.labelId}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                    row.addView(btn)
                    listHost.addView(row)
                    listHost.addView(divider())
                }
            }
            rebuildLangList()

            downloadAllBtn.setOnClickListener {
                val pending = TranslationEngine.SUPPORTED_TARGETS.filter {
                    !translationEngine.isLanguageDownloaded(it.code)
                }
                if (pending.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "Semua bahasa sudah diunduh", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                downloadAllBtn.isEnabled = false
                downloadAllBtn.text = "Mengunduh 0/${pending.size}…"
                var done = 0
                var failed = 0
                fun next(i: Int) {
                    if (i >= pending.size) {
                        downloadAllBtn.isEnabled = true
                        downloadAllBtn.text = "Unduh semua bahasa"
                        rebuildLangList()
                        Toast.makeText(
                            this@SettingsActivity,
                            "Selesai: ${done - failed} berhasil" + if (failed > 0) ", $failed gagal" else "",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }
                    val lang = pending[i]
                    downloadAllBtn.text = "Mengunduh ${i + 1}/${pending.size}… ${lang.labelId}"
                    translationEngine.downloadModelFor(lang.code, switchTo = false) { success, _ ->
                        runOnUiThread {
                            done++
                            if (!success) failed++
                            next(i + 1)
                        }
                    }
                }
                next(0)
            }
        })

        // ── Theme ──
        content.addView(sectionLabel("Tema"))
        content.addView(card {
            val themeLabel = TextView(this@SettingsActivity).apply {
                text = themeName(prefs.themeMode)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
            val row = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(themeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Ganti"
                    setOnClickListener {
                        prefs.themeMode = when (prefs.themeMode) {
                            PreferencesHelper.THEME_SYSTEM -> PreferencesHelper.THEME_LIGHT
                            PreferencesHelper.THEME_LIGHT -> PreferencesHelper.THEME_DARK
                            else -> PreferencesHelper.THEME_SYSTEM
                        }
                        themeLabel.text = themeName(prefs.themeMode)
                        applyThemeMode(prefs.themeMode)
                    }
                })
            }
            addView(row)
            addView(hintText("Sistem mengikuti tema perangkat (Monet)."))
        })
    }

    // ── UI helpers ──────────────────────────────────────────────

    private fun themeName(mode: String) = when (mode) {
        PreferencesHelper.THEME_LIGHT -> "Terang"
        PreferencesHelper.THEME_DARK -> "Gelap"
        else -> "Sistem"
    }

    private fun sectionLabel(text: String): TextView {
        val tv = TextView(this)
        tv.text = text.uppercase()
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setPadding(dp(4), dp(20), dp(4), dp(8))
        val typed = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typed, true)
        tv.setTextColor(typed.data)
        return tv
    }

    private fun card(block: LinearLayout.() -> Unit): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            useCompatPadding = false
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        inner.block()
        card.addView(inner)
        return card
    }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.65f
        })
        row.addView(texts)
        row.addView(MaterialSwitch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        })
        return row
    }

    private fun sliderRow(
        title: String,
        valueText: () -> String,
        valueFrom: Float,
        valueTo: Float,
        step: Float,
        current: Float,
        onChange: (Float) -> Unit
    ): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueTv = TextView(this).apply {
            text = valueText()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            alpha = 0.7f
        }
        header.addView(valueTv)
        box.addView(header)
        box.addView(Slider(this).apply {
            this.valueFrom = valueFrom
            this.valueTo = valueTo
            stepSize = step
            value = current.coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    onChange(value)
                    valueTv.text = valueText()
                }
            }
        })
        return box
    }

    private fun bodyText(msg: String) = TextView(this).apply {
        text = msg
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        alpha = 0.8f
    }

    private fun hintText(msg: String) = TextView(this).apply {
        text = msg
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        alpha = 0.55f
        setPadding(0, dp(8), 0, 0)
    }

    private fun divider(): android.view.View {
        val v = android.view.View(this)
        val typed = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typed, true)
        v.setBackgroundColor(typed.data)
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        v.alpha = 0.5f
        return v
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun applyThemeMode(mode: String) {
        when (mode) {
            PreferencesHelper.THEME_LIGHT ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            PreferencesHelper.THEME_DARK ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    override fun onDestroy() {
        translationEngine.statusListener = null
        translationEngine.destroy()
        super.onDestroy()
    }
}
