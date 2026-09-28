package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 验证会话级 Model/Workspace 覆盖能正确落库并读回（应用重启的持久化路径）。
 */
class ConversationPersistenceTest {

    @Test
    fun `conversation scoped overrides survive entity round trip`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val workspaceId = Uuid.random()
        val conversation = Conversation(
            assistantId = assistantId,
            title = "Chat A",
            messageNodes = emptyList(),
            modelId = modelId,
            workspaceId = workspaceId,
            workspaceCwd = "/project/A",
        )

        val entity = ConversationRepository.conversationToConversationEntity(conversation)
        assertEquals(modelId.toString(), entity.modelId)
        assertEquals(workspaceId.toString(), entity.workspaceId)
        assertEquals("/project/A", entity.workspaceCwd)

        val restored = ConversationRepository.conversationEntityToConversation(entity, emptyList())
        assertEquals(modelId, restored.modelId)
        assertEquals(workspaceId, restored.workspaceId)
        assertEquals("/project/A", restored.workspaceCwd)
        assertEquals(assistantId, restored.assistantId)
    }

    @Test
    fun `legacy rows without overrides restore as inheriting conversations`() {
        // 模拟升级前已存在的旧记录：新增列为空字符串
        val entity = ConversationEntity(
            id = Uuid.random().toString(),
            assistantId = Uuid.random().toString(),
            title = "legacy",
            nodes = "[]",
            createAt = 0L,
            updateAt = 0L,
            chatSuggestions = "[]",
            isPinned = false,
        )

        val restored = ConversationRepository.conversationEntityToConversation(entity, emptyList())

        assertNull(restored.modelId)
        assertNull(restored.workspaceId)
        assertNull(restored.workspaceCwd)
    }

    @Test
    fun `two persisted conversations keep distinct overrides`() {
        val assistantId = Uuid.random()
        val modelA = Uuid.random()
        val modelB = Uuid.random()
        val projectA = Uuid.random()
        val projectB = Uuid.random()

        val conversationA = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            modelId = modelA,
            workspaceId = projectA,
        )
        val conversationB = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            modelId = modelB,
            workspaceId = projectB,
        )

        val restoredA = ConversationRepository.conversationEntityToConversation(
            ConversationRepository.conversationToConversationEntity(conversationA),
            emptyList(),
        )
        val restoredB = ConversationRepository.conversationEntityToConversation(
            ConversationRepository.conversationToConversationEntity(conversationB),
            emptyList(),
        )

        assertEquals(modelA, restoredA.modelId)
        assertEquals(projectA, restoredA.workspaceId)
        assertEquals(modelB, restoredB.modelId)
        assertEquals(projectB, restoredB.workspaceId)
        assertEquals(assistantId, restoredA.assistantId)
        assertEquals(assistantId, restoredB.assistantId)
    }
}
