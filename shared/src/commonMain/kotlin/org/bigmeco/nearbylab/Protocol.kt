package org.bigmeco.nearbylab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class WireMessage(
    val v: Int = 1,
    val id: String,
    val type: String,
    val body: JsonElement = JsonNull,
)

object WireCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    const val MAX_BYTES = 16_384

    fun encode(message: WireMessage): String =
        json.encodeToString(message).also { validate(it, message) }

    fun decode(raw: String): WireMessage {
        require(raw.encodeToByteArray().size <= MAX_BYTES) { "Payload too large" }
        return json.decodeFromString<WireMessage>(raw).also { validate(raw, it) }
    }

    private fun validate(raw: String, message: WireMessage) {
        require(raw.encodeToByteArray().size <= MAX_BYTES) { "Payload too large" }
        require(message.v == 1) { "Unsupported protocol version" }
        require(message.id.isNotBlank() && message.id.length <= 100) { "Invalid message ID" }
        require(message.type in setOf("text", "json", "ping", "pong", "ack", "lab")) {
            "Unknown message type"
        }
        if (message.type == "text")
            require(message.body is JsonPrimitive && message.body.isString) { "Invalid text body" }
    }

    fun jsonBody(raw: String): JsonElement = json.parseToJsonElement(raw)
}
