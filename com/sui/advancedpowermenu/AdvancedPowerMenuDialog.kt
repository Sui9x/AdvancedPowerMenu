package com.sui.advancedpowermenu

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.PowerManager
import android.os.SystemClock
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.animation.*
import android.widget.*
import de.robv.android.xposed.*
import com.sui.utils.Tint
import com.sui.utils.HardcodedVectorDrawable

object AdvancedPowerMenuDialog {

    private const val TAG = "AdvancedPowerMenuDialog"
    private const val PKG_MODULE = "com.sui.advancedpowermenu"
    
    private const val TOKEN = "sui_advanced_power_menu_internal_token"
    
    private const val ACTION_RUN_POWER = "com.sui.advancedpowermenu.action.RUN_POWER"
    private const val ACTION_POWER_RESULT = "com.sui.advancedpowermenu.action.POWER_RESULT"

    private const val EXTRA_ACTION = "action"
    private const val EXTRA_FALLBACK_CMD = "fallback_cmd"
    private const val EXTRA_OK = "ok"
    private const val EXTRA_ERROR = "error"
    
    @Volatile private var resultReceiverInstalled = false
    
    @Volatile private var pendingAction: String? = null
    @Volatile private var pendingFallbackCmd: String? = null
    @Volatile private var pendingRequestedAt = 0L

    @Volatile private var showing = false
    @Volatile private var lastShownAt = 0L
    
    @Volatile private var activeTouchView: View? = null
    
    @Volatile private var currentDialog: AlertDialog? = null
    @Volatile private var screenReceiver: BroadcastReceiver? = null

    private val showLock = Any()
    
    private lateinit var sb: View
    
    data class AdvConfig(
        val enabled: Boolean,
        val excludeKeyguard: Boolean,
        val workaroundPower: Boolean,
        val enabledQs: Boolean,
        val workaroundAm: Boolean,
        val triggerHold: Boolean,
        val forceFallback: Boolean,
        val detailedLog: Boolean
    )

    @Volatile private var cachedConfig = AdvConfig(
        enabled = ConfigKeys.DEFAULT_ENABLED,
        excludeKeyguard = ConfigKeys.DEFAULT_EXCLUDE_KEYGUARD,
        workaroundPower = ConfigKeys.DEFAULT_WORKAROUND_POWER,
        enabledQs = ConfigKeys.DEFAULT_ENABLED_QS,
        workaroundAm = ConfigKeys.DEFAULT_WORKAROUND_AM,
        triggerHold = ConfigKeys.DEFAULT_TRIGGER_HOLD,
        forceFallback = ConfigKeys.DEFAULT_FORCE_FALLBACK,
        detailedLog = ConfigKeys.DEFAULT_DETAILED_LOG
    )

    @Volatile private var lastConfigReadAt = 0L
    private var xPrefs: XSharedPreferences? = null

    private fun config(): AdvConfig {
        val now = SystemClock.uptimeMillis()

        if (now - lastConfigReadAt < 800) {
            return cachedConfig
        }

        lastConfigReadAt = now

        return try {
            val prefs = xPrefs ?: XSharedPreferences(
                PKG_MODULE,
                ConfigKeys.PREF_NAME
            ).also {
                xPrefs = it
            }

            prefs.reload()

            cachedConfig = AdvConfig(
                enabled = prefs.getBoolean(
                    ConfigKeys.KEY_ENABLED,
                    ConfigKeys.DEFAULT_ENABLED
                ),
                excludeKeyguard = prefs.getBoolean(
                    ConfigKeys.KEY_EXCLUDE_KEYGUARD,
                    ConfigKeys.DEFAULT_EXCLUDE_KEYGUARD
                ),
                workaroundPower = prefs.getBoolean(
                    ConfigKeys.KEY_WORKAROUND_POWER,
                    ConfigKeys.DEFAULT_WORKAROUND_POWER
                ),
                enabledQs = prefs.getBoolean(
                    ConfigKeys.KEY_ENABLED_QS,
                    ConfigKeys.DEFAULT_ENABLED_QS
                ),
                workaroundAm = prefs.getBoolean(
                    ConfigKeys.KEY_WORKAROUND_AM,
                    ConfigKeys.DEFAULT_WORKAROUND_AM
                ),
                triggerHold = prefs.getBoolean(
                    ConfigKeys.KEY_TRIGGER_HOLD,
                    ConfigKeys.DEFAULT_TRIGGER_HOLD
                ),
                forceFallback = prefs.getBoolean(
                    ConfigKeys.KEY_FORCE_FALLBACK,
                    ConfigKeys.DEFAULT_FORCE_FALLBACK
                ),
                detailedLog = prefs.getBoolean(
                    ConfigKeys.KEY_DETAILED_LOG,
                    ConfigKeys.DEFAULT_DETAILED_LOG
                )
            )
    
            cachedConfig
        } catch (t: Throwable) {
            log("config read failed: $t")
            cachedConfig
        }
    }

