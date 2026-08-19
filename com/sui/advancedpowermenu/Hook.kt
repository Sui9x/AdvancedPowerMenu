//v2.5

package com.sui.advancedpowermenu

import android.app.*
import android.content.*
import android.os.*
import android.view.KeyEvent
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "AdvancedPowerMenuHook"
        
        private const val PKG_MODULE = "com.sui.advancedpowermenu"
        private const val PKG_ANDROID = "android"
        private const val PKG_SYSTEMUI = "com.android.systemui"
        private const val PERMISSION_STATUS_BAR_SERVICE = "android.permission.STATUS_BAR_SERVICE"

        private const val CLASS_PWM = "com.android.server.policy.PhoneWindowManager"
        private const val CLASS_GA = "com.android.server.policy.GlobalActions"
        
        private const val FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF = 1 shl 1
        
        private const val TOKEN = "sui_advanced_power_menu_internal_token"

        private const val ACTION_SHOW_ADVANCED = "com.sui.advancedpowermenu.action.SHOW"
        private const val ACTION_RUN_POWER = "com.sui.advancedpowermenu.action.RUN_POWER"
        @Volatile private var powerReceiverInstalled = false
        private const val ACTION_POWER_RESULT = "com.sui.advancedpowermenu.action.POWER_RESULT"

        private const val EXTRA_ACTION = "action"
        private const val EXTRA_FALLBACK_CMD = "fallback_cmd"
        private const val EXTRA_OK = "ok"
        private const val EXTRA_ERROR = "error"
        
        private const val FORCE_POWER_INTERNAL_FAIL_TEST = false

        private var pwmContext: Context? = null

        private var powerDown = false
        private var volUpDown = false
        private var lastPowerVolUpAt = 0L
        
        @Volatile private var systemUiContext: Context? = null
        
        @Volatile private var lastSystemUiReceiveAt = 0L
        private val systemUiReceiveLock = Any()
        private val receiverInstallLock = Any()

        private var systemUiReceiverInstalled = false
    }
    
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
            logAlways("config read failed: $t")
            cachedConfig
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            PKG_ANDROID -> hookAndroid(lpparam)
            PKG_SYSTEMUI -> hookSystemUI(lpparam)
        }
    }

    private fun hookAndroid(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        runHook("hookPwmInit") {
            hookPwmInit(lpparam)
        }
    
        runHook("hookKeyState") {
            hookKeyState(lpparam)
        }
    
        runHook("hookShowGlobalActions") {
            hookShowGlobalActions(lpparam)
        }
        
        runHook("hookPowerLongPress") {
            hookPowerLongPress(lpparam)
        }
    
        runHook("hookShowGlobalActionsInternal") {
            hookShowGlobalActionsInternal(lpparam)
        }
    
        runHook("hookShowDialog") {
            hookShowDialog(lpparam)
        }
    }
    
    private inline fun runHook(
        name: String,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (t: Throwable) {
            logAlways("$name uncaught failure: $t")
        }
    }

    private fun hookPwmInit(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        val cl = lpparam.classLoader
    
        val funcsClass = try {
            XposedHelpers.findClass(
                "com.android.server.policy.WindowManagerPolicy\$WindowManagerFuncs",
                cl
            )
        } catch (t: Throwable) {
            logAlways("PhoneWindowManager#init: WindowManagerFuncs not found: $t")
            return
        }
    
        val iwmClass = try {
            XposedHelpers.findClass(
                "android.view.IWindowManager",
                cl
            )
        } catch (_: Throwable) {
            null
        }
    
        val signatures = ArrayList<Array<Class<*>>>()
    
        signatures += arrayOf(
            Context::class.java,
            funcsClass
        )
    
        if (iwmClass != null) {
            signatures += arrayOf(
                Context::class.java,
                iwmClass,
                funcsClass
            )
    
            signatures += arrayOf(
                Context::class.java,
                funcsClass,
                iwmClass
            )
        }
    
        for (sig in signatures) {
            try {
                XposedHelpers.findAndHookMethod(
                    CLASS_PWM,
                    cl,
                    "init",
                    *sig,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(
                            param: MethodHookParam
                        ) {
                            pwmContext = param.args[0] as? Context
                            pwmContext?.let {
                                installPowerReceiver(it)
                            }
    
                            logAlways(
                                "PhoneWindowManager#init hooked, context=$pwmContext"
                            )
                        }
                    }
                )
    
                logAlways(
                    "PhoneWindowManager#init signature hooked: ${sig.size} args"
                )
                return
            } catch (_: Throwable) {
            }
        }
    
        logAlways("PhoneWindowManager#init hook failed")
    }

    private fun hookKeyState(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                CLASS_PWM,
                lpparam.classLoader,
                "interceptKeyBeforeQueueing",
                KeyEvent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        log("PhoneWindowManager.interceptKeyBeforeQueueing()")
                        val ev = param.args[0] as? KeyEvent ?: return
                        if ((ev.flags and KeyEvent.FLAG_FROM_SYSTEM) == 0) return

                        val down = ev.action == KeyEvent.ACTION_DOWN
                        val up = ev.action == KeyEvent.ACTION_UP

                        when (ev.keyCode) {
                            KeyEvent.KEYCODE_POWER -> {
                                if (down) powerDown = true
                                if (up) powerDown = false
                            }

                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                if (down) volUpDown = true
                                if (up) volUpDown = false
                            }
                        }

                        if (powerDown && volUpDown) {
                            lastPowerVolUpAt = SystemClock.uptimeMillis()
                            log("Power+VolUp marked")
                        }
                    }
                }
            )
            logAlways("PhoneWindowManager#interceptKeyBeforeQueueing hooked")
        } catch (t: Throwable) {
            logAlways("PhoneWindowManager#interceptKeyBeforeQueueing hook failed: $t")
        }
    }

    private fun hookShowGlobalActions(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                CLASS_PWM,
                lpparam.classLoader,
                "showGlobalActions",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        log("PhoneWindowManager.showGlobalActions()")
                        val cfg = config()
                        if (!cfg.enabled) return
                        if (cfg.workaroundPower) return
                        
                        val now = SystemClock.uptimeMillis()
                        val fromPowerVolUp = now - lastPowerVolUpAt in 0..500

                        if (!fromPowerVolUp) return
                        
                        if (cfg.excludeKeyguard && isKeyguardShowingCompat(param.thisObject)) {
                            log("keyguard showing; pass original")
                            return
                        }

                        log("PhoneWindowManager#showGlobalActions intercepted from Power+VolUp")

                        lastPowerVolUpAt = 0L
                        sendShowAdvancedBroadcast()

                        param.result = null
                    }
                }
            )
            logAlways("PhoneWindowManager#showGlobalActions hooked")
        } catch (t: Throwable) {
            logAlways("PhoneWindowManager#showGlobalActions hook failed: $t")
        }
    }
    
    private fun hookShowGlobalActionsInternal(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                CLASS_PWM,
                lpparam.classLoader,
                "showGlobalActionsInternal",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        log("PhoneWindowManager.showGlobalActionsInternal()")
                        
                        val cfg = config()
                        if (!cfg.enabled) return
                        if (!cfg.workaroundPower) {
                            log("workaround is false")
                            return
                        }

                        if (cfg.excludeKeyguard && isKeyguardShowingCompat(param.thisObject)) {
                            log("keyguard showing; pass original internal")
                            return
                        }

                        log("PhoneWindowManager#showGlobalActionsInternal intercepted")

                        sendShowAdvancedBroadcast()
                        param.result = null
                    }
                }
            )
            logAlways("PhoneWindowManager#showGlobalActionsInternal hooked")
        } catch (t: Throwable) {
            logAlways("PhoneWindowManager#showGlobalActionsInternal hook failed: $t")
        }
    }
    
    private fun hookShowDialog(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                CLASS_GA,
                lpparam.classLoader,
                "showDialog",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        log("GlobalActions.showDialog()")
    
                        val cfg = config()
                        if (!cfg.enabled) return
                        if (!cfg.workaroundPower) return
                        
                        val keyguardShowing = param.args[0] as? Boolean ?: false
    
                        if (cfg.excludeKeyguard && keyguardShowing) {
                            log("keyguard showing; pass original policy GlobalActions")
                            return
                        }
    
                        log("GlobalActions#showDialog intercepted")
    
                        sendShowAdvancedBroadcast()
                        param.result = null
                    }
                }
            )
            logAlways("GlobalActions#showDialog hooked")
        } catch (t: Throwable) {
            logAlways("GlobalActions#showDialog hook failed: $t")
        }
    }
    
    private fun hookPowerLongPress(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                CLASS_PWM,
                lpparam.classLoader,
                "powerLongPress",
                Long::class.javaPrimitiveType, // eventTime
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        log("PhoneWindowManager.powerLongPress()")
                        
                        val cfg = config()
                        if (!cfg.enabled) return
                        if (!cfg.workaroundPower) {
                            log("workaround is false")
                            return
                        }

                        if (cfg.excludeKeyguard && isKeyguardShowingCompat(param.thisObject)) {
                            log("keyguard showing; pass original internal")
                            return
                        }
                        
                        markPowerKeyHandled(param.thisObject)
                        
                        getPwmContext(param.thisObject)?.let { context ->
                            vibrateLongPress(context)
                        }
                        
                        sendShowAdvancedBroadcast()
                        param.result = null
                    }
                }
            )
            logAlways("PhoneWindowManager#powerLongPress hooked")
        } catch (t: Throwable) {
            try {
                XposedHelpers.findAndHookMethod(
                    CLASS_PWM,
                    lpparam.classLoader,
                    "powerLongPress",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            log("PhoneWindowManager.powerLongPress()")
                            
                            val cfg = config()
                            if (!cfg.enabled) return
                            if (!cfg.workaroundPower) {
                                log("workaround is false")
                                return
                            }
    
                            if (cfg.excludeKeyguard && isKeyguardShowingCompat(param.thisObject)) {
                                log("keyguard showing; pass original internal")
                                return
                            }
                            
                            markPowerKeyHandled(param.thisObject)
                            
                            getPwmContext(param.thisObject)?.let { context ->
                                vibrateLongPress(context)
                            }
                            
                            sendShowAdvancedBroadcast()
                            param.result = null
                        }
                    }
                )
                logAlways("PhoneWindowManager#powerLongPress hooked")
            } catch (t: Throwable) {
                logAlways("PhoneWindowManager#powerLongPress hook failed: $t")
            }
        }
    }
    
    private fun markPowerKeyHandled(pwm: Any): Boolean {
        return try {
            XposedHelpers.setBooleanField(
                pwm,
                "mPowerKeyHandled",
                true
            )
            log("PhoneWindowManager#mPowerKeyHandled=true")
            true
        } catch (t: Throwable) {
            log("markPowerKeyHandled failed: $t")
            false
        }
    }
    
    private fun hookQsGlobalActions(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.globalactions.GlobalActionsDialogLite",
                lpparam.classLoader
            )
    
            var hooked = 0
    
            for (method in cls.declaredMethods) {
                // A15/16: showOrHideDialog(...)
                // A17: showOrHideDialog(...) showDialog(...)
                if (
                    method.name != "showOrHideDialog" &&
                    method.name != "showDialog"
                ) {
                    continue
                }
    
                val types = method.parameterTypes
                
                // A15: (boolean, boolean, Expandable)
                //A16/17: (boolean, boolean, Expandable, int displayId)
                if (types.size != 3 && types.size != 4) continue
    
                if (types[0] != Boolean::class.javaPrimitiveType) continue
                if (types[1] != Boolean::class.javaPrimitiveType) continue
    
                if (
                    types[2].name !=
                    "com.android.systemui.animation.Expandable"
                ) {
                    continue
                }
                
                // A16/17
                if (
                    types.size == 4 &&
                    types[3] != Int::class.javaPrimitiveType
                ) {
                    continue
                }
    
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            log("GlobalActionsDialogLite.${method.name}()")
                            
                            val cfg = config()
    
                            if (!cfg.enabled) return
                            if (!cfg.enabledQs) return
                            
                            val expandable = param.args.getOrNull(2)
    
                            if (expandable == null) {
                                log(
                                    "${method.name}: " +
                                        "Not QS path; pass"
                                )
                                return
                            }
    
                            val keyguardShowing =
                                (param.args.getOrNull(0) as? Boolean ?: false) ||
                                    isSystemUiKeyguardLocked()
    
                            if (
                                cfg.excludeKeyguard &&
                                keyguardShowing
                            ) {
                                log(
                                    "${method.name}: " +
                                        "QS power menu on keyguard; pass"
                                )
                                return
                            }
    
                            val displayId =
                                if (param.args.size >= 4) {
                                    param.args[3] as? Int
                                } else {
                                    null
                                }
    
                            log(
                                "${method.name}: QS intercepted " +
                                    "args=${param.args.size}" +
                                    if (displayId != null) {
                                        " displayId=$displayId"
                                    } else {
                                        ""
                                    }
                            )
    
                            val context = systemUiContext ?: run {
                                log(
                                    "${method.name}: " +
                                        "SystemUI context unavailable; pass"
                                )
                                return
                            }
                            
                            param.result = null
    
                            Handler(Looper.getMainLooper()).post {
                                AdvancedPowerMenuDialog.show(context)
                            }
                        }
                    }
                )
    
                hooked++
    
                logAlways(
                    "GlobalActionsDialogLite#${method.name} hooked " +
                        method.parameterTypes.joinToString(
                            prefix = "(",
                            postfix = ")"
                        ) { it.name }
                )
            }
    
            if (hooked == 0) {
                logAlways(
                    "GlobalActionsDialogLite: " +
                        "compatible show method not found"
                )

                // unknown signature
                for (method in cls.declaredMethods) {
                    if (
                        method.name != "showOrHideDialog" &&
                        method.name != "showDialog"
                    ) {
                        continue
                    }
    
                    logAlways(
                        "Unsupported ${method.name} signature: " +
                            method.parameterTypes.joinToString(
                                prefix = "(",
                                postfix = ")"
                            ) { it.name }
                    )
                }
            } else {
                logAlways(
                    "QS GlobalActions hooked: $hooked"
                )
            }
        } catch (t: Throwable) {
            logAlways("QS GlobalActions hook failed: $t")
        }
    }
    
    private fun hookSystemUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.application.impl.SystemUIApplicationImpl", //A17
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as Application

                        val proc = currentProcessName(app)
                        logAlways("SystemUI onCreate process=$proc pid=${android.os.Process.myPid()}")

                        if (proc != PKG_SYSTEMUI) {
                            logAlways("skip receiver install: non-main SystemUI process=$proc")
                            return
                        }
                        
                        synchronized(receiverInstallLock) {
                            if (systemUiReceiverInstalled) return
                            systemUiReceiverInstalled = true
                        }
                        
                        systemUiContext = app

                        installReceiver(app)
                        logAlways("SystemUI receiver installed")
                        
                        hookQsGlobalActions(lpparam)
                    }
                }
            )
        } catch (t: Throwable) {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.systemui.SystemUIApplication",
                    lpparam.classLoader,
                    "onCreate",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val app = param.thisObject as Application
    
                            val proc = currentProcessName(app)
                            logAlways("SystemUI onCreate process=$proc pid=${android.os.Process.myPid()}")
    
                            if (proc != PKG_SYSTEMUI) {
                                logAlways("skip receiver install: non-main SystemUI process=$proc")
                                return
                            }
                            
                            synchronized(receiverInstallLock) {
                                if (systemUiReceiverInstalled) return
                                systemUiReceiverInstalled = true
                            }
                            
                            systemUiContext = app
    
                            installReceiver(app)
                            logAlways("SystemUI receiver installed")
                            
                            hookQsGlobalActions(lpparam)
                        }
                    }
                )
            } catch (t: Throwable) {
                logAlways("SystemUI hook failed: $t")
            }
        }
    }
    
    private fun showAdvancedFromSystemUi(
        context: Context
    ) {
        Handler(Looper.getMainLooper()).post {
            AdvancedPowerMenuDialog.show(context)
        }
    }

    private fun installReceiver(app: Application) {
        logAlways(
            "installReceiver" +
                " process=${currentProcessName(app)}" +
                " uid=${Process.myUid()}" +
                " sdk=${Build.VERSION.SDK_INT}"
        )
    
        val filter = IntentFilter(ACTION_SHOW_ADVANCED)
    
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                log(
                    "receiver onReceive" +
                        " process=${currentProcessName(context)}" +
                        " action=${intent.action}" +
                        " tokenMatch=${intent.getStringExtra("token") == TOKEN}"
                )
    
                if (currentProcessName(context) != PKG_SYSTEMUI) {
                    log("receiver skip non-main process=${currentProcessName(context)}")
                    return
                }
    
                if (intent.action != ACTION_SHOW_ADVANCED) {
                    log("receiver skip unexpected action=${intent.action}")
                    return
                }
    
                if (intent.getStringExtra("token") != TOKEN) {
                    log("reject broadcast: bad token")
                    return
                }
    
                val now = SystemClock.uptimeMillis()
                synchronized(systemUiReceiveLock) {
                    if (now - lastSystemUiReceiveAt < 250) {
                        log("skip receiver: debounce")
                        return
                    }
                    lastSystemUiReceiveAt = now
                }
    
                log("receiver accepted")
    
                Handler(Looper.getMainLooper()).post {
                    log("calling show")
                    AdvancedPowerMenuDialog.show(context)
                }
            }
        }
    
        logAlways(
            "registerReceiver" +
                " action=$ACTION_SHOW_ADVANCED" +
                " permission=$PERMISSION_STATUS_BAR_SERVICE" +
                " exported=${Build.VERSION.SDK_INT >= 33}"
        )
    
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(
                    receiver,
                    filter,
                    PERMISSION_STATUS_BAR_SERVICE,
                    null,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(
                    receiver,
                    filter,
                    PERMISSION_STATUS_BAR_SERVICE,
                    null
                )
            }
    
            logAlways("registerReceiver success")
        } catch (t: Throwable) {
            logAlways(
                "registerReceiver failed: " +
                    android.util.Log.getStackTraceString(t)
            )
            throw t
        }
    }

    private fun sendShowAdvancedBroadcast() {
        val ctx = pwmContext ?: run {
            log("send failed: pwmContext null")
            return
        }

        try {
            val intent = Intent(ACTION_SHOW_ADVANCED).apply {
                setPackage(PKG_SYSTEMUI)
                putExtra("token", TOKEN)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }

            val userHandle = XposedHelpers.getStaticObjectField(
                UserHandle::class.java,
                "CURRENT"
            ) as UserHandle

            XposedHelpers.callMethod(ctx, "sendBroadcastAsUser", intent, userHandle)

            log("broadcast sent")
        } catch (t: Throwable) {
            log("broadcast failed: $t")
        }
    }
    
    private fun installPowerReceiver(context: Context) {
        if (powerReceiverInstalled) return
        powerReceiverInstalled = true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_RUN_POWER) return
                if (intent.getStringExtra("token") != TOKEN) {
                    logAlways("power receiver reject: bad token")
                    return
                }
                
                val cfg = config()

                val action = intent.getStringExtra(EXTRA_ACTION) ?: return
                val fallbackCmd = intent.getStringExtra(EXTRA_FALLBACK_CMD)

                var ok = false
                var error: String? = null

                val id = Binder.clearCallingIdentity()
                try {
                    ok = if (FORCE_POWER_INTERNAL_FAIL_TEST) {
                        error = "forced fail test"
                        false
                    } else if (cfg.forceFallback) {
                        error = "force fallback"
                        false
                    } else {
                        when (action) {
                            "reboot" -> rebootFromSystemServer(null)
                            "safemode" -> {
                                rebootSafeModeFromSystemServer() ||
                                    rebootSafeModeByPropFromSystemServer()
                            }
                            "system_server" -> restartSystemServerFromSystemServer()
                            "zygote" -> restartZygoteByProp()
                            //"recovery" -> rebootFromSystemServer("recovery") // The data initialization string will be displayed
                            "recovery" -> rebootFromSystemServer("recovery,") // Bypass the data initialization string by ","
                            "bootloader" -> rebootFromSystemServer("bootloader")
                            "poweroff" -> shutdownFromSystemServer()
                            "lockdown" -> lockdownFromSystemServer()
                            else -> false
                        }
                    }
                } catch (t: Throwable) {
                    error = t.toString()
                    ok = false
                } finally {
                    Binder.restoreCallingIdentity(id)
                }

                sendPowerResult(
                    context = ctx,
                    action = action,
                    ok = ok,
                    error = error,
                    fallbackCmd = fallbackCmd
                )
            }
        }

        val filter = IntentFilter(ACTION_RUN_POWER)

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    
        logAlways("power receiver installed in system_server")
    }
    
    private fun sendPowerResult(
        context: Context,
        action: String,
        ok: Boolean,
        error: String?,
        fallbackCmd: String?
    ) {
        try {
            val intent = Intent(ACTION_POWER_RESULT).apply {
                setPackage(PKG_SYSTEMUI)
                putExtra("token", TOKEN)
                putExtra(EXTRA_ACTION, action)
                putExtra(EXTRA_OK, ok)
                putExtra(EXTRA_ERROR, error)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
    
            val userHandle = XposedHelpers.getStaticObjectField(
                UserHandle::class.java,
                "CURRENT"
            ) as UserHandle
    
            XposedHelpers.callMethod(
                context,
                "sendBroadcastAsUser",
                intent,
                userHandle
            )
    
            logAlways("power result sent action=$action ok=$ok")
        } catch (t: Throwable) {
            logAlways("send power result failed: $t")
        }
    }
    
    private fun vibrateLongPress(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(
                    VibratorManager::class.java
                ) ?: run {
                    log("vibrateLongPress: VibratorManager is null")
                    return
                }
    
                val vibrator = manager.defaultVibrator
    
                if (!vibrator.hasVibrator()) {
                    log("vibrateLongPress: vibrator unavailable")
                    return
                }
    
                val effect = VibrationEffect.createOneShot(
                    40L,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
    
                val attributes = VibrationAttributes.Builder()
                    .setUsage(
                        VibrationAttributes.USAGE_HARDWARE_FEEDBACK
                    )
                    .setFlags(
                        FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF,
                        FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF
                    )
                    .build()
    
                vibrator.vibrate(effect, attributes)
    
                log(
                    "vibrateLongPress: requested modern " +
                        "flags=${attributes.flags}"
                )
                return
            }
    
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(
                Context.VIBRATOR_SERVICE
            ) as? Vibrator ?: run {
                log("vibrateLongPress: legacy Vibrator is null")
                return
            }
    
            if (!vibrator.hasVibrator()) {
                log("vibrateLongPress: legacy vibrator unavailable")
                return
            }
    
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        40L,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40L)
            }
    
            log("vibrateLongPress: requested legacy")
        } catch (t: Throwable) {
            log("vibrateLongPress failed: $t")
        }
    }
    
    private fun rebootFromSystemServer(reason: String?): Boolean {
        return try {
            val ctx = pwmContext ?: return false
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

            pm.reboot(reason)

            logAlways("internal reboot requested reason=$reason")
            true
        } catch (t: Throwable) {
            logAlways("internal reboot failed reason=$reason err=$t")
            false
        }
    }

    private fun shutdownFromSystemServer(): Boolean {
        return try {
            val ctx = pwmContext ?: return false
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

            try {
                XposedHelpers.callMethod(pm, "shutdown", false, null, false)
            } catch (_: Throwable) {
                XposedHelpers.callMethod(pm, "shutdown", false, "userrequested", false)
            }

            logAlways("internal shutdown requested")
            true
        } catch (t: Throwable) {
            logAlways("internal shutdown failed err=$t")
            false
        }
    }
    
    private fun rebootSafeModeFromSystemServer(): Boolean {
        return try {
            val binder = XposedHelpers.callStaticMethod(
                Class.forName("android.os.ServiceManager"),
                "getService",
                "power"
            )

            val stub = Class.forName("android.os.IPowerManager\$Stub")
            val ipm = XposedHelpers.callStaticMethod(stub, "asInterface", binder)

            XposedHelpers.callMethod(
                ipm,
                "rebootSafeMode",
                false,
                false
            )

            logAlways("internal safemode requested")
            true
        } catch (t: Throwable) {
            logAlways("internal safemode failed: $t")
            false
        }
    }

    private fun rebootSafeModeByPropFromSystemServer(): Boolean {
        var propSet = false

        return try {
            XposedHelpers.callStaticMethod(
                Class.forName("android.os.SystemProperties"),
                "set",
                "persist.sys.safemode",
                "1"
            )

            propSet = true
            logAlways("set persist.sys.safemode=1")

            val ok = rebootFromSystemServer(null)

            if (!ok) {
                XposedHelpers.callStaticMethod(
                    Class.forName("android.os.SystemProperties"),
                    "set",
                    "persist.sys.safemode",
                    "0"
                )
                logAlways("reboot failed; reset persist.sys.safemode=0")
            }

            ok
        } catch (t: Throwable) {
            if (propSet) {
                try {
                    XposedHelpers.callStaticMethod(
                        Class.forName("android.os.SystemProperties"),
                        "set",
                        "persist.sys.safemode",
                        "0"
                    )
                } catch (_: Throwable) {
                }
            }
    
            logAlways("safemode by prop failed: $t")
            false
        }
    }
    
    private fun restartSystemServerFromSystemServer(): Boolean {
        return try {
            val binder = XposedHelpers.callStaticMethod(
                Class.forName("android.os.ServiceManager"),
                "getService",
                "activity"
            )

            val stub = Class.forName("android.app.IActivityManager\$Stub")
            val iam = XposedHelpers.callStaticMethod(stub, "asInterface", binder)

            XposedHelpers.callMethod(iam, "restart")

            logAlways("soft reboot internal: IActivityManager.restart")
            true
        } catch (t: Throwable) {
            logAlways("soft reboot internal failed: $t")
            false
        }
    }
    
    fun restartZygoteByProp(): Boolean {
        return try {
            XposedHelpers.callStaticMethod(
                Class.forName("android.os.SystemProperties"),
                "set",
                "ctl.restart",
                "zygote"
            )
            logAlways("soft reboot internal: ctl.restart zygote")
            true
        } catch (t: Throwable) {
            false
        }
    }
    
    private fun lockdownFromSystemServer(): Boolean {
        return try {
            val ctx = pwmContext ?: return false
    
            val lpuCls = Class.forName(
                "com.android.internal.widget.LockPatternUtils"
            )
    
            val lpu = lpuCls
                .getConstructor(Context::class.java)
                .newInstance(ctx)
    
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
    
            val serviceManagerCls = Class.forName(
                "android.os.ServiceManager"
            )
    
            val windowBinder = XposedHelpers.callStaticMethod(
                serviceManagerCls,
                "getService",
                "window"
            ) ?: return false
    
            val windowStubCls = Class.forName(
                "android.view.IWindowManager\$Stub"
            )
    
            val windowManager = XposedHelpers.callStaticMethod(
                windowStubCls,
                "asInterface",
                windowBinder
            ) ?: return false
    
            XposedHelpers.callMethod(
                windowManager,
                "lockNow",
                null as Bundle?
            )
    
            logAlways("lockdown main completed from system_server")

            try {
                lockEnabledProfilesFromSystemServer()
            } catch (t: Throwable) {
                logAlways("lockdown profile step failed: $t")
            }
            
            true
        } catch (t: Throwable) {
            logAlways("lockdown from system_server failed: $t")
            false
        }
    }
    
    private fun lockEnabledProfilesFromSystemServer() {
        try {
            val serviceManagerCls = Class.forName("android.os.ServiceManager")
    
            val userBinder = XposedHelpers.callStaticMethod(
                serviceManagerCls,
                "getService",
                "user"
            ) as? IBinder ?: run {
                logAlways("profile lock skipped: user service unavailable")
                return
            }
    
            val userManager = XposedHelpers.callStaticMethod(
                Class.forName("android.os.IUserManager\$Stub"),
                "asInterface",
                userBinder
            ) ?: run {
                logAlways("profile lock skipped: IUserManager unavailable")
                return
            }
    
            val trustBinder = XposedHelpers.callStaticMethod(
                serviceManagerCls,
                "getService",
                "trust"
            ) as? IBinder ?: run {
                logAlways("profile lock skipped: trust service unavailable")
                return
            }
    
            val trustManager = XposedHelpers.callStaticMethod(
                Class.forName("android.app.trust.ITrustManager\$Stub"),
                "asInterface",
                trustBinder
            ) ?: run {
                logAlways("profile lock skipped: ITrustManager unavailable")
                return
            }
    
            val currentUserId = getCurrentUserIdCompat()
    
            val profileIds = getProfileIdsCompat(
                userManager = userManager,
                currentUserId = currentUserId
            )
    
            for (profileId in profileIds) {
                if (profileId == currentUserId) continue
    
                try {
                    XposedHelpers.callMethod(
                        trustManager,
                        "setDeviceLockedForUser",
                        profileId,
                        true
                    )
    
                    logAlways("lockdown profile locked userId=$profileId")
                } catch (t: Throwable) {
                    logAlways(
                        "lockdown profile failed userId=$profileId err=$t"
                    )
                }
            }
        } catch (t: Throwable) {
            logAlways("lockEnabledProfiles failed: $t")
        }
    }
    
    private fun getProfileIdsCompat(
        userManager: Any,
        currentUserId: Int
    ): IntArray {
        try {
            return XposedHelpers.callMethod(
                userManager,
                "getProfileIds",
                currentUserId,
                true
            ) as? IntArray ?: intArrayOf()
        } catch (_: Throwable) {
        }
    
        try {
            return XposedHelpers.callMethod(
                userManager,
                "getEnabledProfileIds",
                currentUserId
            ) as? IntArray ?: intArrayOf()
        } catch (t: Throwable) {
            logAlways("profile ID lookup failed: $t")
        }
    
        return intArrayOf()
    }
    
    private fun getCurrentUserIdCompat(): Int {
        try {
            return XposedHelpers.callStaticMethod(
                ActivityManager::class.java,
                "getCurrentUser"
            ) as Int
        } catch (_: Throwable) {
        }
    
        try {
            val userHandleCls = Class.forName("android.os.UserHandle")
            return XposedHelpers.callStaticMethod(
                userHandleCls,
                "myUserId"
            ) as Int
        } catch (_: Throwable) {
        }
        
        return 0
    }

    private fun isKeyguardShowingCompat(pwm: Any): Boolean {
        try {
            return XposedHelpers.callMethod(
                pwm,
                "isKeyguardShowingAndNotOccluded"
            ) as? Boolean ?: false
        } catch (_: Throwable) {
        }

        try {
            val delegate = XposedHelpers.getObjectField(pwm, "mKeyguardDelegate")
            return XposedHelpers.callMethod(delegate, "isShowing") as? Boolean ?: false
        } catch (_: Throwable) {
        }

        return false
    }
    
    private fun isSystemUiKeyguardLocked(): Boolean {
        val context = systemUiContext ?: return false
    
        return try {
            val km = context.getSystemService(KeyguardManager::class.java)
            km?.isKeyguardLocked == true
        } catch (t: Throwable) {
            log("isSystemUiKeyguardLocked failed: $t")
            false
        }
    }
    
    private fun currentProcessName(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName()
            } else {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val pid = android.os.Process.myPid()
                am.runningAppProcesses
                    ?.firstOrNull { it.pid == pid }
                    ?.processName ?: "unknown"
            }
        } catch (t: Throwable) {
            "unknown:${t.javaClass.simpleName}"
        }
    }
    
    private fun getPwmContext(pwm: Any): Context? {
        return try {
            XposedHelpers.getObjectField(pwm, "mContext") as? Context
        } catch (_: Throwable) {
            log("getPwmContext: Context null")
            null
        }
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
