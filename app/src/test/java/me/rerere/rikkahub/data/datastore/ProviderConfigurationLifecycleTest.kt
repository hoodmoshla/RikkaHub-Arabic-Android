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
    fun `initial state shows not configured because default providers have empty models`() {
        val initialSettings = Settings(
            providers = DEFAULT_PROVIDERS
        )

        // When fresh install, no models exist, so isNotConfigured must be true
        assertTrue("Fresh install must report isNotConfigured() == true", initialSettings.isNotConfigured())
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

        val providersList = listOf(customProvider)

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
