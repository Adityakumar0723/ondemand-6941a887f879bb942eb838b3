import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Main {
    private static final String API_KEY = "<your_api_key>";
    private static final String BASE_URL = "https://api.on-demand.io/chat/v1";

    private static String EXTERNAL_USER_ID = "<your_external_user_id>";
    private static final String QUERY = "<your_query>";
    private static final String RESPONSE_MODE = ""; // Now dynamic
    private static final String[] AGENT_IDS = {}; // Dynamic array from PluginIds
    private static final String ENDPOINT_ID = "predefined-openai-gpt4.1";
    private static final String REASONING_MODE = "grok-4-fast";
    private static final String FULFILLMENT_PROMPT = "";
    private static final String[] STOP_SEQUENCES = {}; // Dynamic array
    private static final double TEMPERATURE = 0.7;
    private static final double TOP_P = 1;
    private static final int MAX_TOKENS = 0;
    private static final double PRESENCE_PENALTY = 0;
    private static final double FREQUENCY_PENALTY = 0;

    static class ContextField {
        String key;
        String value;

        ContextField(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    static class SessionData {
        String id;
        @SerializedName("contextMetadata")
        List<ContextField> contextMetadata;
    }

    static class CreateSessionResponse {
        SessionData data;
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.equals("<your_api_key>") || API_KEY.isEmpty()) {
            System.out.println("❌ Please set API_KEY.");
            System.exit(1);
        }
        if (EXTERNAL_USER_ID.equals("<your_external_user_id>") || EXTERNAL_USER_ID.isEmpty()) {
            EXTERNAL_USER_ID = UUID.randomUUID().toString();
            System.out.println("⚠️  Generated EXTERNAL_USER_ID: " + EXTERNAL_USER_ID);
        }

        List<Map<String, String>> contextMetadata = new ArrayList<>();
        contextMetadata.add(Map.of("key", "userId", "value", "1"));
        contextMetadata.add(Map.of("key", "name", "value", "John"));

        String sessionId = createChatSession();
        if (!sessionId.isEmpty()) {
            System.out.println("\n--- Submitting Query ---");
            System.out.println("Using query: '" + QUERY + "'");
            System.out.println("Using responseMode: '" + RESPONSE_MODE + "'");
            submitQuery(sessionId, contextMetadata); // 👈 updated
        }
    }

    private static String createChatSession() throws Exception {
        String url = BASE_URL + "/sessions";

        List<Map<String, String>> contextMetadata = new ArrayList<>();
        contextMetadata.add(Map.of("key", "userId", "value", "1"));
        contextMetadata.add(Map.of("key", "name", "value", "John"));

        Map<String, Object> body = new HashMap<>();
        body.put("agentIds", AGENT_IDS);
        body.put("externalUserId", EXTERNAL_USER_ID);
        body.put("contextMetadata", contextMetadata);

        Gson gson = new Gson();
        String jsonBody = gson.toJson(body);

        System.out.println("📡 Creating session with URL: " + url);
        System.out.println("📝 Request body: " + jsonBody);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("apikey", API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 201) {
            CreateSessionResponse sessionResp = gson.fromJson(response.body(), CreateSessionResponse.class);

            System.out.println("✅ Chat session created. Session ID: " + sessionResp.data.id);

            if (!sessionResp.data.contextMetadata.isEmpty()) {
                System.out.println("📋 Context Metadata:");
                for (ContextField field : sessionResp.data.contextMetadata) {
                    System.out.println(" - " + field.key + ": " + field.value);
                }
            }

            return sessionResp.data.id;
        } else {
            System.out.println("❌ Error creating chat session: " + response.statusCode() + " - " + response.body());
            return "";
        }
    }

    private static void submitQuery(String sessionId, List<Map<String, String>> contextMetadata) throws Exception {
        String url = BASE_URL + "/sessions/" + sessionId + "/query";

        Map<String, Object> modelConfigs = new HashMap<>();
        modelConfigs.put("fulfillmentPrompt", FULFILLMENT_PROMPT);
        modelConfigs.put("stopSequences", STOP_SEQUENCES);
        modelConfigs.put("temperature", TEMPERATURE);
        modelConfigs.put("topP", TOP_P);
        modelConfigs.put("maxTokens", MAX_TOKENS);
        modelConfigs.put("presencePenalty", PRESENCE_PENALTY);
        modelConfigs.put("frequencyPenalty", FREQUENCY_PENALTY);

        Map<String, Object> body = new HashMap<>();
        body.put("endpointId", ENDPOINT_ID);
        body.put("query", QUERY);
        body.put("agentIds", AGENT_IDS);
        body.put("responseMode", RESPONSE_MODE);
        body.put("reasoningMode", REASONING_MODE);
        body.put("modelConfigs", modelConfigs);

        Gson gson = new Gson();
        String jsonBody = gson.toJson(body);

        System.out.println("🚀 Submitting query to URL: " + url);
        System.out.println("📝 Request body: " + jsonBody);

        System.out.println();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("apikey", API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        if (RESPONSE_MODE.equals("sync")) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject original = JsonParser.parseString(response.body()).getAsJsonObject();

                // Append context metadata at the end
                JsonObject data = original.getAsJsonObject("data");
                if (data != null) {
                    data.add("contextMetadata", gson.toJsonTree(contextMetadata));
                }

                String finalResponse = new GsonBuilder().setPrettyPrinting().create().toJson(original);
                System.out.println("✅ Final Response (with contextMetadata appended):");
                System.out.println(finalResponse);
            } else {
                System.out.println("❌ Error submitting sync query: " + response.statusCode() + " - " + response.body());
            }
        } else if (RESPONSE_MODE.equals("stream")) {
            System.out.println("✅ Streaming Response...");

            HttpResponse<InputStreamReader> response = client.send(request, HttpResponse.BodyHandlers.ofReader());

            if (response.statusCode() != 200) {
                System.out.println("❌ Error submitting stream query: " + response.statusCode());
                return;
            }

            StringBuilder fullAnswer = new StringBuilder();
            String finalSessionId = "";
            String finalMessageId = "";
            JsonObject metrics = new JsonObject();

            try (BufferedReader reader = new BufferedReader(response.body())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String dataStr = line.substring(5).trim();

                        if (dataStr.equals("[DONE]")) {
                            break;
                        }

                        JsonObject event = JsonParser.parseString(dataStr).getAsJsonObject();
                        String eventType = event.get("eventType").getAsString();

                        if (eventType.equals("fulfillment")) {
                            if (event.has("answer")) {
                                fullAnswer.append(event.get("answer").getAsString());
                            }
                            if (event.has("sessionId")) {
                                finalSessionId = event.get("sessionId").getAsString();
                            }
                            if (event.has("messageId")) {
                                finalMessageId = event.get("messageId").getAsString();
                            }
                        } else if (eventType.equals("metricsLog")) {
                            if (event.has("publicMetrics")) {
                                metrics = event.getAsJsonObject("publicMetrics");
                            }
                        }
                    }
                }
            }

            JsonObject finalResponse = new JsonObject();
            finalResponse.addProperty("message", "Chat query submitted successfully");
            JsonObject data = new JsonObject();
            data.addProperty("sessionId", finalSessionId);
            data.addProperty("messageId", finalMessageId);
            data.addProperty("answer", fullAnswer.toString());
            data.add("metrics", metrics);
            data.addProperty("status", "completed");
            data.add("contextMetadata", gson.toJsonTree(contextMetadata));
            finalResponse.add("data", data);

            String formatted = new GsonBuilder().setPrettyPrinting().create().toJson(finalResponse);
            System.out.println("\n✅ Final Response (with contextMetadata appended):");
            System.out.println(formatted);
        }
    }
}