    fun show(context: Context) {
        installPowerResultReceiver(context.applicationContext)

        val now = SystemClock.uptimeMillis()
        var dialogToDismiss: AlertDialog? = null

        synchronized(showLock) {
            if (showing || currentDialog?.isShowing == true) {
                log("show called while showing -> dismiss")
                dialogToDismiss = currentDialog
                showing = false
                currentDialog = null
            } else if (now - lastShownAt < 250) {
                log("show skipped: debounce")
                return
            } else {
                showing = true
                lastShownAt = now
            }
        }

        if (dialogToDismiss != null) {
            try {
                dialogToDismiss?.dismiss()
            } catch (t: Throwable) {
                log("dismiss current failed: $t")
            }
            return
        }

        try {
            val dialog = AlertDialog.Builder(
                context,
                android.R.style.Theme_Material_Dialog_Alert
            ).create()

            dialog.setCanceledOnTouchOutside(true)
            
            dialog.setView(createAdvancedPowerMenuView(context, dialog))

            setBestDialogWindowType(dialog)

            dialog.show()

            dialog.window?.apply {
                
                addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    //WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
                
                setBackgroundDrawableResource(android.R.color.transparent)

                decorView.setPadding(0, 0, 0, 0)

                setLayout(
                    (context.resources.displayMetrics.widthPixels * 0.6f).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
            
            currentDialog = dialog

            registerScreenOffDismissReceiver(context.applicationContext, dialog)

            dialog.setOnCancelListener {
                synchronized(showLock) {
                    showing = false
                    currentDialog = null
                }
                unregisterScreenReceiver(context.applicationContext)
            }

            dialog.setOnDismissListener {
                synchronized(showLock) {
                    showing = false
                    currentDialog = null
                }
                unregisterScreenReceiver(context.applicationContext)
            }

            logAlways("advanced power menu dialog shown")
        } catch (t: Throwable) {
            synchronized(showLock) {
                showing = false
            }
            logAlways("show failed: $t")
        }
    }
    
    private fun dismissCurrent(reason: String) {
        try {
            currentDialog?.dismiss()
            logAlways("dismiss current dialog: $reason")
        } catch (t: Throwable) {
            logAlways("dismiss current failed: $t")
        }
    }

    private fun createAdvancedPowerMenuView(
        context: Context,
        dialog: AlertDialog
    ): View {
        val cfg = config()
        
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, 24)
            setPadding(p, p, p, p)

            background = roundedBg(
                radius = dp(context, 24).toFloat(),
                color = Tint.U1N9_LIGHT_BLUE,
                alpha = 80
            )
        }

        root.addView(TextView(context).apply {
            text = "Advanced Power Menu"
            textSize = 18f
            setTextColor(Color.WHITE)
        })

        root.addView(space(context, 16))

        root.addView(
            row(
                context,
                button(context, "Reboot", "ic_reboot") {
                    requestPowerAction(context, "reboot", "reboot")
                    dialog.dismiss()
                },
                button(context, "Safe Mode", "ic_reboot_safemode") {
                    requestPowerAction(context, "safemode", "SAFE_MODE_FALLBACK")
                    dialog.dismiss()
                }
            )
        )
        
        root.addView(space(context, 16))
        
        if (!cfg.workaroundAm) {
            sb = button(context, "Zygote", "ic_soft_reboot") {
                requestPowerAction(context, "zygote", "setprop ctl.restart zygote")
                dialog.dismiss()
            }
        } else {
            sb = button(context, "System Services", "ic_soft_reboot") {
                requestPowerAction(context, "system_server", "am restart")
                dialog.dismiss()
            }
        }
        
        root.addView(
            row(
                context,
                button(context, "SystemUI", "ic_restart_systemui") {
                    restartSystemUiInternalFallback()
                    dialog.dismiss()
                },
                sb
            )
        )

        root.addView(space(context, 16))

        root.addView(
            row(
                context,
                button(context, "Recovery", "ic_reboot_recovery") {
                    requestPowerAction(context, "recovery", "reboot recovery")
                    dialog.dismiss()
                },
                button(context, "Bootloader", "ic_reboot_bootloader") {
                    requestPowerAction(context, "bootloader", "reboot bootloader")
                    dialog.dismiss()
                }
            )
        )

        root.addView(space(context, 16))
        
        root.addView(
            row(
                context,
                button(context, "Power off", "ic_power_off") {
                    requestPowerAction(context, "poweroff", "reboot -p")
                    dialog.dismiss()
                },
                button(context, "Lockdown", "ic_lock") {
                    requestPowerAction(
                        context,
                        "lockdown",
                        "ROOT_LOCKDOWN_FALLBACK"
                    )
                    dialog.dismiss()
                }
            )
        )
        
        return root
    }
    
