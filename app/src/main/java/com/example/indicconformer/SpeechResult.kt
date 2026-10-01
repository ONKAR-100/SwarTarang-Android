package com.example.indicconformer

import org.json.JSONObject
import java.util.UUID

data class SpeechResult(
    val messageId: String,
    val language: String,
    val text: String,
    val timestamp: Long,
    val isFinal: Boolean,
    val confidence: Float? = null,
    val sourceLanguage: String = language,
    val priority: MessagePriority = MessagePriority.NORMAL,
    val messageType: MessageType = MessageType.SPEECH
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("messageId", messageId)
        json.put("language", language)
        json.put("sourceLanguage", sourceLanguage)
        json.put("text", text)
        json.put("timestamp", timestamp)
        json.put("isFinal", isFinal)
        if (confidence != null) {
            json.put("confidence", confidence.toDouble())
        }
        json.put("priority", priority.name)
        json.put("messageType", messageType.name)
        return json.toString()
    }

    companion object {
        fun create(
            text: String,
            isFinal: Boolean = true,
            language: String = "mr",
            confidence: Float? = null,
            priority: MessagePriority = MessagePriority.NORMAL,
            messageType: MessageType = MessageType.SPEECH
        ): SpeechResult = SpeechResult(
            messageId = UUID.randomUUID().toString(),
            language = language,
            sourceLanguage = language,
            text = text,
            timestamp = System.currentTimeMillis(),
            isFinal = isFinal,
            confidence = confidence,
            priority = priority,
            messageType = messageType
        )

        fun fromJson(jsonStr: String): SpeechResult? = try {
            val json = JSONObject(jsonStr)
            val messageId = json.optString("messageId", UUID.randomUUID().toString())
            val sourceLang = json.optString("sourceLanguage", json.optString("language", "mr"))
            val text = json.getString("text")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val isFinal = json.optBoolean("isFinal", true)
            val confidence = if (json.has("confidence")) json.getDouble("confidence").toFloat() else null

            val priorityStr = json.optString("priority", "NORMAL")
            val priority = try {
                MessagePriority.valueOf(priorityStr.uppercase())
            } catch (_: Exception) {
                MessagePriority.NORMAL
            }

            val typeStr = json.optString("messageType", "SPEECH")
            val messageType = try {
                MessageType.valueOf(typeStr.uppercase())
            } catch (_: Exception) {
                MessageType.SPEECH
            }

            SpeechResult(
                messageId = messageId,
                language = sourceLang,
                sourceLanguage = sourceLang,
                text = text,
                timestamp = timestamp,
                isFinal = isFinal,
                confidence = confidence,
                priority = priority,
                messageType = messageType
            )
        } catch (_: Exception) {
            null
        }
    }
}
