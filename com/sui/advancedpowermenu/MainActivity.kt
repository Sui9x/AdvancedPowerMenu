package com.sui.advancedpowermenu

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.*
import android.view.*
import androidx.appcompat.app.*
import androidx.appcompat.widget.*
import androidx.core.view.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.sui.utils.Tint

object ConfigKeys {
    const val PREF_NAME = "advanced_power_menu_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_EXCLUDE_KEYGUARD = "exclude_keyguard"
    const val KEY_WORKAROUND_POWER = "workaround_power"
    const val KEY_ENABLED_QS = "enabled_qs"
    const val KEY_WORKAROUND_AM = "workaround_am"
    const val KEY_TRIGGER_HOLD = "trigger_hold"
    const val KEY_FORCE_FALLBACK = "force_fallback"
    const val KEY_DETAILED_LOG = "detailed_log"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_EXCLUDE_KEYGUARD = false
    const val DEFAULT_WORKAROUND_POWER = false
    const val DEFAULT_ENABLED_QS = false
    const val DEFAULT_WORKAROUND_AM = false
    const val DEFAULT_TRIGGER_HOLD = true
    const val DEFAULT_FORCE_FALLBACK = false
    const val DEFAULT_DETAILED_LOG = false
}

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    private val prefs by lazy {
        @Suppress("DEPRECATION")
        getSharedPreferences(
            ConfigKeys.PREF_NAME,
            Context.MODE_WORLD_READABLE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101010"))
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#101010"))
        }

        scroll.addView(root)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.setPadding(
                v.paddingLeft,
                sysBars.top + dp(8),
                v.paddingRight,
                sysBars.bottom + dp(8)
            )

            insets
        }

        title("Advanced Power Menu v${BuildConfig.VERSION_NAME}")

        addSwitchRow(
            title = "Enable",
            summary = "Power + Volume Up opens advanced power menu.",
            key = ConfigKeys.KEY_ENABLED,
            def = ConfigKeys.DEFAULT_ENABLED
        )

        addSwitchRow(
            title = "Disable on lock screen",
            summary = "The power menu will not be replaced while keyguard is showing.",
            key = ConfigKeys.KEY_EXCLUDE_KEYGUARD,
            def = ConfigKeys.DEFAULT_EXCLUDE_KEYGUARD
        )

        addSwitchRow(
            title = "Compatibility mode",
            summary = "Replace long-press power menu. Use this on ROMs where Power + Volume Up does not work.",
            key = ConfigKeys.KEY_WORKAROUND_POWER,
            def = ConfigKeys.DEFAULT_WORKAROUND_POWER
        )
        
        addSwitchRow(
            title = "Replace QS Power Menu (Experimental)",
            summary = "Replaces the action of the power menu button on the quick settings panel.",
            key = ConfigKeys.KEY_ENABLED_QS,
            def = ConfigKeys.DEFAULT_ENABLED_QS
        )
        
        addSwitchRow(
            title = "Workarounds for Restart Zygote",
            summary = "Restart the system_server instead of Zygote. Use this on ROMs where Restart Zygote does not work.",
            key = ConfigKeys.KEY_WORKAROUND_AM,
            def = ConfigKeys.DEFAULT_WORKAROUND_AM
        )
        
        addSwitchRow(
            title = "Press and hold to confirm",
            summary = "To prevent accidental operation, press and hold instead of tap.",
            key = ConfigKeys.KEY_TRIGGER_HOLD,
            def = ConfigKeys.DEFAULT_TRIGGER_HOLD
        )
        
        addSwitchRow(
            title = "Force always use fallback",
            summary = "This forces a fallback to always that use root privileges. May need to manually grant root privileges to SystemUI.",
            key = ConfigKeys.KEY_FORCE_FALLBACK,
            def = ConfigKeys.DEFAULT_FORCE_FALLBACK
        )
        
        addSwitchRow(
            title = "Detailed log",
            summary = "Enable LSposed detailed logging.",
            key = ConfigKeys.KEY_DETAILED_LOG,
            def = ConfigKeys.DEFAULT_DETAILED_LOG
        )

        note(
            "Changes are read by the hook through cached preferences, so reboot or SystemUI restart is usually not required except after the initial installation or after updates."
        )
        
        Tint.apply(root)
        
        setContentView(scroll)
    }

    private fun title(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
        })
        root.addView(TextView(this).apply {
            this.text = "Developed by Sui9x"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(4), dp(2), dp(4), dp(20))
        })
    }

    private fun note(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(4), dp(10), dp(4), 0)
        })
    }

    private fun addSwitchRow(
        title: String,
        summary: String,
        key: String,
        def: Boolean
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(Color.parseColor("#1D1D1D"), dp(18).toFloat())
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        texts.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.WHITE)
        })

        texts.addView(TextView(this).apply {
            text = summary
            textSize = 12f
            setTextColor(Color.parseColor("#9A9A9A"))
            setPadding(0, dp(4), dp(8), 0)
        })

        val sw = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean(key, checked)
                    .apply()
            }
        }

        row.setOnClickListener {
            sw.isChecked = !sw.isChecked
        }

        row.addView(texts)
        row.addView(sw)

        root.addView(row)
        root.addView(space(dp(12)))
    }

    private fun roundedBg(color: Int, radius: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun space(h: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                h
            )
        }
    }

    private fun dp(v: Int): Int {
        return (resources.displayMetrics.density * v + 0.5f).toInt()
    }
}