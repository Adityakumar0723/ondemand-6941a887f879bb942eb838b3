package main

import (
    "bufio"
    "bytes"
    "encoding/json"
    "fmt"
    "io/util"
    "mime/multipart"
    "net/http"
    "os"
    "path/filepath"
    "strings"

    "github.com/google/uuid"
)

const (
    API_KEY  = "<your_api_key>"
    BASE_URL = "https://api.on-demand.io"
)

var (
    EXTERNAL_USER_ID   = "<your_external_user_id>"
    QUERY              = "<your_query>"
    RESPONSE_MODE      = "" // Now dynamic
    AGENT_IDS          = []string{} // Dynamic array from AgentIds
    FILE_AGENT_IDS          = []string{} // Dynamic array from AgentIds
    ENDPOINT_ID        = "predefined-openai-gpt4.1"
    REASONING_MODE     = "grok-4-fast"
    FULFILLMENT_PROMPT = ""
    STOP_SEQUENCES     = []string{} // Dynamic array
    TEMPERATURE        = 0.7
    TOP_P              = 1
    MAX_TOKENS         = 0
    PRESENCE_PENALTY   = 0
    FREQUENCY_PENALTY  = 0

    MEDIA_FILE_PATH = "" // "" if no file else "/Users/Documents/file.pdf" your file path
)

type ContextField struct {
    Key   string `json:"key"`
    Value string `json:"value"`
}

type SessionData struct {
    ID              string         `json:"id"`
    ContextMetadata []ContextField `json:"contextMetadata"`
}

type CreateSessionResponse struct {
    Data SessionData `json:"data"`
}

func main() {
    if API_KEY == "<your_api_key>" || API_KEY == "" {
        fmt.Println("❌ Please set API_KEY.")
        os.Exit(1)
    }
    if EXTERNAL_USER_ID == "<your_external_user_id>" || EXTERNAL_USER_ID == "" {
        EXTERNAL_USER_ID = uuid.NewString()
        fmt.Printf("⚠️  Generated EXTERNAL_USER_ID: %s\n", EXTERNAL_USER_ID)
    }

    contextMetadata := []map[string]string{
        {"key": "userId", "value": "1"},
        {"key": "name", "value": "John"},
    }

    sessionID := createChatSession()
    if sessionID != "" {
        fmt.Println("\n--- Submitting Query ---")
        fmt.Printf("Using query: '%s'\n", QUERY)
        fmt.Printf("Using responseMode: '%s'\n", RESPONSE_MODE)

    // include if you u want to add a file in the chat session
    if MEDIA_FILE_PATH != "" {
        uploadMedia(MEDIA_FILE_PATH, sessionID)
    }
        submitQuery(sessionID, contextMetadata, mediaID)
    }
}

// 🔹 Upload media file and return mediaId
func uploadMedia(filePath, sessionID string) string {
    url := BASE_URL + "/media/v1/public/file/raw"

    body := &bytes.Buffer{}
    writer := multipart.NewWriter(body)

    file, err := os.Open(filePath)
    if err != nil {
        fmt.Printf("❌ Failed to open file: %v\n", err)
        return ""
    }
    defer file.Close()

    part, _ := writer.CreateFormFile("file", filepath.Base(filePath))
    io.Copy(part, file)

    writer.WriteField("createdBy", sessionID)
    writer.WriteField("createdBy", "AIREV")
    writer.WriteField("updatedBy", "AIREV")
    writer.WriteField("name", filepath.Base(filePath))
    writer.WriteField("responseMode", "sync")
    for _, agent := range FILE_AGENT_IDS {
        writer.WriteField("agents", agent)
    }

    writer.Close()

    req, _ := http.NewRequest("POST", url, body)
    req.Header.Set("apikey", API_KEY)
    req.Header.Set("Content-Type", writer.FormDataContentType())

    resp, err := http.DefaultClient.Do(req)
    if err != nil {
        fmt.Printf("❌ Upload failed: %v\n", err)
        return ""
    }
    defer resp.Body.Close()

    respBody, _ := io.ReadAll(resp.Body)
    if resp.StatusCode == 200 || resp.StatusCode == 201 {
        var jsonResp map[string]interface{}
        json.Unmarshal(respBody, &jsonResp)
        if data, ok := jsonResp["data"].(map[string]interface{}); ok {
            id := data["id"].(string)
            fmt.Printf("✅ Media uploaded. Media ID: %s\n", id)
            return id
        }
    }
    fmt.Printf("❌ Upload error: %s\n", string(respBody))
    return ""
}

