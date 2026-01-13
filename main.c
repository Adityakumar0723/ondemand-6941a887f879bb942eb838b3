#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <curl/curl.h>
#include <uuid/uuid.h>  // Assuming libuuid is available; install if needed
#include <cjson/cJSON.h>  // Assuming cJSON library is included for JSON handling

#define API_KEY "<your_api_key>"
#define BASE_URL "https://api.on-demand.io/chat/v1"

char* EXTERNAL_USER_ID = "<your_external_user_id>";
#define QUERY "<your_query>"
#define RESPONSE_MODE ""  // Now dynamic
const char* AGENT_IDS[] = {, NULL };  // Dynamic array from PluginIds, NULL-terminated
#define ENDPOINT_ID "predefined-openai-gpt4.1"
#define REASONING_MODE "grok-4-fast"
#define FULFILLMENT_PROMPT ""
const char* STOP_SEQUENCES[] = {, NULL };  // Dynamic array, NULL-terminated
#define TEMPERATURE 0.7
#define TOP_P 1
#define MAX_TOKENS 0
#define PRESENCE_PENALTY 0
#define FREQUENCY_PENALTY 0

struct MemoryStruct {
    char *memory;
    size_t size;
};

static size_t WriteMemoryCallback(void *contents, size_t size, size_t nmemb, void *userp) {
    size_t realsize = size * nmemb;
    struct MemoryStruct *mem = (struct MemoryStruct *)userp;

    char *ptr = realloc(mem->memory, mem->size + realsize + 1);
    if (ptr == NULL) {
        return 0;  // out of memory
    }

    mem->memory = ptr;
    memcpy(&(mem->memory[mem->size]), contents, realsize);
    mem->size += realsize;
    mem->memory[mem->size] = 0;

    return realsize;
}

char* perform_post(const char* url, const char* json_body, long* response_code) {
    CURL *curl;
    CURLcode res;
    struct MemoryStruct chunk;
    struct curl_slist *headers = NULL;

    chunk.memory = malloc(1);
    chunk.size = 0;

    curl = curl_easy_init();
    if (curl) {
        char apikey_header[256];
        snprintf(apikey_header, sizeof(apikey_header), "apikey: %s", API_KEY);
        headers = curl_slist_append(headers, apikey_header);
        headers = curl_slist_append(headers, "Content-Type: application/json");

        curl_easy_setopt(curl, CURLOPT_URL, url);
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, json_body);
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteMemoryCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, (void *)&chunk);

        res = curl_easy_perform(curl);

        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, response_code);

        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
    }

    return chunk.memory;
}

char* create_chat_session() {
    char url[256];
    snprintf(url, sizeof(url), "%s/sessions", BASE_URL);

    cJSON *context_metadata = cJSON_CreateArray();
    cJSON *item1 = cJSON_CreateObject();
    cJSON_AddStringToObject(item1, "key", "userId");
    cJSON_AddStringToObject(item1, "value", "1");
    cJSON_AddItemToArray(context_metadata, item1);
    cJSON *item2 = cJSON_CreateObject();
    cJSON_AddStringToObject(item2, "key", "name");
    cJSON_AddStringToObject(item2, "value", "John");
    cJSON_AddItemToArray(context_metadata, item2);

    cJSON *body = cJSON_CreateObject();
    cJSON *agent_ids = cJSON_CreateStringArray(AGENT_IDS, sizeof(AGENT_IDS)/sizeof(AGENT_IDS[0]) - 1);  // Exclude NULL
    cJSON_AddItemToObject(body, "agentIds", agent_ids);
    cJSON_AddStringToObject(body, "externalUserId", EXTERNAL_USER_ID);
    cJSON_AddItemToObject(body, "contextMetadata", context_metadata);

    char *json_body = cJSON_PrintUnformatted(body);

    printf("📡 Creating session with URL: %s\n", url);
    printf("📝 Request body: %s\n", json_body);

    long response_code;
    char *response = perform_post(url, json_body, &response_code);

    if (response_code == 201) {
        cJSON *session_resp_data = cJSON_Parse(response);
        char *session_id = cJSON_GetObjectItem(cJSON_GetObjectItem(session_resp_data, "data"), "id")->valuestring;
        printf("✅ Chat session created. Session ID: %s\n", session_id);

        cJSON *cm = cJSON_GetObjectItem(cJSON_GetObjectItem(session_resp_data, "data"), "contextMetadata");
        if (cJSON_GetArraySize(cm) > 0) {
            printf("📋 Context Metadata:\n");
            for (int i = 0; i < cJSON_GetArraySize(cm); i++) {
                cJSON *field = cJSON_GetArrayItem(cm, i);
                printf(" - %s: %s\n", cJSON_GetObjectItem(field, "key")->valuestring, cJSON_GetObjectItem(field, "value")->valuestring);
            }
        }

        char *result = strdup(session_id);
        free(response);
        cJSON_Delete(session_resp_data);
        cJSON_Delete(body);
        free(json_body);
        return result;
    } else {
        printf("❌ Error creating chat session: %ld - %s\n", response_code, response);
        free(response);
        cJSON_Delete(body);
        free(json_body);
        return "";
    }
}

