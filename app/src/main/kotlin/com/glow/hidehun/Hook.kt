package com.glow.hidehun

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * HideHUN - suppresses the system heads-up banner for chosen packages,
 * while keeping the notification itself (shade, listeners, island modules).
 *
 * Android 15+ / 16 / 17 (incl. Samsung One UI 8) no longer have
 * HeadsUpManager.shouldHeadsUp(), so the decision is taken from
 * VisualInterruptionDecisionProviderImpl.makeLoggablePeekDecision(entry).
 */
class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "HideHUN"

        /** Packages whose heads-up banners are suppressed. */
        private val TARGET_PACKAGES = setOf(
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
        )

        private const val CLS_DECISION_PROVIDER =
            "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl"
        private const val CLS_LOGGABLE_DECISION =
            "com.android.systemui.statusbar.notification.interruption.LoggableDecision"
        private const val CLS_SUPPRESSOR =
            "com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor"
        private const val CLS_POSTED_ENTRY =
            "com.android.systemui.statusbar.notification.collection.coordinator.HeadsUpCoordinator\$PostedEntry"
    }

    private var cachedSuppressed: Any? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        val cl = lpparam.classLoader
        XposedBridge.log("$TAG: loaded in ${lpparam.processName} (uid=${lpparam.appInfo?.uid})")

        val a = hookDecisionProvider(cl)
        val b = hookPostedEntry(cl)
        XposedBridge.log("$TAG: hooks installed: decisionProvider=$a postedEntry=$b")
    }

    // ---------------------------------------------------------------- hooks

    /** Main point: veto the heads-up decision for our packages. */
    private fun hookDecisionProvider(cl: ClassLoader): Int {
        val cls = XposedHelpers.findClassIfExists(CLS_DECISION_PROVIDER, cl) ?: return 0
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.parameterCount != 1) continue
            // Only the LoggableDecision-returning entry point may be replaced.
            if (m.name != "makeLoggablePeekDecision") continue
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pkg = packageOf(param.args.getOrNull(0)) ?: return
                        if (pkg !in TARGET_PACKAGES) return
                        val replacement = suppressedDecision(cls.classLoader)
                        if (replacement != null) param.result = replacement
                        XposedBridge.log("$TAG: blocked heads-up decision for $pkg (" + m.name + ")")
                    }
                })
                n++
            }.onFailure { XposedBridge.log("$TAG: hook ${m.name} failed: $it") }
        }
        return n
    }

    /** Backup point: if the decision somehow still says "show", flip it off. */
    private fun hookPostedEntry(cl: ClassLoader): Int {
        val cls = XposedHelpers.findClassIfExists(CLS_POSTED_ENTRY, cl) ?: return 0
        val m = runCatching {
            cls.declaredMethods.firstOrNull {
                it.name == "getShouldHeadsUpEver" && it.returnType == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: return 0

        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != true) return
                    val entry = runCatching {
                        XposedHelpers.getObjectField(param.thisObject, "entry")
                    }.getOrNull() ?: return
                    val pkg = packageOf(entry) ?: return
                    if (pkg in TARGET_PACKAGES) {
                        param.result = false
                        XposedBridge.log("$TAG: cleared shouldHeadsUpEver for $pkg")
                    }
                }
            })
            1
        }.onFailure { XposedBridge.log("$TAG: hook PostedEntry failed: $it") }.getOrDefault(0)
    }

    // ------------------------------------------------------------ helpers

    /** A "no heads-up" LoggableDecision instance, built once via reflection. */
    private fun suppressedDecision(cl: ClassLoader): Any? {
        cachedSuppressed?.let { return it }
        return runCatching {
            val ld = XposedHelpers.findClass(CLS_LOGGABLE_DECISION, cl)
            val suppressorIface = XposedHelpers.findClass(CLS_SUPPRESSOR, cl)

            val suppressor = Proxy.newProxyInstance(cl, arrayOf(suppressorIface)) { _, method, _ ->
                when (method.name) {
                    "getName", "toString" -> TAG
                    "getSuppressReason", "getReason" -> "$TAG: package muted"
                    "hashCode" -> System.identityHashCode(method)
                    "equals" -> false
                    else -> when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            }

            val companion = runCatching {
                XposedHelpers.getStaticObjectField(ld, "Companion")
            }.getOrNull()
            val receivers = listOfNotNull(companion?.javaClass, ld)
            val m = receivers.asSequence()
                .flatMap { it.methods.asSequence() }
                .firstOrNull {
                    it.name == "suppressed" && it.parameterCount == 2 &&
                        Modifier.isPublic(it.modifiers)
                } ?: return@runCatching null

            val receiver = if (Modifier.isStatic(m.modifiers)) null else companion
            m.invoke(receiver, suppressor, "$TAG: $TAG muted this package")
                .also { cachedSuppressed = it }
        }.onFailure { XposedBridge.log("$TAG: cannot build suppressed decision: $it") }.getOrNull()
    }

    /** Extract the package name from NotificationEntry / NotificationRecord / SBN. */
    private fun packageOf(obj: Any?): String? {
        if (obj == null) return null
        if (obj is android.service.notification.StatusBarNotification) return obj.packageName
        if (obj is Class<*>) return null
        val c = obj.javaClass

        // StatusBarNotification / Notification accessors
        for (name in arrayOf("getSbn", "getNotification", "getStatusBarNotification")) {
            val v = runCatching { c.getMethod(name).invoke(obj) }.getOrNull()
            val p = packageOf(v)
            if (p != null) return p
        }
        // fields, public or not
        for (name in arrayOf("mSbn", "sbn", "notification", "mNotification", "entry")) {
            val p = runCatching {
                val f = c.getField(name)
                packageOf(f.get(obj))
            }.getOrNull() ?: runCatching {
                val f = c.getDeclaredField(name).apply { isAccessible = true }
                packageOf(f.get(obj))
            }.getOrNull()
            if (p != null) return p
        }
        // Kotlin property "packageName" directly on the record
        runCatching { c.getMethod("getPackageName").invoke(obj) as? String }.getOrNull()
            ?.let { return it }
        return null
    }
}
