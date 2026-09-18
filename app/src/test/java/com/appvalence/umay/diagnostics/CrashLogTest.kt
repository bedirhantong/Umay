package com.appvalence.umay.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {

    private fun report(activeNavigation: String? = null): String {
        val cause = IllegalStateException("sensor went away")
        return CrashLog.formatReport(
            timeMs = 0L,
            threadName = "main",
            throwable = RuntimeException("navigation failed", cause),
            appVersion = "1.0",
            device = "Pixel 8",
            androidSdk = 35,
            activeNavigation = activeNavigation,
        )
    }

    @Test
    fun `the report carries the exception, its cause and the stack trace`() {
        val text = report()

        assertTrue(text.contains("java.lang.RuntimeException: navigation failed"))
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: sensor went away"))
        assertTrue(text.contains("\tat "))
    }

    @Test
    fun `the report says what the device and app were`() {
        val text = report()

        assertTrue(text.contains("app: 1.0"))
        assertTrue(text.contains("device: Pixel 8 (Android SDK 35)"))
        assertTrue(text.contains("thread: main"))
    }

    @Test
    fun `the report says whether a walk was in progress`() {
        assertTrue(report().contains("navigation: none"))
        assertTrue(report("lab: A -> D").contains("navigation: lab: A -> D"))
    }
}