    private fun button(
        context: Context,
        text: String,
        iconName: String,
        onClick: () -> Unit
    ): View {
        val cfg = config()
        val holdMs = 1000L

        val root = FrameLayout(context).apply {
            isClickable = true
            background = roundedBg(
                radius = dp(context, 20).toFloat(),
                color = Color.TRANSPARENT,
                strokeColor = Color.WHITE,
                strokeWidth = xdp(context, 1, 1.5f)
            )
        }
        
        root.clipToOutline = true

        val progressView = View(context).apply {
            background = roundedBg(
                radius = dp(context, 20).toFloat(),
                color = Tint.U1N9_LIGHT_BLUE,
                alpha = 48,
                strokeColor = Color.TRANSPARENT,
                strokeWidth = 0
            )
            visibility = if (cfg.triggerHold) View.VISIBLE else View.GONE
        }

        root.addView(
            progressView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.BOTTOM
            )
        )

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            val icon = ImageView(context).apply {
                
                /*
                com.sui.utils.loadModuleDrawable(PKG_MODULE, context, iconName)?.let {
                    setImageDrawable(it)
                }
                */
                
                createIcon(iconName)?.let {
                    setImageDrawable(it)
                }

                setColorFilter(Color.WHITE)

                layoutParams = LinearLayout.LayoutParams(
                    dp(context, 32),
                    dp(context, 32)
                )
            }

