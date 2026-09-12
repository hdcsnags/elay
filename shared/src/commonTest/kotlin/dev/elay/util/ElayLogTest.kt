package dev.elay.util

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ElayLogTest {
    // ElayLogConfig is a process-wide singleton flag — restore the safe default so other tests
    // (and other test classes sharing the same test process) never observe it left enabled.
    @AfterTest
    fun resetConfig() {
        ElayLogConfig.enabled = false
    }

    // NOTE (D2): deliberately no companion "enabled=true actually logs" test here — the enabled
    // actuals call real platform logging (android.util.Log / println), which on the Android
    // host-test target hits an unmocked android.jar stub and throws. The disabled path below is
    // both the security-relevant one (release builds) and the one safely testable from
    // commonTest across every target.
    @Test
    fun disabledLoggerNeverInvokesTheMessageLambda() {
        ElayLogConfig.enabled = false
        var invocations = 0
        ElayLog.d("Test") {
            invocations++
            "should never be built"
        }
        ElayLog.w("Test") {
            invocations++
            "should never be built"
        }
        assertEquals(0, invocations, "release/disabled builds must skip string construction entirely")
    }
}
