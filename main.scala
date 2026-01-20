import sttp.client3._
import sttp.client3.httpclient.HttpClientSyncBackend
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._

import java.util.UUID
import scala.io.Source
import scala.util.Try

object Main {
  val API_KEY = "<your_api_key>"
  val BASE_URL = "https://api.on-demand.io/chat/v1"

  var EXTERNAL_USER_ID = "<your_external_user_id>"
  val QUERY = "<your_query>"
  val RESPONSE_MODE = "" // Now dynamic
  val AGENT_IDS: Array[String] = Array() // Dynamic array from PluginIds
  val ENDPOINT_ID = "predefined-openai-gpt4.1"
  val REASONING_MODE = "grok-4-fast"
  val FULFILLMENT_PROMPT = ""
  val STOP_SEQUENCES: Array[String] = Array() // Dynamic array
  val TEMPERATURE = 0.7
  val TOP_P = 1
  val MAX_TOKENS = 0
  val PRESENCE_PENALTY = 0
  val FREQUENCY_PENALTY = 0

  case class ContextField(key: String, value: String)
  case class SessionData(id: String, contextMetadata: Array[ContextField])
  case class CreateSessionResponse(data: SessionData)

  implicit val contextFieldDecoder: Decoder[ContextField] = deriveDecoder
  implicit val sessionDataDecoder: Decoder[SessionData] = deriveDecoder
  implicit val createSessionResponseDecoder: Decoder[CreateSessionResponse] = deriveDecoder

  def main(args: Array[String]): Unit = {
    if (API_KEY == "<your_api_key>" || API_KEY.isEmpty) {
      println("❌ Please set API_KEY.")
      sys.exit(1)
    }
    if (EXTERNAL_USER_ID == "<your_external_user_id>" || EXTERNAL_USER_ID.isEmpty) {
      EXTERNAL_USER_ID = UUID.randomUUID().toString
      println(s"⚠️  Generated EXTERNAL_USER_ID: $EXTERNAL_USER_ID")
    }

    val contextMetadata: Array[Map[String, String]] = Array(
      Map("key" -> "userId", "value" -> "1"),
      Map("key" -> "name", "value" -> "John")
    )

    val sessionId = createChatSession()
    if (sessionId.nonEmpty) {
      println("\n--- Submitting Query ---")
      println(s"Using query: '$QUERY'")
      println(s"Using responseMode: '$RESPONSE_MODE'")
      submitQuery(sessionId, contextMetadata) // 👈 updated
    }
  }

  def createChatSession(): String = {
    val url = s"$BASE_URL/sessions"

    val contextMetadata: Array[Map[String, String]] = Array(
      Map("key" -> "userId", "value" -> "1"),
      Map("key" -> "name", "value" -> "John")
    )

    val body = Map(
      "agentIds" -> AGENT_IDS.toSeq.asJson,
      "externalUserId" -> EXTERNAL_USER_ID.asJson,
      "contextMetadata" -> contextMetadata.asJson
    ).asJson.noSpaces

    println(s"📡 Creating session with URL: $url")
    println(s"📝 Request body: $body")

    val backend = HttpClientSyncBackend()
    val request = basicRequest
      .post(uri"$url")
      .header("apikey", API_KEY)
      .contentType("application/json")
      .body(body)
      .response(asString)

    val response = request.send(backend)

    response.code.code match {
      case 201 =>
        decode[CreateSessionResponse](response.body.getOrElse("")) match {
          case Right(sessionResp) =>
            println(s"✅ Chat session created. Session ID: ${sessionResp.data.id}")

            if (sessionResp.data.contextMetadata.nonEmpty) {
              println("📋 Context Metadata:")
              sessionResp.data.contextMetadata.foreach { field =>
                println(s" - ${field.key}: ${field.value}")
              }
            }
            sessionResp.data.id
          case Left(e) =>
            println(s"❌ Error decoding session response: $e")
            ""
        }
      case code =>
        println(s"❌ Error creating chat session: $code - ${response.body.getOrElse("")}")
        ""
    }
  }

