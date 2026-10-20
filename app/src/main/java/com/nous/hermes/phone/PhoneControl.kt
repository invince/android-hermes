package com.nous.hermes.phone

/**
 * The "hands": drives the phone through `su -c ...` using stock Android
 * tooling (input / am / pm / screencap / settings). No ADB, no Termux:API.
 *
 * All untrusted values are [ShellUtils.shellQuote]d to prevent command
 * injection (v1 bug: `input text '$s'` broke / injected on a quote).
 */
object PhoneControl {

    data class ScreenSize(val width: Int, val height: Int)
    data class Shot(val hostPath: String, val guestPath: String)

    fun screenSize(): ScreenSize {
        val out = RootShell.exec("wm size").output
        val match = Regex("(\\d+)x(\\d+)").find(out)
            ?: throw IllegalStateException("cannot parse wm size: $out")
        return ScreenSize(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    fun tap(x: Int, y: Int): RootShell.Result = RootShell.exec("input tap $x $y")

    fun longPress(x: Int, y: Int, ms: Int = 800): RootShell.Result =
        RootShell.exec("input swipe $x $y $x $y $ms")

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 300): RootShell.Result =
        RootShell.exec("input swipe $x1 $y1 $x2 $y2 $ms")

    fun text(value: String): RootShell.Result =
        RootShell.exec("input text ${ShellUtils.shellQuote(ShellUtils.inputTextEscape(value))}")

    fun keyEvent(code: String): RootShell.Result =
        RootShell.exec("input keyevent ${ShellUtils.shellQuote(code)}")

    /** Screenshot into hermes-home so it is visible inside the rootfs too. */
    fun screenshot(): Shot {
        val name = "shot-${System.currentTimeMillis()}.png"
        val path = "${Constants.HERMES_HOME}/shots/$name"
        val guestPath = "/root/.hermes/shots/$name"
        val r = RootShell.exec(
            "screencap -p ${ShellUtils.shellQuote(path)} && echo SAMPLED"
        )
        if (!r.output.contains("SAMPLED")) throw IllegalStateException("screencap failed: ${r.output}")
        return Shot(path, guestPath)
    }

    fun install(apkPath: String): RootShell.Result =
        RootShell.exec("pm install -r ${ShellUtils.shellQuote(apkPath)}", 120_000)

    fun launch(component: String): RootShell.Result =
        RootShell.exec("am start -n ${ShellUtils.shellQuote(component)}")

    fun openUrl(url: String): RootShell.Result =
        RootShell.exec("am start -a android.intent.action.VIEW -d ${ShellUtils.shellQuote(url)}")

    fun setting(namespace: String, key: String, value: String): RootShell.Result {
        val ns = when (namespace) {
            "global", "system", "secure" -> namespace
            else -> "global"
        }
        return RootShell.exec("settings put $ns ${ShellUtils.shellQuote(key)} ${ShellUtils.shellQuote(value)}")
    }

    fun logcat(lines: Int = 50): RootShell.Result = RootShell.exec("logcat -d -t $lines", 30_000)

    fun battery(): RootShell.Result = RootShell.exec("dumpsys battery", 30_000)
}
