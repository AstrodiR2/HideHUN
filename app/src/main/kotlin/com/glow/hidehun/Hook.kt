package com.glow.hidehun

import dalvik.system.DexFile
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

class Hook : IXposedHookLoadPackage {

    private fun classNames(cl: ClassLoader, apk: String?): List<String> {
        val out = ArrayList<String>()
        runCatching {
            val pathList = XposedHelpers.getObjectField(cl, "pathList")
            val elements = XposedHelpers.getObjectField(pathList, "dexElements") as Array<*>
            for (e in elements) {
                val df = XposedHelpers.getObjectField(e, "dexFile") as? DexFile ?: continue
                val en = df.entries()
                while (en.hasMoreElements()) out.add(en.nextElement())
            }
        }.onFailure { XposedBridge.log("HideHUN: pathList scan failed: $it") }
        if (out.isEmpty() && apk != null) {
            runCatching {
                val en = DexFile(apk).entries()
                while (en.hasMoreElements()) out.add(en.nextElement())
            }.onFailure { XposedBridge.log("HideHUN: DexFile scan failed: $it") }
        }
        return out
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        if (lpparam.processName != "com.android.systemui") return
        val cl = lpparam.classLoader
        var hooked = 0

        val names = classNames(cl, lpparam.appInfo?.sourceDir)
        XposedBridge.log("HideHUN: scanned ${names.size} classes")

        val targets = names.filter { n ->
            !n.contains('$') && !n.contains("Test") &&
                (n.contains("HeadsUpManager") ||
                    n.contains("InterruptStateProvider") ||
                    n.contains("InterruptionDecisionProvider"))
        }

        for (n in targets) {
            val cls = runCatching { Class.forName(n, false, cl) }.getOrNull() ?: continue
            if (cls.isInterface) continue
            val methods = runCatching { cls.declaredMethods }.getOrNull() ?: continue
            XposedBridge.log("HideHUN: class $n methods=" +
                methods.map { it.name }.distinct().take(70).joinToString(","))

            for (m in methods) {
                if (Modifier.isAbstract(m.modifiers)) continue
                runCatching {
                    if (m.name == "shouldHeadsUp" && m.returnType == java.lang.Boolean.TYPE) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false))
                        hooked++
                    } else if (n.contains("HeadsUpManager") && m.name == "showNotification") {
                        val repl = when (m.returnType) {
                            Void.TYPE -> XC_MethodReplacement.DO_NOTHING
                            java.lang.Boolean.TYPE -> XC_MethodReplacement.returnConstant(false)
                            else -> null
                        }
                        if (repl != null) {
                            XposedBridge.hookMethod(m, repl)
                            hooked++
                        }
                    } else if (m.name == "makeAndLogHeadsUpDecision" || m.name == "makeUnloggedHeadsUpDecision") {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val r = param.result ?: return
                                val c = r.javaClass.declaredConstructors.firstOrNull {
                                    it.parameterTypes.size == 2 &&
                                        it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                                        it.parameterTypes[1] == String::class.java
                                } ?: return
                                c.isAccessible = true
                                param.result = c.newInstance(false, "HideHUN")
                            }
                        })
                        hooked++
                    }
Unit
                }.onFailure { XposedBridge.log("HideHUN: hook ${m.name} failed: $it") }
            }
        }
        XposedBridge.log("HideHUN: hooked $hooked methods")
    }
}
