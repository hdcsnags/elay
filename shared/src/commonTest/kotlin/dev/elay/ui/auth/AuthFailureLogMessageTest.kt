package dev.elay.ui.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthFailureLogMessageTest {
    @Test
    fun dropsTheExceptionMessageKeepingOnlyTheClassName() {
        val emailInMessage = "user+secret@example.com is not a valid login for Invalid login credentials"
        val error = RuntimeException(emailInMessage)

        val logged = authFailureLogMessage(error)

        assertFalse(
            logged.contains(emailInMessage),
            "the exception message can carry the user's email — it must never reach the log",
        )
        assertTrue(logged.contains("RuntimeException"), "the class name is the whole diagnostic value kept")
        assertEquals("ELAY auth failure: RuntimeException", logged)
    }

    @Test
    fun stillFormatsSensiblyWhenTheMessageIsNull() {
        val error = RuntimeException(null as String?)

        val logged = authFailureLogMessage(error)

        assertEquals("ELAY auth failure: RuntimeException", logged)
    }
}