void submit_query(const char* session_id, cJSON* context_metadata) {
    char url[256];
    snprintf(url, sizeof(url), "%s/sessions/%s/query", BASE_URL, session_id);

    cJSON *model_configs = cJSON_CreateObject();
    cJSON_AddStringToObject(model_configs, "fulfillmentPrompt", FULFILLMENT_PROMPT);
    cJSON *stop_sequences = cJSON_CreateStringArray(STOP_SEQUENCES, sizeof(STOP_SEQUENCES)/sizeof(STOP_SEQUENCES[0]) - 1);
    cJSON_AddItemToObject(model_configs, "stopSequences", stop_sequences);
    cJSON_AddNumberToObject(model_configs, "temperature", TEMPERATURE);
    cJSON_AddNumberToObject(model_configs, "topP", TOP_P);
    cJSON_AddNumberToObject(model_configs, "maxTokens", MAX_TOKENS);
    cJSON_AddNumberToObject(model_configs, "presencePenalty", PRESENCE_PENALTY);
    cJSON_AddNumberToObject(model_configs, "frequencyPenalty", FREQUENCY_PENALTY);

    cJSON *body = cJSON_CreateObject();
    cJSON_AddStringToObject(body, "endpointId", ENDPOINT_ID);
    cJSON_AddStringToObject(body, "query", QUERY);
    cJSON *agent_ids = cJSON_CreateStringArray(AGENT_IDS, sizeof(AGENT_IDS)/sizeof(AGENT_IDS[0]) - 1);
    cJSON_AddItemToObject(body, "agentIds", agent_ids);
    cJSON_AddStringToObject(body, "responseMode", RESPONSE_MODE);
    cJSON_AddStringToObject(body, "reasoningMode", REASONING_MODE);
    cJSON_AddItemToObject(body, "modelConfigs", model_configs);

    char *json_body = cJSON_PrintUnformatted(body);

    printf("🚀 Submitting query to URL: %s\n", url);
    printf("📝 Request body: %s\n", json_body);
    printf("\n");

    if (strcmp(RESPONSE_MODE, "sync") == 0) {
        long response_code;
        char *response = perform_post(url, json_body, &response_code);

        if (response_code == 200) {
            cJSON *original = cJSON_Parse(response);
            cJSON *data = cJSON_GetObjectItem(original, "data");
            cJSON_AddItemToObject(data, "contextMetadata", cJSON_Duplicate(context_metadata, 1));

            char *final = cJSON_Print(original);
            printf("✅ Final Response (with contextMetadata appended):\n%s\n", final);
            free(final);
            cJSON_Delete(original);
        } else {
            printf("❌ Error submitting sync query: %ld - %s\n", response_code, response);
        }
        free(response);
    } else if (strcmp(RESPONSE_MODE, "stream") == 0) {
        printf("✅ Streaming Response...\n");

        // Streaming in C with libcurl requires custom handling
        // For simplicity, assume full response is read and processed line by line
        long response_code;
        char *response = perform_post(url, json_body, &response_code);  // Note: for real streaming, use multi or other

        if (response_code != 200) {
            printf("❌ Error submitting stream query: %ld - %s\n", response_code, response);
            free(response);
            cJSON_Delete(body);
            free(json_body);
            return;
        }

        // Process response as stream (simulated by splitting lines)
        char *full_answer = NULL;
        char *final_session_id = NULL;
        char *final_message_id = NULL;
        cJSON *metrics = cJSON_CreateObject();

        char *line = strtok(response, "\n");
        while (line != NULL) {
            if (strncmp(line, "data:", 5) == 0) {
                char *data_str = line + 5;
                while (*data_str == ' ') data_str++;  // trim

                if (strcmp(data_str, "[DONE]") == 0) {
                    break;
                }

                cJSON *event = cJSON_Parse(data_str);
                if (event) {
                    char *event_type = cJSON_GetObjectItem(event, "eventType")->valuestring;
                    if (strcmp(event_type, "fulfillment") == 0) {
                        if (cJSON_GetObjectItem(event, "answer")) {
                            char *answer = cJSON_GetObjectItem(event, "answer")->valuestring;
                            full_answer = realloc(full_answer, (full_answer ? strlen(full_answer) : 0) + strlen(answer) + 1);
                            strcat(full_answer, answer);
                        }
                        if (cJSON_GetObjectItem(event, "sessionId")) {
                            final_session_id = strdup(cJSON_GetObjectItem(event, "sessionId")->valuestring);
                        }
                        if (cJSON_GetObjectItem(event, "messageId")) {
                            final_message_id = strdup(cJSON_GetObjectItem(event, "messageId")->valuestring);
                        }
                    } else if (strcmp(event_type, "metricsLog") == 0) {
                        if (cJSON_GetObjectItem(event, "publicMetrics")) {
                            cJSON_Delete(metrics);
                            metrics = cJSON_Duplicate(cJSON_GetObjectItem(event, "publicMetrics"), 1);
                        }
                    }
                    cJSON_Delete(event);
                }
            }
            line = strtok(NULL, "\n");
        }

        cJSON *final_response = cJSON_CreateObject();
        cJSON_AddStringToObject(final_response, "message", "Chat query submitted successfully");
        cJSON *data = cJSON_CreateObject();
        cJSON_AddStringToObject(data, "sessionId", final_session_id ? final_session_id : "");
        cJSON_AddStringToObject(data, "messageId", final_message_id ? final_message_id : "");
        cJSON_AddStringToObject(data, "answer", full_answer ? full_answer : "");
        cJSON_AddItemToObject(data, "metrics", metrics);
        cJSON_AddStringToObject(data, "status", "completed");
        cJSON_AddItemToObject(data, "contextMetadata", cJSON_Duplicate(context_metadata, 1));
        cJSON_AddItemToObject(final_response, "data", data);

        char *formatted = cJSON_Print(final_response);
        printf("\n✅ Final Response (with contextMetadata appended):\n%s\n", formatted);

        free(formatted);
        free(full_answer);
        free(final_session_id);
        free(final_message_id);
        cJSON_Delete(final_response);
        free(response);
    }

    cJSON_Delete(body);
    free(json_body);
}