func createChatSession() string {
    url := BASE_URL + "/chat/v1/sessions"

    contextMetadata := []map[string]string{
        {"key": "userId", "value": "1"},
        {"key": "name", "value": "John"},
    }

    body := map[string]interface{}{
        "agentIds":        AGENT_IDS,
        "externalUserId":  EXTERNAL_USER_ID,
        "contextMetadata": contextMetadata,
    }

    jsonBody, _ := json.Marshal(body)

    fmt.Printf("📡 Creating session with URL: %s\n", url)
    fmt.Printf("📝 Request body: %s\n", string(jsonBody))

    req, _ := http.NewRequest("POST", url, bytes.NewBuffer(jsonBody))
    req.Header.Set("apikey", API_KEY)
    req.Header.Set("Content-Type", "application/json")

    client := &http.Client{}
    resp, err := client.Do(req)
    if err != nil {
        fmt.Printf("❌ Request failed during session creation: %v\n", err)
        return ""
    }
    defer resp.Body.Close()
    respBody, _ := ioutil.ReadAll(resp.Body)

    if resp.StatusCode == 201 {
        var sessionResp CreateSessionResponse
        json.Unmarshal(respBody, &sessionResp)

        fmt.Printf("✅ Chat session created. Session ID: %s\n", sessionResp.Data.ID)

        if len(sessionResp.Data.ContextMetadata) > 0 {
            fmt.Println("📋 Context Metadata:")
            for _, field := range sessionResp.Data.ContextMetadata {
                fmt.Printf(" - %s: %s\n", field.Key, field.Value)
            }
        }

        return sessionResp.Data.ID
    }

    fmt.Printf("❌ Error creating chat session: %d - %s\n", resp.StatusCode, string(respBody))
    return ""
}

func submitQuery(sessionID string, contextMetadata []map[string]string) {
    url := fmt.Sprintf("%s/chat/v1/sessions/%s/query", BASE_URL, sessionID)
    body := map[string]interface{}{
        "endpointId":    ENDPOINT_ID,
        "query":         QUERY,
        "agentIds":      AGENT_IDS,
        "responseMode":  RESPONSE_MODE,
        "reasoningMode": REASONING_MODE,
        "modelConfigs": map[string]interface{}{
            "fulfillmentPrompt": FULFILLMENT_PROMPT,
            "stopSequences":     STOP_SEQUENCES,
            "temperature":       TEMPERATURE,
            "topP":              TOP_P,
            "maxTokens":         MAX_TOKENS,
            "presencePenalty":   PRESENCE_PENALTY,
            "frequencyPenalty":  FREQUENCY_PENALTY,
        },
    }

    jsonBody, _ := json.Marshal(body)

    fmt.Printf("🚀 Submitting query to URL: %s\n", url)
    fmt.Printf("📝 Request body: %s\n", string(jsonBody))

    req, _ := http.NewRequest("POST", url, bytes.NewBuffer(jsonBody))
    req.Header.Set("apikey", API_KEY)
    req.Header.Set("Content-Type", "application/json")

    client := &http.Client{}
    resp, err := client.Do(req)
    if err != nil {
        fmt.Printf("❌ Request failed during query submission: %v\n", err)
        return
    }
    defer resp.Body.Close()

    if RESPONSE_MODE == "sync" {
        respBody, _ := ioutil.ReadAll(resp.Body)
        if resp.StatusCode == 200 {
            var original map[string]interface{}
            json.Unmarshal(respBody, &original)

            // Append context metadata at the end
            if dataMap, ok := original["data"].(map[string]interface{}); ok {
                dataMap["contextMetadata"] = contextMetadata
            }

            final, _ := json.MarshalIndent(original, "", "  ")
            fmt.Println("✅ Final Response (with contextMetadata appended):")
            fmt.Println(string(final))
        } else {
            fmt.Printf("❌ Error submitting sync query: %d - %s\n", resp.StatusCode, string(respBody))
        }
    } else if RESPONSE_MODE == "stream" {
        fmt.Println("✅ Streaming Response...")

        scanner := bufio.NewScanner(resp.Body)

        var fullAnswer strings.Builder
        var finalSessionID, finalMessageID string
        var metrics map[string]interface{}

        for scanner.Scan() {
            line := scanner.Text()

            if strings.HasPrefix(line, "data:") {
                dataStr := strings.TrimPrefix(line, "data:")
                dataStr = strings.TrimSpace(dataStr)

                if dataStr == "[DONE]" {
                    break
                }

                var event map[string]interface{}
                if err := json.Unmarshal([]byte(dataStr), &event); err != nil {
                    continue
                }

                if event["eventType"] == "fulfillment" {
                    if text, ok := event["answer"].(string); ok {
                        fullAnswer.WriteString(text)
                    }
                    if val, ok := event["sessionId"].(string); ok {
                        finalSessionID = val
                    }
                    if val, ok := event["messageId"].(string); ok {
                        finalMessageID = val
                    }
                }

                if event["eventType"] == "metricsLog" {
                    if m, ok := event["publicMetrics"].(map[string]interface{}); ok {
                        metrics = m
                    }
                }
            }
        }

        if err := scanner.Err(); err != nil {
            fmt.Printf("❌ Error reading stream: %v\n", err)
            return
        }

        finalResponse := map[string]interface{}{
            "message": "Chat query submitted successfully",
            "data": map[string]interface{}{
                "sessionId":       finalSessionID,
                "messageId":       finalMessageID,
                "answer":          fullAnswer.String(),
                "metrics":         metrics,
                "status":          "completed",
                "contextMetadata": contextMetadata,
            },
        }

        formatted, _ := json.MarshalIndent(finalResponse, "", "  ")
        fmt.Println("\n✅ Final Response (with contextMetadata appended):")
        fmt.Println(string(formatted))
    }
}
