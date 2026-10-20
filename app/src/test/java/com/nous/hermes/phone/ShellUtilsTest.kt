package com.nous.hermes.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellUtilsTest {

    @Test
    fun quotesPlainValue() {
        assertEquals("'hello'", ShellUtils.shellQuote("hello"))
    }

    @Test
    fun escapesSingleQuote() {
        assertEquals("'it'\\''s'", ShellUtils.shellQuote("it's"))
        assertEquals("'a'\\''b'\\''c'", ShellUtils.shellQuote("a'b'c"))
    }

    @Test
    fun neutralisesInjectionAttempt() {
        val evil = "x'; rm -rf / #"
        assertEquals("'x'\\''; rm -rf / #'", ShellUtils.shellQuote(evil))
    }

    @Test
    fun encodesSpacesForInputText() {
        assertEquals("hello%sworld", ShellUtils.inputTextEscape("hello world"))
    }

    @Test
    fun leavesSingleTokenUntouched() {
        assertEquals("pong", ShellUtils.inputTextEscape("pong"))
    }
}
