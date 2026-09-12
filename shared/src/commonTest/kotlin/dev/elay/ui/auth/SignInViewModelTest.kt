package dev.elay.ui.auth

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInViewModelTest {
    private fun alwaysSucceedingAccountCreator() = AccountCreator { _, _ -> Result.success(Unit) }

    @Test
    fun submitSetsLoadingImmediatelyThenClearsItOnSuccess() =
        runTest {
            val auth = FakeAuthGateway()
            val viewModel = SignInViewModel(auth, alwaysSucceedingAccountCreator(), backgroundScope)
            viewModel.onEmailChanged("a@b.com")
            viewModel.onPasswordChanged("secret123")

            viewModel.submit()
            assertTrue(viewModel.state.value.isLoading, "loading flips synchronously, before the coroutine resumes")

            runCurrent()

            assertFalse(viewModel.state.value.isLoading)
            assertNull(viewModel.state.value.message)
            assertEquals(listOf("a@b.com" to "secret123"), auth.signInCalls)
        }

    @Test
    fun failedSignInShowsCalmMessageNeverTheRawProviderError() =
        runTest {
            val auth = FakeAuthGateway()
            auth.signInResult = Result.failure(RuntimeException("Invalid login credentials"))
            val viewModel = SignInViewModel(auth, alwaysSucceedingAccountCreator(), backgroundScope)
            viewModel.onEmailChanged("a@b.com")
            viewModel.onPasswordChanged("wrong-password")

            viewModel.submit()
            runCurrent()

            assertFalse(viewModel.state.value.isLoading)
            val message = viewModel.state.value.message
            assertTrue(message != null, "a failure must surface some message")
            assertFalse(
                message!!.contains("Invalid login credentials"),
                "must never surface the raw provider error string",
            )
        }

    @Test
    fun blankFieldsAreRejectedLocallyWithoutCallingTheGateway() =
        runTest {
            val auth = FakeAuthGateway()
            val viewModel = SignInViewModel(auth, alwaysSucceedingAccountCreator(), backgroundScope)

            viewModel.submit()
            runCurrent()

            assertTrue(auth.signInCalls.isEmpty())
            assertFalse(viewModel.state.value.isLoading)
            assertEquals("Add your email and password to continue.", viewModel.state.value.message)
        }

    @Test
    fun createAccountModeSignsInAfterASuccessfulSignUp() =
        runTest {
            val auth = FakeAuthGateway()
            val signUpCalls = mutableListOf<Pair<String, String>>()
            val viewModel =
                SignInViewModel(
                    auth,
                    AccountCreator { email, password ->
                        signUpCalls += email to password
                        Result.success(Unit)
                    },
                    backgroundScope,
                )
            viewModel.onEmailChanged("new@b.com")
            viewModel.onPasswordChanged("secret123")
            viewModel.toggleMode()
            assertEquals(SignInMode.CreateAccount, viewModel.state.value.mode)

            viewModel.submit()
            runCurrent()

            assertEquals(listOf("new@b.com" to "secret123"), signUpCalls)
            assertEquals(listOf("new@b.com" to "secret123"), auth.signInCalls)
            assertFalse(viewModel.state.value.isLoading)
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun failedCreateAccountNeverAttemptsASignIn() =
        runTest {
            val auth = FakeAuthGateway()
            val viewModel =
                SignInViewModel(
                    auth,
                    AccountCreator { _, _ -> Result.failure(RuntimeException("already registered")) },
                    backgroundScope,
                )
            viewModel.onEmailChanged("dup@b.com")
            viewModel.onPasswordChanged("secret123")
            viewModel.toggleMode()

            viewModel.submit()
            runCurrent()

            assertTrue(auth.signInCalls.isEmpty(), "a failed sign-up must not be followed by a sign-in attempt")
            assertFalse(viewModel.state.value.isLoading)
            assertTrue(viewModel.state.value.message != null)
        }

    @Test
    fun editingAFieldClearsAStaleMessage() =
        runTest {
            val auth = FakeAuthGateway()
            val viewModel = SignInViewModel(auth, alwaysSucceedingAccountCreator(), backgroundScope)
            viewModel.submit()
            runCurrent()
            assertTrue(viewModel.state.value.message != null)

            viewModel.onEmailChanged("a@b.com")

            assertNull(viewModel.state.value.message)
        }
}
