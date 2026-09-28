package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.createForkConversation
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 验证 Model 与 Project(Workspace) 是会话级设置:
 * 两个会话可以各自选择不同的模型和项目, 且修改其中一个不会影响另一个。
 */
class ConversationScopedSettingsTest {

    private val modelA = Model(modelId = "deepseek-chat", displayName = "DeepSeek", type = ModelType.CHAT)
    private val modelB = Model(modelId = "claude-sonnet", displayName = "Claude", type = ModelType.CHAT)
    private val modelC = Model(modelId = "gpt-4o", displayName = "GPT-4o", type = ModelType.CHAT)

    private val provider = ProviderSetting.OpenAI(
        name = "Test Provider",
        models = listOf(modelA, modelB, modelC),
    )

    private val projectA = Uuid.random()
    private val projectB = Uuid.random()
    private val assistantId = Uuid.random()

    private val assistant = Assistant(
        id = assistantId,
        chatModelId = null,
        workspaceId = null,
    )

    private fun settings(assistant: Assistant = this.assistant) = Settings(
        assistantId = assistant.id,
        providers = listOf(provider),
        assistants = listOf(assistant),
    )

    @Test
    fun `two conversations keep independent model and workspace`() {
        val settings = settings()
        val conversationA = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            modelId = modelA.id,
            workspaceId = projectA,
        )
        val conversationB = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            modelId = modelB.id,
            workspaceId = projectB,
        )

        // Chat A -> Model A + Project A
        assertEquals(modelA, settings.resolveChatModel(conversationA))
        assertEquals(projectA, settings.resolveAssistant(conversationA).workspaceId)

        // Chat B -> Model B + Project B
        assertEquals(modelB, settings.resolveChatModel(conversationB))
        assertEquals(projectB, settings.resolveAssistant(conversationB).workspaceId)

        // 回到 Chat A 仍然是 Model A + Project A
        assertEquals(modelA, settings.resolveChatModel(conversationA))
        assertEquals(projectA, settings.resolveAssistant(conversationA).workspaceId)
    }

    @Test
    fun `changing one conversation does not affect the other`() {
        val settings = settings()
        val conversationA = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            modelId = modelA.id,
            workspaceId = projectA,
        )
        val conversationB = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            modelId = modelB.id,
            workspaceId = projectB,
        )

        // 修改 Chat B
        val updatedB = conversationB.copy(modelId = modelC.id, workspaceId = projectA)

        // Chat B 反映了新选择
        assertEquals(modelC, settings.resolveChatModel(updatedB))
        assertEquals(projectA, settings.resolveAssistant(updatedB).workspaceId)

        // Chat A 完全不受影响
        assertEquals(modelA, settings.resolveChatModel(conversationA))
        assertEquals(projectA, settings.resolveAssistant(conversationA).workspaceId)

        // 原始 B 对象也没有被原地修改
        assertEquals(modelB, settings.resolveChatModel(conversationB))
        assertEquals(projectB, settings.resolveAssistant(conversationB).workspaceId)
    }

    @Test
    fun `conversation without override inherits assistant defaults`() {
        val assistantWithDefaults = Assistant(
            id = assistantId,
            chatModelId = modelB.id,
            workspaceId = projectA,
        )
        val settings = settings(assistantWithDefaults)
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            modelId = null,
            workspaceId = null,
        )

        assertEquals(modelB, settings.resolveChatModel(conversation))
        assertEquals(projectA, settings.resolveAssistant(conversation).workspaceId)
    }

    @Test
    fun `conversation override takes precedence over assistant defaults`() {
        val assistantWithDefaults = Assistant(
            id = assistantId,
            chatModelId = modelA.id,
            workspaceId = projectA,
        )
        val settings = settings(assistantWithDefaults)
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            modelId = modelB.id,
            workspaceId = projectB,
        )

        assertEquals(modelB, settings.resolveChatModel(conversation))
        assertEquals(projectB, settings.resolveAssistant(conversation).workspaceId)
        // 助手默认值本身未被修改
        assertEquals(modelA.id, assistantWithDefaults.chatModelId)
        assertEquals(projectA, assistantWithDefaults.workspaceId)
    }

    @Test
    fun `resolveAssistant keeps assistant identity while overriding workspace`() {
        val settings = settings()
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            workspaceId = projectB,
        )

        val resolved = settings.resolveAssistant(conversation)

        assertEquals(assistantId, resolved.id)
        assertEquals(projectB, resolved.workspaceId)
        // 返回的是副本, 不应污染设置中的原助手
        assertNull(settings.assistants.first().workspaceId)
    }

    @Test
    fun `fork inherits conversation scoped model and workspace`() {
        val source = Conversation(
            assistantId = assistantId,
            title = "Source",
            messageNodes = emptyList(),
            modelId = modelA.id,
            workspaceId = projectA,
            workspaceCwd = "/project/A",
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(modelA.id, fork.modelId)
        assertEquals(projectA, fork.workspaceId)
        assertEquals("/project/A", fork.workspaceCwd)
    }

    @Test
    fun `conversation scoped settings survive json round trip`() {
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            modelId = modelB.id,
            workspaceId = projectB,
            workspaceCwd = "/project/B",
        )

        val restored = JsonInstant.decodeFromString<Conversation>(
            JsonInstant.encodeToString(conversation)
        )

        assertEquals(modelB.id, restored.modelId)
        assertEquals(projectB, restored.workspaceId)
        assertEquals("/project/B", restored.workspaceCwd)
    }
}
