import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:uuid/uuid.dart';

const String apiKey = "<your_api_key>";
const String baseUrl = "https://api.on-demand.io/chat/v1";

String externalUserId = "<your_external_user_id>";
const String query = "<your_query>";
const String responseMode = ""; // Now dynamic
final List<String> agentIds = []; // Dynamic list from PluginIds
const String endpointId = "predefined-openai-gpt4.1";
const String reasoningMode = "grok-4-fast";
const String fulfillmentPrompt = "";
final List<String> stopSequences = []; // Dynamic list
const double temperature = 0.7;
const double topP = 1;
const int maxTokens = 0;
const double presencePenalty = 0;
const double frequencyPenalty = 0;

class ContextField {
  final String key;
  final String value;

  ContextField(this.key, this.value);

  factory ContextField.fromJson(Map<String, dynamic> json) {
    return ContextField(json['key'], json['value']);
  }
}

class SessionData {
  final String id;
  final List<ContextField> contextMetadata;

  SessionData(this.id, this.contextMetadata);

  factory SessionData.fromJson(Map<String, dynamic> json) {
    return SessionData(
      json['id'],
      (json['contextMetadata'] as List)
          .map((field) => ContextField.fromJson(field))
          .toList(),
    );
  }
}

class CreateSessionResponse {
  final SessionData data;

  CreateSessionResponse(this.data);

  factory CreateSessionResponse.fromJson(Map<String, dynamic> json) {
    return CreateSessionResponse(SessionData.fromJson(json['data']));
  }
}

void main() async {
  if (apiKey == "<your_api_key>" || apiKey.isEmpty) {
    print("❌ Please set API_KEY.");
    exit(1);
  }
  if (externalUserId == "<your_external_user_id>" || externalUserId.isEmpty) {
    externalUserId = Uuid().v4();
    print("⚠️  Generated EXTERNAL_USER_ID: $externalUserId");
  }

  final List<Map<String, String>> contextMetadata = [
    {"key": "userId", "value": "1"},
    {"key": "name", "value": "John"},
  ];

  final sessionId = await createChatSession();
  if (sessionId.isNotEmpty) {
    print("\n--- Submitting Query ---");
    print("Using query: '$query'");
    print("Using responseMode: '$responseMode'");
    await submitQuery(sessionId, contextMetadata); // 👈 updated
  }
}

Future<String> createChatSession() async {
  final url = Uri.parse("$baseUrl/sessions");

  final List<Map<String, String>> contextMetadata = [
    {"key": "userId", "value": "1"},
    {"key": "name", "value": "John"},
  ];

  final body = {
    "agentIds": agentIds,
    "externalUserId": externalUserId,
    "contextMetadata": contextMetadata,
  };

  final jsonBody = jsonEncode(body);

  print("📡 Creating session with URL: $url");
  print("📝 Request body: $jsonBody");

  final response = await http.post(
    url,
    headers: {
      "apikey": apiKey,
      "Content-Type": "application/json",
    },
    body: jsonBody,
  );

  if (response.statusCode == 201) {
    final sessionResp = CreateSessionResponse.fromJson(jsonDecode(response.body));

    print("✅ Chat session created. Session ID: ${sessionResp.data.id}");

    if (sessionResp.data.contextMetadata.isNotEmpty) {
      print("📋 Context Metadata:");
      for (final field in sessionResp.data.contextMetadata) {
        print(" - ${field.key}: ${field.value}");
      }
    }

    return sessionResp.data.id;
  } else {
    print("❌ Error creating chat session: ${response.statusCode} - ${response.body}");
    return "";
  }
}

Future<void> submitQuery(String sessionId, List<Map<String, String>> contextMetadata) async {
  final url = Uri.parse("$baseUrl/sessions/$sessionId/query");
  final body = {
    "endpointId": endpointId,
    "query": query,
    "agentIds": agentIds,
    "responseMode": responseMode,
    "reasoningMode": reasoningMode,
    "modelConfigs": {
      "fulfillmentPrompt": fulfillmentPrompt,
      "stopSequences": stopSequences,
      "temperature": temperature,
      "topP": topP,
      "maxTokens": maxTokens,
      "presencePenalty": presencePenalty,
      "frequencyPenalty": frequencyPenalty,
    },
  };

  final jsonBody = jsonEncode(body);

  print("🚀 Submitting query to URL: $url");
  print("📝 Request body: $jsonBody");

  if (responseMode == "sync") {
    final response = await http.post(
      url,
      headers: {
        "apikey": apiKey,
        "Content-Type": "application/json",
      },
      body: jsonBody,
    );

    print("");

    if (response.statusCode == 200) {
      final original = jsonDecode(response.body) as Map<String, dynamic>;

      // Append context metadata at the end
      if (original.containsKey("data")) {
        original["data"]["contextMetadata"] = contextMetadata;
      }

      final finalResponse = jsonEncode(original);
      print("✅ Final Response (with contextMetadata appended):");
      print(finalResponse);
    } else {
      print("❌ Error submitting sync query: ${response.statusCode} - ${response.body}");
    }
  } else if (responseMode == "stream") {
    final request = http.Request("POST", url);
    request.headers.addAll({
      "apikey": apiKey,
      "Content-Type": "application/json",
    });
    request.body = jsonBody;

    final streamedResponse = await request.send();

    print("");
    print("✅ Streaming Response...");

    String fullAnswer = "";
    String finalSessionId = "";
    String finalMessageId = "";
    Map<String, dynamic> metrics = {};

    final stream = streamedResponse.stream.transform(utf8.decoder).transform(const LineSplitter());

    await for (final line in stream) {
      if (line.startsWith("data:")) {
        final dataStr = line.substring(5).trim();

        if (dataStr == "[DONE]") {
          break;
        }

        try {
          final event = jsonDecode(dataStr) as Map<String, dynamic>;

          if (event["eventType"] == "fulfillment") {
            if (event.containsKey("answer")) {
              fullAnswer += event["answer"] as String;
            }
            if (event.containsKey("sessionId")) {
              finalSessionId = event["sessionId"] as String;
            }
            if (event.containsKey("messageId")) {
              finalMessageId = event["messageId"] as String;
            }
          } else if (event["eventType"] == "metricsLog") {
            if (event.containsKey("publicMetrics")) {
              metrics = event["publicMetrics"] as Map<String, dynamic>;
            }
          }
        } catch (e) {
          continue;
        }
      }
    }

    final finalResponse = {
      "message": "Chat query submitted successfully",
      "data": {
        "sessionId": finalSessionId,
        "messageId": finalMessageId,
        "answer": fullAnswer,
        "metrics": metrics,
        "status": "completed",
        "contextMetadata": contextMetadata,
      },
    };

    final formatted = jsonEncode(finalResponse);
    print("\n✅ Final Response (with contextMetadata appended):");
    print(formatted);
  }
}
