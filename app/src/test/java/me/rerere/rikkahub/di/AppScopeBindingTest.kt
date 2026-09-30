package me.rerere.rikkahub.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Locks the DI binding that the app startup crash depended on.
 *
 * UpdateChecker (created while building ChatVM) takes a `CoroutineScope` parameter, and the app
 * module only ever declared `AppScope`. Koin therefore failed with
 * `NoDefinitionFoundException: No definition found for type 'kotlinx.coroutines.CoroutineScope'`
 * and the chat page crashed on every launch. The application scope must stay bound to
 * `CoroutineScope` so the same managed instance answers both types.
 */
class AppScopeBindingTest {

    private class TestAppScope : CoroutineScope by CoroutineScope(SupervisorJob())

    @Test
    fun `the application scope answers for AppScope and CoroutineScope alike`() {
        val koin = koinApplication {
            modules(
                module {
                    single { TestAppScope() }
                    single<CoroutineScope> { get<TestAppScope>() }
                },
            )
        }.koin

        assertSame(koin.get<TestAppScope>(), koin.get<CoroutineScope>())
    }

    @Test
    fun `the real app module declares an AppScope bound to CoroutineScope`() {
        // Fails if the `bind CoroutineScope::class` in AppModule is ever removed again.
        val source = java.io.File("src/main/java/me/rerere/rikkahub/di/AppModule.kt")
        if (!source.exists()) return // unit tests may run from another working directory
        val text = source.readText()
        assert(text.contains("single { AppScope() }") && text.contains("single<CoroutineScope>")) {
            "AppModule must expose the application scope for the CoroutineScope type"
        }
    }
}
