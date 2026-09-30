package me.rerere.rikkahub.data.datastore

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the startup crash that reached
 * `org.koin.core.error.InstanceCreationException: Could not create instance for '[Factory: ChatVM']`.
 *
 * `ChatService.createInitialConversation` runs `settings.getCurrentAssistant()` synchronously while
 * the session (and therefore the chat view model) is being created, and the old implementation ended
 * with `assistants.first()`, which throws [NoSuchElementException] as soon as the stored settings
 * contain no assistant at all.
 */
@OptIn(ExperimentalUuidApi::class)
class AssistantResolutionTest {

    private fun settingsWithoutAssistants() = Settings.dummy().copy(assistants = emptyList())

    @Test
    fun `getCurrentAssistant never throws when no assistant is stored`() {
        val settings = settingsWithoutAssistants()
        val assistant = settings.getCurrentAssistant()
        assertNotNull(assistant)
        assertEquals("", assistant.name)
    }

    @Test
    fun `getCurrentAssistant still resolves the stored selection when it exists`() {
        val selected = Settings.dummy().assistants.first()
        val settings = Settings.dummy().copy(assistants = listOf(selected), assistantId = selected.id)
        assertEquals(selected.id, settings.getCurrentAssistant().id)
    }

    @Test
    fun `a conversation pointing to a missing assistant resolves safely`() {
        val settings = settingsWithoutAssistants()
        val conversation = me.rerere.rikkahub.data.model.Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
        )

        // These are the lookups used by the chat UI/VM; none of them may throw.
        assertNotNull(settings.getConversationAssistant(conversation))
        assertNotNull(settings.resolveAssistant(conversation))
        assertNull(settings.resolveWorkspaceId(conversation))
        settings.resolveChatModel(conversation)
    }
}
