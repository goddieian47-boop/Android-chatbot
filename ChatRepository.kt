package com.example.localchat.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val dao: ChatDao) {
    fun conversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    fun messages(conversationId: String): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    suspend fun createConversation(): String {
        val id = UUID.randomUUID().toString()
        dao.insertConversation(ConversationEntity(id, "New chat", System.currentTimeMillis()))
        return id
    }

    suspend fun addMessage(
        conversationId: String,
        role: String,
        content: String,
        imagePath: String? = null,
    ) {
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                createdAt = System.currentTimeMillis(),
                imagePath = imagePath,
            ),
        )
        if (role == "user") {
            dao.setFirstMessageTitle(conversationId, content.take(48))
        }
    }

    suspend fun getMessages(conversationId: String): List<MessageEntity> =
        dao.getMessages(conversationId)

    suspend fun getConversation(id: String): ConversationEntity? = dao.findConversation(id)

    suspend fun getNewestConversation(): ConversationEntity? = dao.findNewestConversation()
}