int main() {
    if (strcmp(API_KEY, "<your_api_key>") == 0 || strlen(API_KEY) == 0) {
        printf("❌ Please set API_KEY.\n");
        return 1;
    }
    if (strcmp(EXTERNAL_USER_ID, "<your_external_user_id>") == 0 || strlen(EXTERNAL_USER_ID) == 0) {
        uuid_t uuid;
        uuid_generate_random(uuid);
        char str[37];
        uuid_unparse(uuid, str);
        EXTERNAL_USER_ID = strdup(str);
        printf("⚠️  Generated EXTERNAL_USER_ID: %s\n", EXTERNAL_USER_ID);
    }

    cJSON *context_metadata = cJSON_CreateArray();
    cJSON *item1 = cJSON_CreateObject();
    cJSON_AddStringToObject(item1, "key", "userId");
    cJSON_AddStringToObject(item1, "value", "1");
    cJSON_AddItemToArray(context_metadata, item1);
    cJSON *item2 = cJSON_CreateObject();
    cJSON_AddStringToObject(item2, "key", "name");
    cJSON_AddStringToObject(item2, "value", "John");
    cJSON_AddItemToArray(context_metadata, item2);

    char *session_id = create_chat_session();
    if (strlen(session_id) > 0) {
        printf("\n--- Submitting Query ---\n");
        printf("Using query: '%s'\n", QUERY);
        printf("Using responseMode: '%s'\n", RESPONSE_MODE);
        submit_query(session_id, context_metadata);  // 👈 updated
    }

    free(session_id);
    cJSON_Delete(context_metadata);
    return 0;
}
