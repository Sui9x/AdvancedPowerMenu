//v2.7

package com.sui.advancedpowermenu

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class AppearanceActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    private val prefs by lazy {
        @Suppress("DEPRECATION")
        getSharedPreferences(
            ConfigKeys.PREF_NAME,
            Context.MODE_WORLD_READABLE
        )
    }

    private data class ValueRowRefs(
        val valueView: TextView,
        val preview: View?
    )

    private data class ColorRowRefs(
        val valueView: TextView,
        val preview: View
    )

    private val colorRows = mutableMapOf<String, ColorRowRefs>()

    private val alphaRows = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101010"))
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(20),
                dp(24),
                dp(20),
                dp(20)
            )
            setBackgroundColor(Color.parseColor("#101010"))
        }

        scroll.addView(root)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            v.setPadding(
                v.paddingLeft,
                bars.top + dp(8),
                v.paddingRight,
                bars.bottom + dp(8)
            )

            insets
        }

        addTitle()
        
        root.addView(space(dp(8)))

        addSection(
            title = "Menu background",
            colorKey = ConfigKeys.KEY_MENU_COLOR,
            defaultColor = ConfigKeys.DEFAULT_MENU_COLOR,
            alphaKey = ConfigKeys.KEY_MENU_ALPHA,
            defaultAlpha = ConfigKeys.DEFAULT_MENU_ALPHA
        )

        addSection(
            title = "Button background",
            colorKey = ConfigKeys.KEY_BUTTON_COLOR,
            defaultColor = ConfigKeys.DEFAULT_BUTTON_COLOR,
            alphaKey = ConfigKeys.KEY_BUTTON_ALPHA,
            defaultAlpha = ConfigKeys.DEFAULT_BUTTON_ALPHA
        )

        addSection(
            title = "Outline",
            colorKey = ConfigKeys.KEY_OUTLINE_COLOR,
            defaultColor = ConfigKeys.DEFAULT_OUTLINE_COLOR,
            alphaKey = ConfigKeys.KEY_OUTLINE_ALPHA,
            defaultAlpha = ConfigKeys.DEFAULT_OUTLINE_ALPHA
        )

        addSection(
            title = "Icon & text",
            colorKey = ConfigKeys.KEY_CONTENT_COLOR,
            defaultColor = ConfigKeys.DEFAULT_CONTENT_COLOR,
            alphaKey = ConfigKeys.KEY_CONTENT_ALPHA,
            defaultAlpha = ConfigKeys.DEFAULT_CONTENT_ALPHA
        )

        addSection(
            title = "Hold progress",
            colorKey = ConfigKeys.KEY_PROGRESS_COLOR,
            defaultColor = ConfigKeys.DEFAULT_PROGRESS_COLOR,
            alphaKey = ConfigKeys.KEY_PROGRESS_ALPHA,
            defaultAlpha = ConfigKeys.DEFAULT_PROGRESS_ALPHA
        )
        
        root.addView(space(dp(16)))

        addActionRow(
            title = "Reset to defaults",
            summary = "Reset all appearance settings."
        ) {
            showResetConfirmDialog()
        }

        setContentView(scroll)
    }

    private fun addTitle() {
        root.addView(
            TextView(this).apply {
                text = "Appearance"
                textSize = 24f
                setTextColor(Color.WHITE)
            }
        )
    }

    private fun addSection(
        title: String,
        colorKey: String,
        defaultColor: String,
        alphaKey: String,
        defaultAlpha: Int
    ) {
        root.addView(
            TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(
                    dp(4),
                    dp(8),
                    dp(4),
                    dp(8)
                )
            }
        )

        addColorRow(
            title = "Color",
            key = colorKey,
            defaultValue = defaultColor
        )

        addAlphaRow(
            title = "Opacity",
            key = alphaKey,
            defaultValue = defaultAlpha
        )
    }

    private fun addColorRow(
        title: String,
        key: String,
        defaultValue: String
    ) {
        val saved = normalizeColor(
            prefs.getString(
                key,
                defaultValue
            ),
            defaultValue
        )

        val refs = addValueRow(
            title = title,
            value = saved,
            previewColor = safeParseColor(
                saved,
                defaultValue
            )
        ) { valueView, preview ->
            showColorDialog(
                key = key,
                defaultValue = defaultValue
            ) { newValue ->
                valueView.text = newValue

                preview?.background =
                    colorPreview(
                        safeParseColor(
                            newValue,
                            defaultValue
                        )
                    )
            }
        }

        val preview = refs.preview
        if (preview != null) {
            colorRows[key] = ColorRowRefs(
                valueView = refs.valueView,
                preview = preview
            )
        }
    }

    private fun addAlphaRow(
        title: String,
        key: String,
        defaultValue: Int
    ) {
        val current = prefs
            .getInt(
                key,
                defaultValue
            )
            .coerceIn(0, 255)

        val refs = addValueRow(
            title = title,
            value = alphaPercentText(current),
            previewColor = null
        ) { valueView, _ ->
            val currentValue = prefs
                .getInt(
                    key,
                    defaultValue
                )
                .coerceIn(0, 255)

            showAlphaDialog(
                key = key,
                currentValue = currentValue,
                defaultValue = defaultValue
            ) { newValue ->
                valueView.text =
                    alphaPercentText(newValue)
            }
        }

        alphaRows[key] = refs.valueView
    }

    private fun addValueRow(
        title: String,
        value: String,
        previewColor: Int?,
        onClick: (
            TextView,
            View?
        ) -> Unit
    ): ValueRowRefs {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(14)
            )

            background = roundedBg(
                color = Color.parseColor("#1D1D1D"),
                radius = dp(18).toFloat()
            )

            isClickable = true
            isFocusable = true
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.WHITE)

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        row.addView(titleView)

        val valueView = TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(
                Color.parseColor("#AAAAAA")
            )
        }

        row.addView(valueView)
        
        var preview: View? = null

        if (previewColor != null) {
            preview = View(this).apply {
                background =
                    colorPreview(previewColor)
            }

            row.addView(
                preview,
                LinearLayout.LayoutParams(
                    dp(24),
                    dp(24)
                ).apply {
                    marginStart = dp(12)
                }
            )
        }

        row.setOnClickListener {
            onClick(
                valueView,
                preview
            )
        }

        root.addView(row)
        root.addView(space(dp(8)))

        return ValueRowRefs(
            valueView = valueView,
            preview = preview
        )
    }

    private fun addActionRow(
        title: String,
        summary: String,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(14)
            )

            background = roundedBg(
                color = Color.parseColor("#1D1D1D"),
                radius = dp(18).toFloat()
            )

            isClickable = true
            isFocusable = true
        }

        row.addView(
            TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
            }
        )

        row.addView(
            TextView(this).apply {
                text = summary
                textSize = 12f
                setTextColor(
                    Color.parseColor("#9A9A9A")
                )
                setPadding(
                    0,
                    dp(4),
                    0,
                    0
                )
            }
        )

        row.setOnClickListener {
            onClick()
        }

        root.addView(row)
        root.addView(space(dp(12)))
    }

    private fun showColorDialog(
        key: String,
        defaultValue: String,
        onChanged: (String) -> Unit
    ) {
        val current = normalizeColor(
            prefs.getString(key, defaultValue),
            defaultValue
        )
    
        val input = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            hint = "#RRGGBB"
            setSingleLine()
    
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#777777"))
    
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#AAAAAA")
                )
    
            setPadding(
                dp(4),
                dp(8),
                dp(4),
                dp(8)
            )
        }
    
        val preview = View(this).apply {
            background = colorPreview(
                safeParseColor(
                    current,
                    defaultValue
                )
            )
        }
    
        lateinit var dialog: Dialog
    
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
    
        val defaultButton = dialogButton("Default") {
            prefs.edit()
                .remove(key)
                .apply()
    
            onChanged(defaultValue)
            dialog.dismiss()
        }
    
        val cancelButton = dialogButton("Cancel") {
            dialog.dismiss()
        }
    
        val okButton = dialogButton(
            "OK",
            Color.WHITE
        ) {
            val normalized =
                normalizeColorOrNull(
                    input.text.toString()
                )
    
            if (normalized == null) {
                Toast.makeText(this, "Use #RRGGBB", Toast.LENGTH_SHORT).show()
                return@dialogButton
            }
    
            prefs.edit()
                .putString(
                    key,
                    normalized
                )
                .apply()
    
            onChanged(normalized)
            dialog.dismiss()
        }
    
        buttonRow.addView(defaultButton)
    
        buttonRow.addView(
            View(this),
            LinearLayout.LayoutParams(
                0,
                1,
                1f
            )
        )
    
        buttonRow.addView(cancelButton)
        buttonRow.addView(okButton)
    
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        
            setPadding(
                dp(20),
                dp(18),
                dp(20),
                dp(10)
            )
        
            background = roundedBg(
                color = Color.parseColor("#1D1D1D"),
                radius = dp(20).toFloat()
            )
        
            addView(
                TextView(this@AppearanceActivity).apply {
                    text = "Color"
                    textSize = 18f
                    setTextColor(Color.WHITE)
                }
            )
        
            addView(
                preview,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(36)
                ).apply {
                    topMargin = dp(16)
                    bottomMargin = dp(12)
                }
            )
        
            addView(input)
        
            addView(
                buttonRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
            )
        }
        
        dialog = showStyledDialog(content)
    
        input.setOnFocusChangeListener { _, _ ->
            val value = normalizeColorOrNull(
                input.text.toString()
            ) ?: return@setOnFocusChangeListener
    
            preview.background = colorPreview(
                safeParseColor(
                    value,
                    defaultValue
                )
            )
        }
    
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(
                    android.R.color.transparent
                )
    
                decorView.setPadding(
                    dp(20),
                    0,
                    dp(20),
                    0
                )
            }
        }
    
        dialog.show()
    }

    private fun showAlphaDialog(
        key: String,
        currentValue: Int,
        defaultValue: Int,
        onChanged: (Int) -> Unit
    ) {
        val valueText = TextView(this).apply {
            text = alphaPercentText(currentValue)
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
    
        val seek = SeekBar(this).apply {
            max = 255
            progress = currentValue
    
            progressTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#CDDDF2")
                )
    
            thumbTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.WHITE
                )
        }
    
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    valueText.text =
                        alphaPercentText(progress)
                }
    
                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) = Unit
    
                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) = Unit
            }
        )
    
        lateinit var dialog: Dialog
    
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
    
        val defaultButton = dialogButton("Default") {
            prefs.edit()
                .remove(key)
                .apply()
    
            onChanged(defaultValue)
            dialog.dismiss()
        }
    
        val cancelButton = dialogButton("Cancel") {
            dialog.dismiss()
        }
    
        val okButton = dialogButton(
            "OK",
            Color.WHITE
        ) {
            prefs.edit()
                .putInt(
                    key,
                    seek.progress
                )
                .apply()
    
            onChanged(
                seek.progress
            )
    
            dialog.dismiss()
        }
    
        buttonRow.addView(defaultButton)
    
        buttonRow.addView(
            View(this),
            LinearLayout.LayoutParams(
                0,
                1,
                1f
            )
        )
    
        buttonRow.addView(cancelButton)
        buttonRow.addView(okButton)
    
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
    
            setPadding(
                dp(20),
                dp(18),
                dp(20),
                dp(10)
            )
    
            background = roundedBg(
                color = Color.parseColor("#1D1D1D"),
                radius = dp(20).toFloat()
            )
    
            addView(
                TextView(this@AppearanceActivity).apply {
                    text = "Opacity"
                    textSize = 18f
                    setTextColor(Color.WHITE)
                }
            )
    
            addView(
                valueText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(18)
                }
            )
    
            addView(
                seek,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                }
            )
    
            addView(buttonRow)
        }
        
        dialog = showStyledDialog(content)
    
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(
                    android.R.color.transparent
                )
    
                decorView.setPadding(
                    dp(20),
                    0,
                    dp(20),
                    0
                )
            }
        }
    
        dialog.show()
    }

    private fun refreshAppearanceRows() {
        refreshColorRow(
            ConfigKeys.KEY_MENU_COLOR,
            ConfigKeys.DEFAULT_MENU_COLOR
        )
        refreshAlphaRow(
            ConfigKeys.KEY_MENU_ALPHA,
            ConfigKeys.DEFAULT_MENU_ALPHA
        )

        refreshColorRow(
            ConfigKeys.KEY_BUTTON_COLOR,
            ConfigKeys.DEFAULT_BUTTON_COLOR
        )
        refreshAlphaRow(
            ConfigKeys.KEY_BUTTON_ALPHA,
            ConfigKeys.DEFAULT_BUTTON_ALPHA
        )

        refreshColorRow(
            ConfigKeys.KEY_OUTLINE_COLOR,
            ConfigKeys.DEFAULT_OUTLINE_COLOR
        )
        refreshAlphaRow(
            ConfigKeys.KEY_OUTLINE_ALPHA,
            ConfigKeys.DEFAULT_OUTLINE_ALPHA
        )

        refreshColorRow(
            ConfigKeys.KEY_CONTENT_COLOR,
            ConfigKeys.DEFAULT_CONTENT_COLOR
        )
        refreshAlphaRow(
            ConfigKeys.KEY_CONTENT_ALPHA,
            ConfigKeys.DEFAULT_CONTENT_ALPHA
        )

        refreshColorRow(
            ConfigKeys.KEY_PROGRESS_COLOR,
            ConfigKeys.DEFAULT_PROGRESS_COLOR
        )
        refreshAlphaRow(
            ConfigKeys.KEY_PROGRESS_ALPHA,
            ConfigKeys.DEFAULT_PROGRESS_ALPHA
        )
    }

    private fun refreshColorRow(
        key: String,
        defaultValue: String
    ) {
        val refs =
            colorRows[key]
                ?: return

        val value = normalizeColor(
            prefs.getString(
                key,
                defaultValue
            ),
            defaultValue
        )

        refs.valueView.text = value

        refs.preview.background =
            colorPreview(
                safeParseColor(
                    value,
                    defaultValue
                )
            )
    }

    private fun refreshAlphaRow(
        key: String,
        defaultValue: Int
    ) {
        val valueView =
            alphaRows[key]
                ?: return

        val alpha = prefs
            .getInt(
                key,
                defaultValue
            )
            .coerceIn(0, 255)

        valueView.text =
            alphaPercentText(alpha)
    }

    private fun resetAppearance() {
        prefs.edit()
            .remove(
                ConfigKeys.KEY_MENU_COLOR
            )
            .remove(
                ConfigKeys.KEY_MENU_ALPHA
            )
            .remove(
                ConfigKeys.KEY_BUTTON_COLOR
            )
            .remove(
                ConfigKeys.KEY_BUTTON_ALPHA
            )
            .remove(
                ConfigKeys.KEY_OUTLINE_COLOR
            )
            .remove(
                ConfigKeys.KEY_OUTLINE_ALPHA
            )
            .remove(
                ConfigKeys.KEY_CONTENT_COLOR
            )
            .remove(
                ConfigKeys.KEY_CONTENT_ALPHA
            )
            .remove(
                ConfigKeys.KEY_PROGRESS_COLOR
            )
            .remove(
                ConfigKeys.KEY_PROGRESS_ALPHA
            )
            .apply()
    }

    private fun normalizeColor(
        value: String?,
        fallback: String
    ): String {
        return normalizeColorOrNull(value)
            ?: fallback
    }

    private fun normalizeColorOrNull(
        value: String?
    ): String? {
        if (value == null) {
            return null
        }

        val raw = value
            .trim()
            .uppercase()

        val normalized =
            if (raw.startsWith("#")) {
                raw
            } else {
                "#$raw"
            }

        if (
            !Regex(
                "^#[0-9A-F]{6}$"
            ).matches(normalized)
        ) {
            return null
        }

        return normalized
    }

    private fun safeParseColor(
        value: String?,
        fallback: String
    ): Int {
        return try {
            Color.parseColor(
                normalizeColor(
                    value,
                    fallback
                )
            )
        } catch (_: Throwable) {
            Color.parseColor(fallback)
        }
    }

    private fun alphaPercentText(
        alpha: Int
    ): String {
        val percent =
            (
                alpha
                    .coerceIn(0, 255) *
                    100 +
                    127
            ) / 255

        return "$percent%"
    }

    private fun colorPreview(
        color: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                dp(8).toFloat()

            setColor(color)

            setStroke(
                dp(1),
                Color.parseColor("#666666")
            )
        }
    }
    
    private fun dialogButton(
        textValue: String,
        color: Int = Color.parseColor("#AAAAAA"),
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(color)
            gravity = Gravity.CENTER
    
            setPadding(
                dp(14),
                dp(10),
                dp(14),
                dp(10)
            )
    
            isClickable = true
            isFocusable = true
    
            setOnClickListener {
                onClick()
            }
        }
    }
    
    private fun showStyledDialog(
        content: View
    ): Dialog {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            setCanceledOnTouchOutside(true)
    
            window?.apply {
                setBackgroundDrawableResource(
                    android.R.color.transparent
                )
    
                setDimAmount(0.45f)
                addFlags(
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
                )
    
                setGravity(Gravity.CENTER)
            }
        }
    
        dialog.show()
    
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    
        return dialog
    }
    
    private fun showResetConfirmDialog() {
        lateinit var dialog: Dialog
    
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
    
        val cancelButton = dialogButton("Cancel") {
            dialog.dismiss()
        }
    
        val resetButton = dialogButton(
            "Reset",
            Color.WHITE
        ) {
            resetAppearance()
            refreshAppearanceRows()
            dialog.dismiss()
        }
    
        buttonRow.addView(
            View(this),
            LinearLayout.LayoutParams(
                0,
                1,
                1f
            )
        )
    
        buttonRow.addView(cancelButton)
        buttonRow.addView(resetButton)
    
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
    
            setPadding(
                dp(20),
                dp(18),
                dp(20),
                dp(10)
            )
    
            background = roundedBg(
                color = Color.parseColor("#1D1D1D"),
                radius = dp(20).toFloat()
            )
    
            addView(
                TextView(this@AppearanceActivity).apply {
                    text = "Reset appearance?"
                    textSize = 18f
                    setTextColor(Color.WHITE)
                }
            )
    
            addView(
                TextView(this@AppearanceActivity).apply {
                    text = "All color and opacity settings will be restored to their defaults."
                    textSize = 13f
                    setTextColor(Color.parseColor("#AAAAAA"))
    
                    setPadding(
                        0,
                        dp(10),
                        0,
                        dp(14)
                    )
                }
            )
    
            addView(buttonRow)
        }
    
        dialog = showStyledDialog(content)
    }

    private fun roundedBg(
        color: Int,
        radius: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    private fun space(h: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                h
            )
        }
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value + 0.5f).toInt()
    }
}