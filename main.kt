import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val API_KEY = "<your_api_key>"
const val BASE_URL = "https://api.on-demand.io/chat/v1"

var EXTERNAL_USER_ID = "<your_external_user_id>"
const val QUERY = "<your_query>"
const val RESPONSE_MODE = "" // Now dynamic
val AGENT_IDS = arrayOf() // Dynamic array from PluginIds
const val ENDPOINT_ID = "predefined-openai-gpt4.1"
const val REASONING_MODE = "grok-4-fast"
const val FULFILLMENT_PROMPT = ""
val STOP_SEQUENCES = arrayOf() // Dynamic array
const val TEMPERATURE = 0.7
const val TOP_P = 1
const val MAX_TOKENS = 0
const val PRESENCE_PENALTY = 0
const val FREQUENCY_PENALTY = 0

@Serializable
data class ContextField(val key: String, val value: String)

@Serializable
data class SessionData(val id: String, val contextMetadata: List<ContextField>)

@Serializable
data class CreateSessionResponse(val data: SessionData)

fun main() {
    if (API_KEY == "<your_api_key>" || API_KEY.isEmpty()) {
        println("❌ Please set API_KEY.")
        System.exit(1)
    }
    if (EXTERNAL_USER_ID == "<your_external_user_id>" || EXTERNAL_USER_ID.isEmpty()) {
        EXTERNAL_USER_ID = UUID.randomUUID().toString()
        println("⚠️  Generated EXTERNAL_USER_ID: $EXTERNAL_USER_ID")
    }

    val contextMetadata = listOf(
        mapOf("key" to "userId", "value" to "1"),
        mapOf("key" to "name", "value" to "John")
    )

    val sessionId = createChatSession()
    if (sessionId.isNotEmpty()) {
        println("\n--- Submitting Query ---")
        println("Using query: '$QUERY'")
        println("Using responseMode: '$RESPONSE_MODE'")
        submitQuery(sessionId, contextMetadata) // 👈 updated
    }
}

fun createChatSession(): String {
    val url = "$BASE_URL/sessions"

    val contextMetadata = listOf(
        mapOf("key" to "userId", "value" to "1"),
        mapOf("key" to "name", "value" to "John")
    )

    val body = buildJsonObject {
        put("agentIds", Json.encodeToString(AGENT_IDS))
        put("externalUserId", EXTERNAL_USER_ID)
        put("contextMetadata", Json.encodeToString(contextMetadata))
    }.toString()

    println("📡 Creating session with URL: $url")
    println("📝 Request body: $body")

    val client = OkHttpClient()
    val request = Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/json".toMediaType()))
        .addHeader("apikey", API_KEY)
        .addHeader("Content-Type", "application/json")
        .build()

    client.newCall(request).execute().use { response ->
        if (response.code == 201) {
            val sessionResp = Json.decodeFromString<CreateSessionResponse>(response.body?.string() ?: "")

            println("✅ Chat session created. Session ID: ${sessionResp.data.id}")

            if (sessionResp.data.contextMetadata.isNotEmpty()) {
                println("📋 Context Metadata:")
                sessionResp.data.contextMetadata.forEach { field ->
                    println(" - ${field.key}: ${field.value}")
                }
            }

            return sessionResp.data.id
        } else {
            println("❌ Error creating chat session: ${response.code} - ${response.body?.string()}")
            return ""
        }
    }
}

fun submitQuery(sessionId: String, contextMetadata: List<Map<String, String>>) {
    val url = "$BASE_URL/sessions/$sessionId/query"

    val body = buildJsonObject {
        put("endpointId", ENDPOINT_ID)
        put("query", QUERY)
        put("agentIds", Json.encodeToString(AGENT_IDS))
        put("responseMode", RESPONSE_MODE)
        put("reasoningMode", REASONING_MODE)
        put("modelConfigs", buildJsonObject {
            put("fulfillmentPrompt", FULFILLMENT_PROMPT)
            put("stopSequences", Json.encodeToString(STOP_SEQUENCES))
            put("temperature", TEMPERATURE)
            put("topP", TOP_P)
            put("maxTokens", MAX_TOKENS)
            put("presencePenalty", PRESENCE_PENALTY)
            put("frequencyPenalty", FREQUENCY_PENALTY)
        })
    }.toString()

    println("🚀 Submitting query to URL: $url")
    println("📝 Request body: $body")

    println()

    val client = OkHttpClient()
    val request = Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/json".toMediaType()))
        .addHeader("apikey", API_KEY)
        .addHeader("Content-Type", "application/json")
        .build()

    if (RESPONSE_MODE == "sync") {
        client.newCall(request).execute().use { response ->
            if (response.code == 200) {
                val original = Json.parseToJsonElement(response.body?.string() ?: "").jsonObject.toMutableMap()

                // Append context metadata at the end
                original["data"] = (original["data"] as? JsonObject)?.toMutableMap()?.apply {
                    this["contextMetadata"] = Json.encodeToJsonElement(contextMetadata)
                } ?: JsonObject(emptyMap())

                val final = Json.encodeToString(original)
                println("✅ Final Response (with contextMetadata appended):")
                println(final)
            } else {
                println("❌ Error submitting sync query: ${response.code} - ${response.body?.string()}")
            }
        }
    } else if (RESPONSE_MODE == "stream") {
        println("✅ Streaming Response...")

        client.newCall(request).execute().use { response ->
            if (response.code != 200) {
                println("❌ Error submitting stream query: ${response.code} - ${response.body?.string()}")
                return
            }

            var fullAnswer = ""
            var finalSessionId = ""
            var finalMessageId = ""
            var metrics: Map<String, Any?> = emptyMap()

            val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
            reader.forEachLine { line ->
                if (line.startsWith("data:")) {
                    val dataStr = line.substringAfter("data:").trim()

                    if (dataStr == "[DONE]") {
                        return@forEachLine
                    }

                    try {
                        val event = Json.parseToJsonElement(dataStr).jsonObject
                        if (event["eventType"]?.toString()?.trim('"') == "fulfillment") {
                            event["answer"]?.toString()?.trim('"')?.let { fullAnswer += it }
                            event["sessionId"]?.toString()?.trim('"')?.let { finalSessionId = it }
                            event["messageId"]?.toString()?.trim('"')?.let { finalMessageId = it }
                        } else if (event["eventType"]?.toString()?.trim('"') == "metricsLog") {
                            event["publicMetrics"]?.jsonObject?.let { metrics = it.toMap() }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }

            val finalResponse = buildJsonObject {
                put("message", "Chat query submitted successfully")
                put("data", buildJsonObject {
                    put("sessionId", finalSessionId)
                    put("messageId", finalMessageId)
                    put("answer", fullAnswer)
                    put("metrics", Json.encodeToJsonElement(metrics))
                    put("status", "completed")
                    put("contextMetadata", Json.encodeToJsonElement(contextMetadata))
                })
            }

            val formatted = Json.encodeToString(finalResponse)
            println("\n✅ Final Response (with contextMetadata appended):")
            println(formatted)
        }
    }
}
