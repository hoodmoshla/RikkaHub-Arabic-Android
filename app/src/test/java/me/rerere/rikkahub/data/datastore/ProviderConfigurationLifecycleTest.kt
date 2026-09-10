package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderConfigurationLifecycleTest {

    @Test
    fun `initial state includes official RikkaHub free provider with Auto model and configured state`() {
        val initialSettings = Settings(
            providers = DEFAULT_PROVIDERS
        )

        // RikkaHub provider must be present in DEFAULT_PROVIDERS
        val rikkahub = initialSettings.providers.find { it.id == RIKKAHUB_PROVIDER_ID }
        assertNotNull("RikkaHub provider must be in DEFAULT_PROVIDERS", rikkahub)
        assertEquals("RikkaHub", rikkahub?.name)
        assertTrue("RikkaHub must be builtIn", rikkahub?.builtIn == true)
        assertTrue("RikkaHub must be enabled", rikkahub?.enabled == true)

        // Auto model must be present with DEFAULT_AUTO_MODEL_ID
        val autoModel = rikkahub?.models?.find { it.id == DEFAULT_AUTO_MODEL_ID }
        assertNotNull("Auto model must be in RikkaHub models", autoModel)
        assertEquals("auto", autoModel?.modelId)
        assertEquals("Auto", autoModel?.displayName)

        // On clean install with RikkaHub provider, isNotConfigured must be false because Auto model is available
        assertFalse("Clean install with RikkaHub provider must report isNotConfigured() == false", initialSettings.isNotConfigured())

        // Default chat model resolution must find Auto model
        val resolvedChatModel = initialSettings.getCurrentChatModel()
        assertNotNull("Default chat model must resolve to Auto model", resolvedChatModel)
        assertEquals("auto", resolvedChatModel?.modelId)
        assertEquals(RIKKAHUB_PROVIDER_ID, resolvedChatModel?.findProvider(initialSettings.providers)?.id)
    }

    @Test
    fun `unconfigured state occurs only when all provider models are empty`() {
        val emptySettings = Settings(
            providers = DEFAULT_PROVIDERS.map { it.copyProvider(models = emptyList()) }
        )
        assertTrue("When all models are empty, isNotConfigured must be true", emptySettings.isNotConfigured())
    }

    @Test
    fun `configuring provider with api key and model clears unconfigured warning state`() {
        val testModel = Model(
            modelId = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            type = ModelType.CHAT,
        )

        val configuredGemini = ProviderSetting.Google(
            id = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"),
            name = "Gemini",
            apiKey = "test-google-key-12345",
            enabled = true,
            models = listOf(testModel)
        )

        val updatedSettings = Settings(
            chatModelId = testModel.id,
            providers = DEFAULT_PROVIDERS.map {
                if (it.id == configuredGemini.id) configuredGemini else it
            }
        )

        // Once a model is added, isNotConfigured() must be false!
        assertFalse("Configured provider must report isNotConfigured() == false", updatedSettings.isNotConfigured())

        // Chat model resolution must find the configured model
        val currentModel = updatedSettings.getCurrentChatModel()
        assertNotNull("Must resolve a current chat model", currentModel)
        assertEquals("gemini-2.5-flash", currentModel?.modelId)

        // Provider lookup must resolve the configured provider with the key
        val resolvedProvider = currentModel?.findProvider(updatedSettings.providers)
        assertNotNull("Must resolve parent provider", resolvedProvider)
        assertEquals("Gemini", resolvedProvider?.name)
        assertTrue(resolvedProvider is ProviderSetting.Google)
        assertEquals("test-google-key-12345", (resolvedProvider as ProviderSetting.Google).apiKey)
    }

    @Test
    fun `settings providers serialize and deserialize with full fidelity via JsonInstant`() {
        val customModel = Model(
            modelId = "gpt-4o",
            displayName = "GPT-4o (Omni)",
            type = ModelType.CHAT,
        )
        val customProvider = ProviderSetting.OpenAI(
            name = "Custom OpenAI",
            apiKey = "sk-custom-secret-key",
            baseUrl = "https://custom.openai.proxy/v1",
            models = listOf(customModel)
        )

        val providersList: List<ProviderSetting> = listOf(customProvider)

        // Serialize to JSON (same mechanism as DataStore)
        val encoded = JsonInstant.encodeToString(providersList)
        assertTrue(encoded.contains("sk-custom-secret-key"))
        assertTrue(encoded.contains("gpt-4o"))

        // Deserialize back from JSON
        val decoded = JsonInstant.decodeFromString<List<ProviderSetting>>(encoded)
        assertEquals(1, decoded.size)

        val restoredProvider = decoded.first() as ProviderSetting.OpenAI
        assertEquals("Custom OpenAI", restoredProvider.name)
        assertEquals("sk-custom-secret-key", restoredProvider.apiKey)
        assertEquals("https://custom.openai.proxy/v1", restoredProvider.baseUrl)
        assertEquals(1, restoredProvider.models.size)
        assertEquals("gpt-4o", restoredProvider.models.first().modelId)
    }
}
