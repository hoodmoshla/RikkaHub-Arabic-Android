package me.rerere.rikkahub.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.utils.UpdateChecker
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Real Koin-graph test for the crash that happened the moment ChatPage was opened:
 *
 *   ChatPage -> ChatVM -> UpdateChecker -> CoroutineScope -> Koin
 *   NoDefinitionFoundException: No definition found for type 'kotlinx.coroutines.CoroutineScope'
 *
 * It builds a Koin container from the *actual* production modules (appModule + viewModelModule),
 * not from a copy, so it fails exactly like the app would if UpdateChecker's scope dependency can
 * no longer be satisfied. That is the regression this test locks.
 *
 * `Dispatchers.setMain` is only needed because a JVM test has no Android main looper; the graph
 * itself is identical to production.
 */
class ChatGraphDiTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newKoin() = koinApplication(createEagerInstances = false) {
        modules(
            appModule,
            viewModelModule,
            // The production OkHttpClient single builds a SettingsStore/DataStore/Context graph that
            // only exists on Android. Replace just that leaf so the DI graph is otherwise byte-for-
            // byte the production one.
            module { single<OkHttpClient> { OkHttpClient() } },
        )
    }.koin

    @Test
    fun `real app module answers CoroutineScope with the managed AppScope`() {
        val koin = newKoin()
        val appScope = koin.get<AppScope>()
        assertSame(appScope, koin.get<CoroutineScope>())
    }

    /**
     * This is the exact edge the crash came from: building UpdateChecker (and therefore ChatVM).
     * It used to fail with `NoDefinitionFoundException: ... 'kotlinx.coroutines.CoroutineScope'`.
     */
    @Test
    fun `real app module can build the UpdateChecker that ChatVM needs`() {
        val koin = newKoin()
        val checker = try {
            koin.get<UpdateChecker>()
        } catch (t: Throwable) {
            val deepest = generateSequence(t) { it.cause }.last()
            throw AssertionError(
                "UpdateChecker could not be built: ${deepest::class.qualifiedName}: ${deepest.message}",
                t,
            )
        }
        assertNotNull(checker)
    }
}
