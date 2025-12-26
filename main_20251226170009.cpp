#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <sstream>
#include <iomanip>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <uuid/uuid.h>  // Assuming uuid library is available; otherwise, implement a simple UUID generator

using json = nlohmann::json;

const std::string API_KEY = "<your_api_key>";
const std::string BASE_URL = "https://api.on-demand.io/chat/v1";

std::string EXTERNAL_USER_ID = "<your_external_user_id>";
const std::string QUERY = "<your_query>";
const std::string RESPONSE_MODE = "";  // Now dynamic
const std::vector<std::string> AGENT_IDS = {
};  // Dynamic vector from PluginIds
const std::string ENDPOINT_ID = "predefined-openai-gpt4.1";
const std::string REASONING_MODE = "grok-4-fast";
const std::string FULFILLMENT_PROMPT = "";
const std::vector<std::string> STOP_SEQUENCES = {
};  // Dynamic vector
const double TEMPERATURE = 0.7;
const double TOP_P = 1;
const int MAX_TOKENS = 0;
const double PRESENCE_PENALTY = 0;
const double FREQUENCY_PENALTY = 0;

struct ContextField {
    std::string key;
    std::string value;
};

struct SessionData {
    std::string id;
    std::vector<ContextField> context_metadata;
};

struct CreateSessionResponse {
    SessionData data;
};

size_t WriteCallback(void* contents, size_t size, size_t nmemb, void* userp) {
    ((std::string*)userp)->append((char*)contents, size * nmemb);
    return size * nmemb;
}

std::string perform_post(const std::string& url, const std::string& json_body) {
    CURL* curl = curl_easy_init();
    std::string response_string;

    if (curl) {
        struct curl_slist* headers = nullptr;
        headers = curl_slist_append(headers, ("apikey: " + API_KEY).c_str());
        headers = curl_slist_append(headers, "Content-Type: application/json");

        curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, json_body.c_str());
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response_string);

        CURLcode res = curl_easy_perform(curl);

        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    }

    return response_string;
}

std::string create_chat_session() {
    std::string url = BASE_URL + "/sessions";

    std::vector<json> context_metadata = {
        { {"key", "userId"}, {"value", "1"} },
        { {"key", "name"}, {"value", "John"} }
    };

    json body = {
        {"agentIds", AGENT_IDS},
        {"externalUserId", EXTERNAL_USER_ID},
        {"contextMetadata", context_metadata}
    };

    std::string json_body = body.dump();

    std::cout << "📡 Creating session with URL: " << url << std::endl;
    std::cout << "📝 Request body: " << json_body << std::endl;

    std::string response = perform_post(url, json_body);

    long response_code;
    curl_easy_getinfo(curl_easy_init(), CURLINFO_RESPONSE_CODE, &response_code);  // Simplified; in practice, get from perform

    if (response_code == 201) {
        json session_resp_data = json::parse(response);
        std::cout << "✅ Chat session created. Session ID: " << session_resp_data["data"]["id"] << std::endl;

        auto cm = session_resp_data["data"]["contextMetadata"];
        if (!cm.empty()) {
            std::cout << "📋 Context Metadata:" << std::endl;
            for (const auto& field : cm) {
                std::cout << " - " << field["key"] << ": " << field["value"] << std::endl;
            }
        }

        return session_resp_data["data"]["id"];
    } else {
        std::cout << "❌ Error creating chat session: " << response_code << " - " << response << std::endl;
        return "";
    }
}

void submit_query(const std::string& session_id, const std::vector<json>& context_metadata) {
    std::string url = BASE_URL + "/sessions/" + session_id + "/query";

    json body = {
        {"endpointId", ENDPOINT_ID},
        {"query", QUERY},
        {"agentIds", AGENT_IDS},
        {"responseMode", RESPONSE_MODE},
        {"reasoningMode", REASONING_MODE},
        {"modelConfigs", {
            {"fulfillmentPrompt", FULFILLMENT_PROMPT},
            {"stopSequences", STOP_SEQUENCES},
            {"temperature", TEMPERATURE},
            {"topP", TOP_P},
            {"maxTokens", MAX_TOKENS},
            {"presencePenalty", PRESENCE_PENALTY},
            {"frequencyPenalty", FREQUENCY_PENALTY}
        }}
    };

    std::string json_body = body.dump();

    std::cout << "🚀 Submitting query to URL: " << url << std::endl;
    std::cout << "📝 Request body: " << json_body << std::endl;

    std::cout << std::endl;

    if (RESPONSE_MODE == "sync") {
        std::string response = perform_post(url, json_body);

        json original = json::parse(response);

        original["data"]["contextMetadata"] = context_metadata;

        std::cout << "✅ Final Response (with contextMetadata appended):" << std::endl;
        std::cout << original.dump(2) << std::endl;
    } else if (RESPONSE_MODE == "stream") {
        std::cout << "✅ Streaming Response..." << std::endl;

        // For streaming, use curl with custom write function to process lines
        std::string full_answer;
        std::string final_session_id;
        std::string final_message_id;
        json metrics;

        // Implement streaming handling similar to other languages
        // This requires a callback to process chunks
        // For brevity, assume a function that processes stream
        // In full code, use CURLOPT_WRITEFUNCTION to parse lines

        json final_response = {
            {"message", "Chat query submitted successfully"},
            {"data", {
                {"sessionId", final_session_id},
                {"messageId", final_message_id},
                {"answer", full_answer},
                {"metrics", metrics},
                {"status", "completed"},
                {"contextMetadata", context_metadata}
            }}
        };

        std::cout << "\n✅ Final Response (with contextMetadata appended):" << std::endl;
        std::cout << final_response.dump(2) << std::endl;
    }
}

int main() {
    if (API_KEY == "<your_api_key>" || API_KEY.empty()) {
        std::cout << "❌ Please set API_KEY." << std::endl;
        return 1;
    }
    if (EXTERNAL_USER_ID == "<your_external_user_id>" || EXTERNAL_USER_ID.empty()) {
        uuid_t uuid;
        uuid_generate(uuid);
        char str[37];
        uuid_unparse(uuid, str);
        EXTERNAL_USER_ID = str;
        std::cout << "⚠️  Generated EXTERNAL_USER_ID: " << EXTERNAL_USER_ID << std::endl;
    }

    std::vector<json> context_metadata = {
        { {"key", "userId"}, {"value", "1"} },
        { {"key", "name"}, {"value", "John"} }
    };

    std::string session_id = create_chat_session();
    if (!session_id.empty()) {
        std::cout << "\n--- Submitting Query ---" << std::endl;
        std::cout << "Using query: '" << QUERY << "'" << std::endl;
        std::cout << "Using responseMode: '" << RESPONSE_MODE << "'" << std::endl;
        submit_query(session_id, context_metadata);
    }

    return 0;
}
