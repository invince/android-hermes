package com.nous.hermes.phone

/**
 * Safety helpers for handing strings to a shell. Never build a command with a
 * raw user string: every interpolated value must go through [shellQuote].
 */
object ShellUtils {

    /** POSIX single-quote escaping: `it's` -> `'it'\''s'`. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * `input text` uses `%s` as the whitespace escape on Android. There is no
     * escape for a literal `%`, so only spaces are rewritten.
     */
    fun inputTextEscape(value: String): String = value.replace(" ", "%s")
}
