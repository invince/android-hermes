package com.nous.hermes.phone

/**
 * Builds and launches one-off commands inside the Hermes proot.
 *
 * Each command runs in its own `su -c` process (not [RootShell]) so a long or
 * interactive command in the terminal cannot stall the gateway/control layer.
 * The working directory is echoed back through [CWD_MARKER] so [TerminalActivity]
 * can carry `cd` state across commands.
 */
object TerminalRunner {
    const val CWD_MARKER = "__HERMES_TERM_CWD__"

    fun defaultCwd(): String = "/root"

    fun build(userCommand: String, cwd: String): String {
        val inner = buildString {
            append("export HOME=/root\n")
            append("export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n")
            append("export TMPDIR=/tmp\n")
            append("[ -f /root/.hermes/.env ] && { set -a; . /root/.hermes/.env; set +a; }\n")
            append("cd ").append(ShellUtils.shellQuote(cwd)).append(" 2>/dev/null || cd /root\n")
            append(userCommand).append('\n')
            append("__hermes_term_status=$?\n")
            append("printf '\\n").append(CWD_MARKER).append("%s\\n' \"\$PWD\"\n")
            append("exit \$__hermes_term_status\n")
        }
        return buildString {
            append("export LD_LIBRARY_PATH=").append(Constants.LIB_DIR).append("; ")
            append("export PROOT_LOADER=").append(Constants.LOADER).append("; ")
            append("export TMPDIR=").append(Constants.TMP_DIR).append("; ")
            append("export PROOT_TMP_DIR=").append(Constants.TMP_DIR).append("; ")
            append("exec ").append(Constants.PROOT)
            append(" -0 -r ").append(Constants.ROOTFS_DIR)
            append(" -b /dev -b /proc -b /sys")
            append(" -b ").append(Constants.TMP_DIR).append(":/tmp")
            append(" -b ").append(Constants.HERMES_HOME).append(":/root/.hermes")
            append(" -w /root /bin/sh -c ").append(ShellUtils.shellQuote(inner))
        }
    }

    fun start(userCommand: String, cwd: String): Process =
        ProcessBuilder("su", "-c", build(userCommand, cwd))
            .redirectErrorStream(true)
            .start()
}
