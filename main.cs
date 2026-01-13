using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Tasks;

class Program
{
    private const string API_KEY = "<your_api_key>";
    private const string BASE_URL = "https://api.on-demand.io/chat/v1";

    private static string EXTERNAL_USER_ID = "<your_external_user_id>";
    private const string QUERY = "<your_query>";
    private const string RESPONSE_MODE = ""; // Now dynamic
    private static readonly string[] AGENT_IDS = {}; // Dynamic array from PluginIds
    private const string ENDPOINT_ID = "predefined-openai-gpt4.1";
    private const string REASONING_MODE = "grok-4-fast";
    private const string FULFILLMENT_PROMPT = "";
    private static readonly string[] STOP_SEQUENCES = {}; // Dynamic array
    private const double TEMPERATURE = 0.7;
    private const double TOP_P = 1;
    private const int MAX_TOKENS = 0;
    private const double PRESENCE_PENALTY = 0;
    private const double FREQUENCY_PENALTY = 0;

    public class ContextField
    {
        public string key { get; set; }
        public string value { get; set; }
    }

    public class SessionData
    {
        public string id { get; set; }
        [JsonPropertyName("contextMetadata")]
        public List<ContextField> context_metadata { get; set; }
    }

    public class CreateSessionResponse
    {
        public SessionData data { get; set; }
    }

    static async Task Main(string[] args)
    {
        if (API_KEY == "<your_api_key>" || string.IsNullOrEmpty(API_KEY))
        {
            Console.WriteLine("❌ Please set API_KEY.");
            Environment.Exit(1);
        }
        if (EXTERNAL_USER_ID == "<your_external_user_id>" || string.IsNullOrEmpty(EXTERNAL_USER_ID))
        {
            EXTERNAL_USER_ID = Guid.NewGuid().ToString();
            Console.WriteLine($"⚠️  Generated EXTERNAL_USER_ID: {EXTERNAL_USER_ID}");
        }

        var contextMetadata = new List<Dictionary<string, string>>
        {
            new Dictionary<string, string> { { "key", "userId" }, { "value", "1" } },
            new Dictionary<string, string> { { "key", "name" }, { "value", "John" } }
        };

        string sessionId = await CreateChatSession();
        if (!string.IsNullOrEmpty(sessionId))
        {
            Console.WriteLine("\n--- Submitting Query ---");
            Console.WriteLine($"Using query: '{QUERY}'");
            Console.WriteLine($"Using responseMode: '{RESPONSE_MODE}'");
            await SubmitQuery(sessionId, contextMetadata); // 👈 updated
        }
    }

    private static async Task<string> CreateChatSession()
    {
        string url = $"{BASE_URL}/sessions";

        var contextMetadata = new List<Dictionary<string, string>>
        {
            new Dictionary<string, string> { { "key", "userId" }, { "value", "1" } },
            new Dictionary<string, string> { { "key", "name" }, { "value", "John" } }
        };

        var body = new
        {
            agentIds = AGENT_IDS,
            externalUserId = EXTERNAL_USER_ID,
            contextMetadata = contextMetadata
        };

        string jsonBody = JsonSerializer.Serialize(body);

        Console.WriteLine($"📡 Creating session with URL: {url}");
        Console.WriteLine($"📝 Request body: {jsonBody}");

        using var client = new HttpClient();
        client.DefaultRequestHeaders.Add("apikey", API_KEY);

        var content = new StringContent(jsonBody, Encoding.UTF8, "application/json");
        var response = await client.PostAsync(url, content);

        if (response.StatusCode == System.Net.HttpStatusCode.Created)
        {
            string responseBody = await response.Content.ReadAsStringAsync();
            var sessionResp = JsonSerializer.Deserialize<CreateSessionResponse>(responseBody);

            Console.WriteLine($"✅ Chat session created. Session ID: {sessionResp.data.id}");

            if (sessionResp.data.context_metadata.Count > 0)
            {
                Console.WriteLine("📋 Context Metadata:");
                foreach (var field in sessionResp.data.context_metadata)
                {
                    Console.WriteLine($" - {field.key}: {field.value}");
                }
            }

            return sessionResp.data.id;
        }
        else
        {
            string respBody = await response.Content.ReadAsStringAsync();
            Console.WriteLine($"❌ Error creating chat session: {(int)response.StatusCode} - {respBody}");
            return "";
        }
    }

