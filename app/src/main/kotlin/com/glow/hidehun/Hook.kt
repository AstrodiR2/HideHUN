package com.glow.hidehun

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class Hook : IXposedHookLoadPackage {

    private val decisionClasses = listOf(
        "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl",
        "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl"
    )

    private val headsUpClasses = listOf(
        "com.android.systemui.statusbar.policy.BaseHeadsUpManager",
        "com.android.systemui.statusbar.policy.HeadsUpManager",
        "com.android.systemui.statusbar.policy.HeadsUpManagerPhone",
        "com.android.systemui.statusbar.phone.HeadsUpManagerPhone"
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        var hooked = 0

        for (name in decisionClasses) {
            val cls = XposedHelpers.findClassIfExists(name, lpparam.classLoader) ?: continue
            for (m in cls.declaredMethods) {
                if (m.name == "shouldHeadsUp" && m.returnType == java.lang.Boolean.TYPE) {
                    runCatching {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false))
                        hooked++
                    }
                }
            }
        }

        for (name in headsUpClasses) {
            val cls = XposedHelpers.findClassIfExists(name, lpparam.classLoader) ?: continue
            for (m in cls.declaredMethods) {
                if (m.name != "showNotification") continue
                val repl = when (m.returnType) {
                    Void.TYPE -> XC_MethodReplacement.DO_NOTHING
                    java.lang.Boolean.TYPE -> XC_MethodReplacement.returnConstant(false)
                    else -> null
                } ?: continue
                runCatching {
                    XposedBridge.hookMethod(m, repl)
                    hooked++
                }
            }
        }

        XposedBridge.log("HideHUN: hooked $hooked methods")
    }
}