  def submitQuery(sessionId: String, contextMetadata: Array[Map[String, String]]): Unit = {
    val url = s"$BASE_URL/sessions/$sessionId/query"

    val modelConfigs = Map(
      "fulfillmentPrompt" -> FULFILLMENT_PROMPT.asJson,
      "stopSequences" -> STOP_SEQUENCES.toSeq.asJson,
      "temperature" -> TEMPERATURE.asJson,
      "topP" -> TOP_P.asJson,
      "maxTokens" -> MAX_TOKENS.asJson,
      "presencePenalty" -> PRESENCE_PENALTY.asJson,
      "frequencyPenalty" -> FREQUENCY_PENALTY.asJson
    )

    val body = Map(
      "endpointId" -> ENDPOINT_ID.asJson,
      "query" -> QUERY.asJson,
      "agentIds" -> AGENT_IDS.toSeq.asJson,
      "responseMode" -> RESPONSE_MODE.asJson,
      "reasoningMode" -> REASONING_MODE.asJson,
      "modelConfigs" -> modelConfigs.asJson
    ).asJson.noSpaces

    println(s"🚀 Submitting query to URL: $url")
    println(s"📝 Request body: $body")

    println()

    val backend = HttpClientSyncBackend()

    if (RESPONSE_MODE == "sync") {
      val request = basicRequest
        .post(uri"$url")
        .header("apikey", API_KEY)
        .contentType("application/json")
        .body(body)
        .response(asString)

      val response = request.send(backend)

      response.code.code match {
        case 200 =>
          parse(response.body.getOrElse("")) match {
            case Right(json) =>
              val original = json.asObject.getOrElse(JsonObject.empty)
              val data = original("data").flatMap(_.asObject).getOrElse(JsonObject.empty)
              val updatedData = data.add("contextMetadata", contextMetadata.asJson)
              val updatedOriginal = original.add("data", updatedData.asJson)
              println("✅ Final Response (with contextMetadata appended):")
              println(updatedOriginal.asJson.spaces2)
            case Left(e) =>
              println(s"❌ Error parsing sync response: $e")
          }
        case code =>
          println(s"❌ Error submitting sync query: $code - ${response.body.getOrElse("")}")
      }
    } else if (RESPONSE_MODE == "stream") {
      println("✅ Streaming Response...")

      val request = basicRequest
        .post(uri"$url")
        .header("apikey", API_KEY)
        .contentType("application/json")
        .body(body)
        .response(asStreamUnsafe[Source[String]])

      val response = request.send(backend)

      response.code.code match {
        case 200 =>
          val stream = response.body.getOrElse(Source.fromString(""))
          var fullAnswer = ""
          var finalSessionId = ""
          var finalMessageId = ""
          var metrics: Json = Json.obj()

          stream.getLines().foreach { line =>
            if (line.startsWith("data:")) {
              val dataStr = line.drop(5).trim
              if (dataStr == "[DONE]") {
                // break
              } else {
                parse(dataStr) match {
                  case Right(eventJson) =>
                    val event = eventJson.asObject.getOrElse(JsonObject.empty)
                    event("eventType").flatMap(_.asString) match {
                      case Some("fulfillment") =>
                        event("answer").flatMap(_.asString).foreach { ans => fullAnswer += ans }
                        event("sessionId").flatMap(_.asString).foreach { sid => finalSessionId = sid }
                        event("messageId").flatMap(_.asString).foreach { mid => finalMessageId = mid }
                      case Some("metricsLog") =>
                        event("publicMetrics").foreach { m => metrics = m }
                      case _ => // ignore
                    }
                  case Left(_) => // ignore
                }
              }
            }
          }

          val finalResponse = Json.obj(
            "message" -> "Chat query submitted successfully".asJson,
            "data" -> Json.obj(
              "sessionId" -> finalSessionId.asJson,
              "messageId" -> finalMessageId.asJson,
              "answer" -> fullAnswer.asJson,
              "metrics" -> metrics,
              "status" -> "completed".asJson,
              "contextMetadata" -> contextMetadata.asJson
            )
          )
          println("\n✅ Final Response (with contextMetadata appended):")
          println(finalResponse.spaces2)
        case code =>
          println(s"❌ Error submitting stream query: $code")
      }
    }
  }
}