    private static async Task SubmitQuery(string sessionId, List<Dictionary<string, string>> contextMetadata)
    {
        string url = $"{BASE_URL}/sessions/{sessionId}/query";

        var body = new
        {
            endpointId = ENDPOINT_ID,
            query = QUERY,
            agentIds = AGENT_IDS,
            responseMode = RESPONSE_MODE,
            reasoningMode = REASONING_MODE,
            modelConfigs = new
            {
                fulfillmentPrompt = FULFILLMENT_PROMPT,
                stopSequences = STOP_SEQUENCES,
                temperature = TEMPERATURE,
                topP = TOP_P,
                maxTokens = MAX_TOKENS,
                presencePenalty = PRESENCE_PENALTY,
                frequencyPenalty = FREQUENCY_PENALTY
            }
        };

        string jsonBody = JsonSerializer.Serialize(body);

        Console.WriteLine($"🚀 Submitting query to URL: {url}");
        Console.WriteLine($"📝 Request body: {jsonBody}");

        Console.WriteLine();

        using var client = new HttpClient();
        client.DefaultRequestHeaders.Add("apikey", API_KEY);

        var content = new StringContent(jsonBody, Encoding.UTF8, "application/json");

        if (RESPONSE_MODE == "sync")
        {
            var response = await client.PostAsync(url, content);

            if (response.StatusCode == System.Net.HttpStatusCode.OK)
            {
                string responseBody = await response.Content.ReadAsStringAsync();
                var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
                var original = JsonSerializer.Deserialize<Dictionary<string, object>>(responseBody, options);

                if (original.ContainsKey("data"))
                {
                    var data = JsonSerializer.Deserialize<Dictionary<string, object>>(original["data"].ToString());
                    data["contextMetadata"] = contextMetadata;
                    original["data"] = data;
                }

                string final = JsonSerializer.Serialize(original, new JsonSerializerOptions { WriteIndented = true });
                Console.WriteLine("✅ Final Response (with contextMetadata appended):");
                Console.WriteLine(final);
            }
            else
            {
                string respBody = await response.Content.ReadAsStringAsync();
                Console.WriteLine($"❌ Error submitting sync query: {(int)response.StatusCode} - {respBody}");
            }
        }
        else if (RESPONSE_MODE == "stream")
        {
            Console.WriteLine("✅ Streaming Response...");

            var response = await client.PostAsync(url, content);

            if (response.StatusCode != System.Net.HttpStatusCode.OK)
            {
                string respBody = await response.Content.ReadAsStringAsync();
                Console.WriteLine($"❌ Error submitting stream query: {(int)response.StatusCode} - {respBody}");
                return;
            }

            string fullAnswer = "";
            string finalSessionId = "";
            string finalMessageId = "";
            Dictionary<string, object> metrics = new Dictionary<string, object>();

            using var stream = await response.Content.ReadAsStreamAsync();
            using var reader = new StreamReader(stream);

            string line;
            while ((line = await reader.ReadLineAsync()) != null)
            {
                if (line.StartsWith("data:"))
                {
                    string dataStr = line.Substring(5).Trim();

                    if (dataStr == "[DONE]")
                    {
                        break;
                    }

                    try
                    {
                        var eventDict = JsonSerializer.Deserialize<Dictionary<string, object>>(dataStr);
                        if (eventDict["eventType"].ToString() == "fulfillment")
                        {
                            if (eventDict.ContainsKey("answer"))
                            {
                                fullAnswer += eventDict["answer"].ToString();
                            }
                            if (eventDict.ContainsKey("sessionId"))
                            {
                                finalSessionId = eventDict["sessionId"].ToString();
                            }
                            if (eventDict.ContainsKey("messageId"))
                            {
                                finalMessageId = eventDict["messageId"].ToString();
                            }
                        }
                        else if (eventDict["eventType"].ToString() == "metricsLog")
                        {
                            if (eventDict.ContainsKey("publicMetrics"))
                            {
                                metrics = JsonSerializer.Deserialize<Dictionary<string, object>>(eventDict["publicMetrics"].ToString());
                            }
                        }
                    }
                    catch
                    {
                        continue;
                    }
                }
            }

            var finalResponse = new
            {
                message = "Chat query submitted successfully",
                data = new
                {
                    sessionId = finalSessionId,
                    messageId = finalMessageId,
                    answer = fullAnswer,
                    metrics = metrics,
                    status = "completed",
                    contextMetadata = contextMetadata
                }
            };

            string formatted = JsonSerializer.Serialize(finalResponse, new JsonSerializerOptions { WriteIndented = true });
            Console.WriteLine("\n✅ Final Response (with contextMetadata appended):");
            Console.WriteLine(formatted);
        }
    }
}
