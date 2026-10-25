package analyzer

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse

/** Replays scripted answers in order and records every request it receives. */
class FakeChatModel(vararg answers: String) : ChatModel {
    private val script = ArrayDeque(answers.toList())
    val requests = mutableListOf<List<ChatMessage>>()

    override fun doChat(request: ChatRequest): ChatResponse {
        requests += request.messages().toList()
        val answer = script.removeFirstOrNull() ?: error("FakeChatModel ran out of scripted answers")
        return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build()
    }
}
