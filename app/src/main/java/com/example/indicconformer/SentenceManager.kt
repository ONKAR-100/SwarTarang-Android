package com.example.indicconformer

import java.util.UUID

class SentenceManager {
    fun createResult(
        text: String,
        isFinal: Boolean,
        language: String = "mr",
        priority: MessagePriority = MessagePriority.NORMAL,
        messageType: MessageType = MessageType.SPEECH
    ): SpeechResult {
        return SpeechResult(
            messageId = UUID.randomUUID().toString(),
            language = language,
            sourceLanguage = language,
            text = text,
            timestamp = System.currentTimeMillis(),
            isFinal = isFinal,
            priority = priority,
            messageType = messageType
        )
    }
}
