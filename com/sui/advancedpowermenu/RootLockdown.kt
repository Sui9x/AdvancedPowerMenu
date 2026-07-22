package com.sui.advancedpowermenu

import android.app.ActivityManager
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle
import java.lang.reflect.Method

object RootLockdown {

    private const val TAG = "AdvancedPowerMenuRoot"

    private const val USER_ALL = -1
    private const val STRONG_AUTH_AFTER_USER_LOCKDOWN = 0x20

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")

            val lockSettings = getBinderInterface(
                serviceManager = serviceManager,
                serviceName = "lock_settings",
                stubClassName = "com.android.internal.widget.ILockSettings\$Stub"
            ) ?: error("ILockSettings unavailable")

            val windowManager = getBinderInterface(
                serviceManager = serviceManager,
                serviceName = "window",
                stubClassName = "android.view.IWindowManager\$Stub"
            ) ?: error("IWindowManager unavailable")

            callRequireStrongAuth(lockSettings)
            log("requireStrongAuth completed")

            callLockNow(windowManager)
            log("lockNow completed")

            try {
                lockEnabledProfiles(serviceManager)
            } catch (t: Throwable) {
                log("profile lock failed, main lockdown remains valid: $t")
            }

            log("root lockdown completed")
        } catch (t: Throwable) {
            log("root lockdown failed: $t")
            throw t
        }
    }

    private fun getBinderInterface(
        serviceManager: Class<*>,
        serviceName: String,
        stubClassName: String
    ): Any? {
        val binder = serviceManager
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, serviceName) as? IBinder
            ?: return null

        val stub = Class.forName(stubClassName)
        val asInterface = stub.getDeclaredMethod(
            "asInterface",
            IBinder::class.java
        )

        return asInterface.invoke(null, binder)
    }

    private fun callRequireStrongAuth(lockSettings: Any) {
        val method = findMethod(
            lockSettings,
            name = "requireStrongAuth"
        ) { types ->
            types.size == 2 &&
                types[0] == Int::class.javaPrimitiveType &&
                types[1] == Int::class.javaPrimitiveType
        } ?: error("requireStrongAuth(int,int) unavailable")

        method.invoke(
            lockSettings,
            STRONG_AUTH_AFTER_USER_LOCKDOWN,
            USER_ALL
        )
    }

    private fun callLockNow(windowManager: Any) {
        val method = findMethod(
            windowManager,
            name = "lockNow"
        ) { types ->
            types.size == 1 &&
                Bundle::class.java.isAssignableFrom(types[0])
        } ?: error("lockNow(Bundle) unavailable")

        method.invoke(windowManager, null)
    }

    private fun lockEnabledProfiles(serviceManager: Class<*>) {
        val userManager = getBinderInterface(
            serviceManager = serviceManager,
            serviceName = "user",
            stubClassName = "android.os.IUserManager\$Stub"
        ) ?: run {
            log("profile lock skipped: IUserManager unavailable")
            return
        }

        val trustManager = getBinderInterface(
            serviceManager = serviceManager,
            serviceName = "trust",
            stubClassName = "android.app.trust.ITrustManager\$Stub"
        ) ?: run {
            log("profile lock skipped: ITrustManager unavailable")
            return
        }

        val currentUserId = getCurrentUserIdCompat()
        val profileIds = getProfileIdsCompat(userManager, currentUserId)

        if (profileIds.isEmpty()) {
            log("profile lock: no profiles")
            return
        }

        val lockMethod = findMethod(
            trustManager,
            name = "setDeviceLockedForUser"
        ) { types ->
            types.size == 2 &&
                types[0] == Int::class.javaPrimitiveType &&
                types[1] == Boolean::class.javaPrimitiveType
        } ?: run {
            log("profile lock skipped: setDeviceLockedForUser unavailable")
            return
        }

        for (profileId in profileIds) {
            if (profileId == currentUserId) continue

            try {
                lockMethod.invoke(
                    trustManager,
                    profileId,
                    true
                )
                log("profile locked userId=$profileId")
            } catch (t: Throwable) {
                log("profile lock failed userId=$profileId err=$t")
            }
        }
    }

    private fun getProfileIdsCompat(
        userManager: Any,
        currentUserId: Int
    ): IntArray {
        //getProfileIds(int userId, boolean enabledOnly)
        val method = findMethod(
            userManager,
            name = "getProfileIds"
        ) { types ->
            types.size == 2 &&
                types[0] == Int::class.javaPrimitiveType &&
                types[1] == Boolean::class.javaPrimitiveType
        }

        if (method != null) {
            val result = method.invoke(
                userManager,
                currentUserId,
                true
            )
            return result as? IntArray ?: intArrayOf()
        }

        //Differential API
        val enabledMethod = findMethod(
            userManager,
            name = "getEnabledProfileIds"
        ) { types ->
            types.size == 1 &&
                types[0] == Int::class.javaPrimitiveType
        }

        if (enabledMethod != null) {
            val result = enabledMethod.invoke(
                userManager,
                currentUserId
            )
            return result as? IntArray ?: intArrayOf()
        }

        log("profile lock skipped: profile ID method unavailable")
        return intArrayOf()
    }

    private fun getCurrentUserIdCompat(): Int {
        try {
            val method = ActivityManager::class.java.getDeclaredMethod(
                "getCurrentUser"
            )
            method.isAccessible = true
            return method.invoke(null) as Int
        } catch (_: Throwable) {
        }
    
        try {
            val userHandleCls = Class.forName("android.os.UserHandle")
            val method = userHandleCls.getDeclaredMethod("myUserId")
            method.isAccessible = true
            return method.invoke(null) as Int
        } catch (_: Throwable) {
        }
    
        return 0
    }

    private fun findMethod(
        target: Any,
        name: String,
        predicate: (Array<Class<*>>) -> Boolean
    ): Method? {
        val methods = LinkedHashSet<Method>()

        methods.addAll(target.javaClass.methods)
        methods.addAll(target.javaClass.declaredMethods)

        for (method in methods) {
            if (method.name != name) continue
            if (!predicate(method.parameterTypes)) continue

            method.isAccessible = true
            return method
        }

        return null
    }

    private fun log(msg: String) {
        android.util.Log.i(TAG, msg)
    }
}