            val tv = TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
            }

            addView(space(context, 4))
            addView(icon)
            addView(space(context, 6))
            addView(tv)
        }

        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        var holdRunning = false
        var holdCompleted = false
        var holdStartTime = 0L

        fun updateProgress(progress: Float) {
            if (!cfg.triggerHold) return

            val h = (root.height * progress.coerceIn(0f, 1f)).toInt()
            val lp = progressView.layoutParams as FrameLayout.LayoutParams
            lp.height = h
            lp.gravity = Gravity.BOTTOM
            progressView.layoutParams = lp
        }

        fun resetProgress() {
            updateProgress(0f)
        }

        fun startHold() {
            if (!cfg.triggerHold) return
            if (holdRunning) return

            holdRunning = true
            holdCompleted = false
            holdStartTime = SystemClock.uptimeMillis()

            fun tick() {
                if (!holdRunning) return

                val elapsed = SystemClock.uptimeMillis() - holdStartTime
                val progress = (elapsed.toFloat() / holdMs).coerceIn(0f, 1f)

                updateProgress(progress)

                if (progress >= 1f) {
                    holdRunning = false
                    holdCompleted = true

                    root.performHapticFeedback(
                        HapticFeedbackConstants.VIRTUAL_KEY,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )

                    onClick()
                } else {
                    root.postOnAnimation { tick() }
                }
            }

            tick()
        }

        fun cancelHold() {
            if (!cfg.triggerHold) return

            holdRunning = false

            if (!holdCompleted) {
                resetProgress()
            }
        }

        root.setOnClickListener { view ->
            if (!cfg.triggerHold) {
                view.performHapticFeedback(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                onClick()
            }
        }

        root.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    
                    if (activeTouchView != null && activeTouchView !== view) {
                        return@setOnTouchListener true
                    }
                    activeTouchView = view

                    view.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(50)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                    if (cfg.triggerHold) {
                        startHold()
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (activeTouchView === view) {
                        activeTouchView = null
                    } else {
                        return@setOnTouchListener true
                    }
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(50)
                        .setInterpolator(BounceInterpolator())
                        .start()

                    if (cfg.triggerHold) {
                        cancelHold()
                        true
                    } else {
                        false
                    }
                }

                else -> cfg.triggerHold
            }
        }
        
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: View) {
                if (activeTouchView === v) {
                    activeTouchView = null
                }
            }

            override fun onViewAttachedToWindow(v: View) {}
        })

        return root
    }

    private fun row(
        context: Context,
        left: View,
        right: View
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL

            addView(left.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(context, 80),
                    1f
                ).apply {
                    marginEnd = dp(context, 8)
                }
            })

            addView(right.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(context, 80),
                    1f
                ).apply {
                    marginStart = dp(context, 8)
                }
            })
        }
    }
    
    private fun createIcon(name: String): Drawable? {
        val PATH_REBOOT_L = "M1195 3628 c-241 -260 -403 -581 -482 -956 -25 -118 -27 -145 -27 -372 0 -250 2 -273 48 -471 26 -109 94 -294 146 -400 203 -404 505 -709 885 -894 112 -54 270 -114 279 -105 4 5 43 148 86 319 11 41 17 78 14 82 -2 4 -21 13 -42 20 -60 20 -195 84 -268 129 -233 141 -443 368 -565 612 -288 572 -200 1254 221 1710 l71 78 -146 160 c-80 88 -148 160 -150 160 -2 0 -33 -33 -70 -72z"
        val PATH_REBOOT_R = "M2143 4428 l-411 -422 99 -106 c54 -58 243 -251 419 -430 l319 -325 1 318 c0 271 2 318 15 323 19 8 210 -13 300 -33 315 -68 612 -249 815 -498 203 -248 317 -539 338 -860 31 -471 -114 -870 -433 -1191 -232 -233 -483 -363 -804 -415 -64 -10 -142 -19 -173 -19 l-58 0 0 -217 0 -216 123 6 c533 28 1045 303 1378 742 253 332 399 769 399 1195 0 273 -69 587 -186 837 -202 436 -575 792 -1010 967 -187 74 -481 136 -648 136 l-56 0 0 315 c0 173 -4 315 -8 315 -4 0 -193 -190 -419 -422z"
        
        val drawable = when (name) {
            "ic_reboot" ->
                HardcodedVectorDrawable(
                    viewportWidth = 512f,
                    viewportHeight = 512f,
                    nodes = listOf(
                        HardcodedVectorDrawable.Group(
                            scaleX = 0.1f,
                            scaleY = -0.1f,
                            translateY = 512f,
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = PATH_REBOOT_R
                                ),
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = PATH_REBOOT_L
                                )
                            )
                        )
                    )
                )
            
            "ic_reboot_safemode" ->
                HardcodedVectorDrawable(
                    viewportWidth = 512f,
                    viewportHeight = 512f,
                    nodes = listOf(
                        HardcodedVectorDrawable.Group(
                            scaleX = 0.1f,
                            scaleY = -0.1f,
                            translateY = 512f,
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = PATH_REBOOT_R
                                ),
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = PATH_REBOOT_L
                                )
                            )
                        ),
                        HardcodedVectorDrawable.Group(
                            scaleX = 16f,
                            scaleY = 16f,
                            translateX = 64f,
                            translateY = 76f,
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = "M15,12h-2v-2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1v2L9,12c-0.55,0 -1,0.45 -1,1s0.45,1 1,1h2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1z"
                                )
                            )
                        )
                    )
                )
                
            "ic_soft_reboot" ->
                HardcodedVectorDrawable(
                    viewportWidth = 512f,
                    viewportHeight = 512f,
                    nodes = listOf(
                        HardcodedVectorDrawable.Group(
                            scaleX = 0.1f,
                            scaleY = -0.1f,
                            translateY = 512f,
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = PATH_REBOOT_R
                                ),
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = PATH_REBOOT_L
                                )
                            )
                        ),
                        HardcodedVectorDrawable.Group(
                            scaleX = 12f,
                            scaleY = 12f,
                            translateX = 112f,
                            translateY = 205f,
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData="""M15,9A1,1 0 0,1 14,8A1,1 0 0,1 15,7A1,1 0 0,1 16,8A1,1 0 0,1 15,9
                                        M9,9A1,1 0 0,1 8,8A1,1 0 0,1 9,7A1,1 0 0,1 10,8A1,1 0 0,1 9,9
                                        M16.12,4.37L18.22,2.27L17.4,1.44L15.09,3.75
                                        C14.16,3.28 13.11,3 12,3
                                        C10.88,3 9.84,3.28 8.91,3.75
                                        L6.6,1.44L5.78,2.27L7.88,4.37
                                        C6.14,5.64 5,7.68 5,10V11H19V10
                                        C19,7.68 17.86,5.64 16.12,4.37"""
                                )
                            )
                        )
                    )
                )
            
            "ic_restart_systemui" ->
                HardcodedVectorDrawable(
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                    nodes = listOf(
                        HardcodedVectorDrawable.Group(
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = "M15,9A1,1 0 0,1 14,8A1,1 0 0,1 15,7A1,1 0 0,1 16,8A1,1 0 0,1 15,9M9,9A1,1 0 0,1 8,8A1,1 0 0,1 9,7A1,1 0 0,1 10,8A1,1 0 0,1 9,9M16.12,4.37L18.22,2.27L17.4,1.44L15.09,3.75C14.16,3.28 13.11,3 12,3C10.88,3 9.84,3.28 8.91,3.75L6.6,1.44L5.78,2.27L7.88,4.37C6.14,5.64 5,7.68 5,10V11H19V10C19,7.68 17.86,5.64 16.12,4.37M5,16C5,19.86 8.13,23 12,23A7,7 0 0,0 19,16V12H5V16Z"
                                )
                            )
                        )
                    )
                )
            
            "ic_reboot_recovery" ->
                HardcodedVectorDrawable(
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                    nodes = listOf(
                        HardcodedVectorDrawable.Group(
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = "M19.43,12.98 C19.47,12.66,19.5,12.34,19.5,12 S19.47,11.34,19.43,11.02 L21.54,9.37 C21.73,9.22,21.78,8.95,21.66,8.73 L19.66,5.27 C19.54,5.05,19.269,4.97,19.05,5.05 L16.56,6.05 C16.04,5.65,15.481,5.32,14.871,5.07 L14.491,2.42 C14.46,2.18,14.25,2,14,2 L10,2 C9.75,2,9.54,2.18,9.51,2.42 L9.13,5.07 C8.52,5.32,7.96,5.66,7.44,6.05 L4.95,5.05 C4.72,4.96,4.46,5.05,4.34,5.27 L2.34,8.73 C2.21,8.95,2.27,9.22,2.46,9.37 L4.57,11.02 C4.53,11.34,4.5,11.67,4.5,12 S4.53,12.66,4.57,12.98 L2.46,14.63 C2.27,14.78,2.22,15.05,2.34,15.27 L4.34,18.731 C4.46,18.951,4.73,19.031,4.95,18.951 L7.44,17.951 C7.96,18.35,8.52,18.68,9.13,18.93 L9.51,21.58 C9.54,21.82,9.75,22,10,22 L14,22 C14.25,22,14.46,21.82,14.49,21.58 L14.87,18.93 C15.48,18.68,16.04,18.34,16.559,17.951 L19.049,18.951 C19.279,19.041,19.539,18.951,19.659,18.731 L21.659,15.27 C21.779,15.05,21.729,14.781,21.539,14.63 L19.43,12.98 Z M9.71,15.71 L8.29,14.29 L14.29,8.29 L15.71,9.71 L9.71,15.71 Z"
                                )
                            )
                        )
                    )
                )
            
            "ic_reboot_bootloader" ->
                HardcodedVectorDrawable(
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                    nodes = listOf(
                        HardcodedVectorDrawable.Group(
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = "M21,8 L3,8 C2,8,2,9,2,9 L2,21 C2,22,3,22,3,22 L21,22 C22,22,22,21,22,21 L22,9 C22,8,21,8,21,8 Z M17,9 C17.55,9,18,9.45,18,10 S17.55,11,17,11 S16,10.55,16,10 S16.45,9,17,9 Z M14,9 C14.55,9,15,9.45,15,10 S14.55,11,14,11 S13,10.55,13,10 S13.45,9,14,9 Z M20,20 L4,20 L4,12 L20,12 L20,20 Z M20,11 C19.45,11,19,10.55,19,10 S19.45,9,20,9 S21,9.45,21,10 S20.55,11,20,11 Z M21,2 L3,2 C2,2,2,3,2,3 L2,6 L22,6 L22,3 C22,2,21,2,21,2 Z M14,5 C13.45,5,13,4.55,13,4 S13.45,3,14,3 S15,3.45,15,4 S14.55,5,14,5 Z M17,5 C16.45,5,16,4.55,16,4 S16.45,3,17,3 S18,3.45,18,4 S17.55,5,17,5 Z M20,5 C19.45,5,19,4.55,19,4 S19.45,3,20,3 S21,3.45,21,4 S20.55,5,20,5 Z"
                                ),
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = "M11,19.414 L8.293,16.707 L9.707,15.293 L11,16.586 L14.293,13.293 L15.707,14.707 Z"
                                )
                            )
                        )
                    )
                )
            
            "ic_power_off" ->
                HardcodedVectorDrawable(
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                    nodes = listOf(
                        HardcodedVectorDrawable.Group(
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = "M12,3c-0.55,0 -1,0.45 -1,1v8c0,0.55 0.45,1 1,1s1,-0.45 1,-1L13,4c0,-0.55 -0.45,-1 -1,-1zM17.14,5.86c-0.39,0.39 -0.38,1 -0.01,1.39 1.13,1.2 1.83,2.8 1.87,4.57 0.09,3.83 -3.08,7.13 -6.91,7.17C8.18,19.05 5,15.9 5,12c0,-1.84 0.71,-3.51 1.87,-4.76 0.37,-0.39 0.37,-1 -0.01,-1.38 -0.4,-0.4 -1.05,-0.39 -1.43,0.02C3.98,7.42 3.07,9.47 3,11.74c-0.14,4.88 3.83,9.1 8.71,9.25 5.1,0.16 9.29,-3.93 9.29,-9 0,-2.37 -0.92,-4.51 -2.42,-6.11 -0.38,-0.41 -1.04,-0.42 -1.44,-0.02z"
                                )
                            )
                        )
                    )
                )
            
            "ic_lock" ->
                HardcodedVectorDrawable(
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                    nodes = listOf(
                        HardcodedVectorDrawable.Group(
                            children = listOf(
                                HardcodedVectorDrawable.PathNode(
                                    fillColor = Color.WHITE,
                                    pathData = "M18,8h-1L17,6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2L6,8c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L20,10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM9,8L9,6c0,-1.66 1.34,-3 3,-3s3,1.34 3,3v2L9,8z"
                                )
                            )
                        )
                    )
                )
            
            else -> return null
        }
        return drawable
    }

    private fun roundedBg(
        radius: Float,
        color: Int,
        alpha: Int? = null,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidth: Int = 0
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        
        if (alpha != null) setAlpha(alpha)

        if (strokeWidth > 0) {
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(context: Context, v: Int): Int {
        return (context.resources.displayMetrics.density * v).toInt()
    }
    
    private fun xdp(context: Context, v: Int, x:Float): Int {
        return (context.resources.displayMetrics.density * v * x).toInt()
    }

    private fun space(context: Context, dp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, dp)
            )
        }
    }

    private fun execSu(cmd: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            logAlways("exec: $cmd")
        } catch (t: Throwable) {
            logAlways("exec failed cmd=$cmd err=$t")
        }
    }
    
    private fun setBestDialogWindowType(dialog: AlertDialog) {
        val w = dialog.window ?: return

        try {
            w.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
            log("window type=TYPE_KEYGUARD_DIALOG")
        } catch (t: Throwable) {
            log("TYPE_KEYGUARD_DIALOG failed: $t")
            try {
                w.setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG)
                log("window type=TYPE_SYSTEM_DIALOG")
            } catch (t2: Throwable) {
                log("TYPE_SYSTEM_DIALOG failed: $t2")
            }
        }
    }

    private fun requestPowerAction(
        context: Context,
        action: String,
        fallbackCmd: String
    ) {
        try {
            pendingAction = action
            pendingFallbackCmd = fallbackCmd
            pendingRequestedAt = SystemClock.uptimeMillis()

            val intent = Intent(ACTION_RUN_POWER).apply {
                setPackage("android")
                putExtra("token", TOKEN)
                putExtra(EXTRA_ACTION, action)
                putExtra(EXTRA_FALLBACK_CMD, fallbackCmd)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }

            context.sendBroadcast(intent)

            log("request power action=$action fallback=$fallbackCmd")
        } catch (t: Throwable) {
            log("request power action failed: $t")
            when (fallbackCmd) {
                "SAFE_MODE_FALLBACK" -> {
                    execSu("setprop persist.sys.safemode 1")
                    execSu("reboot")
                }
                else -> execSu(fallbackCmd)
            }
        }
    }

    private fun installPowerResultReceiver(context: Context) {
        if (resultReceiverInstalled) return
        resultReceiverInstalled = true
    
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_POWER_RESULT) return
                if (intent.getStringExtra("token") != TOKEN) return
    
                val expectedAction = pendingAction
                val fallbackCmd = pendingFallbackCmd
                val requestedAt = pendingRequestedAt
    
                pendingAction = null
                pendingFallbackCmd = null
                pendingRequestedAt = 0L
    
                val resultAction =
                    intent.getStringExtra(EXTRA_ACTION)
    
                val ok =
                    intent.getBooleanExtra(EXTRA_OK, false)
    
                val err =
                    intent.getStringExtra(EXTRA_ERROR)
    
                val age =
                    SystemClock.uptimeMillis() - requestedAt
    
                if (
                    expectedAction == null ||
                    resultAction != expectedAction ||
                    requestedAt == 0L ||
                    age !in 0..10_000L
                ) {
                    logAlways(
                        "reject unmatched power result: " +
                            "expected=$expectedAction " +
                            "actual=$resultAction " +
                            "age=$age"
                    )
                    return
                }
    
                logAlways(
                    "power result action=$resultAction " +
                        "ok=$ok err=$err " +
                        "fallback=$fallbackCmd"
                )
    
                if (ok || fallbackCmd.isNullOrBlank()) {
                    return
                }
    
                when (fallbackCmd) {
                    "reboot" -> {
                        execSu("reboot")
                    }
    
                    "SAFE_MODE_FALLBACK" -> {
                        execSu(
                            "setprop persist.sys.safemode 1"
                        )
                        execSu("reboot")
                    }
    
                    "setprop ctl.restart zygote" -> {
                        execSu(
                            "setprop ctl.restart zygote"
                        )
                    }
    
                    "am restart" -> {
                        execSu("am restart")
                    }
    
                    "reboot recovery" -> {
                        execSu("reboot recovery")
                    }
    
                    "reboot bootloader" -> {
                        execSu("reboot bootloader")
                    }
    
                    "reboot -p" -> {
                        execSu("reboot -p")
                    }
    
                    "ROOT_LOCKDOWN_FALLBACK" -> {
                        requestRootLockdownFallback(ctx)
                    }
    
                    else -> {
                        logAlways(
                            "reject unknown fallback=$fallbackCmd"
                        )
                    }
                }
            }
        }
    
        val filter = IntentFilter(ACTION_POWER_RESULT)
    
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        
        logAlways("power result receiver installed")
    }
    
    private fun restartSystemUiInternalFallback() {
        try {
            logAlways("trying kill self SystemUI")
            android.os.Process.killProcess(android.os.Process.myPid())
            logAlways("kill self SystemUI")
        } catch (t: Throwable) {
            logAlways("kill self failed: $t")
            execSu("pkill -f com.android.systemui")
            execSu("killall com.android.systemui")
            execSu("am crash com.android.systemui")
        }
    }
    
    private fun doLockdown(context: Context) {
        try {
            val lpuCls = Class.forName("com.android.internal.widget.LockPatternUtils")
            val lpu = lpuCls.getConstructor(Context::class.java).newInstance(context)

            val trackerCls = Class.forName(
                "com.android.internal.widget.LockPatternUtils\$StrongAuthTracker"
            )

            val reason = XposedHelpers.getStaticIntField(
                trackerCls,
            "STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN"
            )

            XposedHelpers.callMethod(
                lpu,
                "requireStrongAuth",
                reason,
                -1
            )

            val smCls = Class.forName("android.os.ServiceManager")
            val binder = XposedHelpers.callStaticMethod(smCls, "getService", "window")

            val stubCls = Class.forName("android.view.IWindowManager\$Stub")
            val wm = XposedHelpers.callStaticMethod(stubCls, "asInterface", binder)

            XposedHelpers.callMethod(wm, "lockNow", null as Bundle?)

            logAlways("lockdown executed")
        } catch (t: Throwable) {
            logAlways("lockdown failed: $t")
        }
    }
    
    private fun doSleep(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

            XposedHelpers.callMethod(
                pm,
                "goToSleep",
                SystemClock.uptimeMillis()
            )

            logAlways("sleep executed")
        } catch (t: Throwable) {
            logAlways("sleep failed: $t")
        }
    }
    
    private fun requestRootLockdownFallback(context: Context): Boolean {
        return try {
            val moduleContext = context.createPackageContext(
                PKG_MODULE,
                Context.CONTEXT_IGNORE_SECURITY
            )
    
            val apkPath = moduleContext.applicationInfo.sourceDir
            if (apkPath.isNullOrBlank()) {
                log("root lockdown fallback failed: module apk path unavailable")
                return false
            }
            
            val command =
                "CLASSPATH=${shellQuote(apkPath)} " +
                "app_process /system/bin " +
                "com.sui.advancedpowermenu.RootLockdown"
    
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", command)
            )
    
            log("root lockdown fallback requested")
            true
        } catch (t: Throwable) {
            log("root lockdown fallback failed: $t")
            false
        }
    }
    
    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
    
    private fun registerScreenOffDismissReceiver(
        context: Context,
        dialog: AlertDialog
    ) {
        unregisterScreenReceiver(context)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    logAlways("screen off -> dismiss advanced dialog")
                    try {
                        dialog.dismiss()
                    } catch (t: Throwable) {
                        logAlways("dismiss on screen off failed: $t")
                    }
                }
            }
        }

        screenReceiver = receiver

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterScreenReceiver(context: Context) {
        val receiver = screenReceiver ?: return

        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }

        screenReceiver = null
    }
    
    private fun log(msg: String) {
        val cfg = config()
        if (!cfg.detailedLog) return
        XposedBridge.log("$TAG: $msg")
    }

    private fun logAlways(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }
}