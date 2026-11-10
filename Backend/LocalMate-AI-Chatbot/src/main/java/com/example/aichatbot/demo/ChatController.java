package com.example.aichatbot.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "http://localhost:4200")
public class ChatController {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String ollamaBaseUrl;
    private final String model;

    public ChatController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                          @Value("${ollama.base-url}") String ollamaBaseUrl,
                          @Value("${ollama.model}") String model) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.model = model;
    }

    @GetMapping("/history")
    public List<SavedChat> history(@RequestParam String clientId) {
        return jdbcTemplate.query("SELECT chat_id, title, messages, updated_at FROM chat_history WHERE client_id = ? ORDER BY updated_at DESC",
                (resultSet, rowNum) -> new SavedChat(resultSet.getString("chat_id"), resultSet.getString("title"),
                        readMessages(resultSet.getString("messages")), resultSet.getTimestamp("updated_at").getTime()), clientId);
    }

    @PostMapping(produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) return ResponseEntity.badRequest().build();

        String systemPrompt = jdbcTemplate.queryForObject(
                "SELECT setting_value FROM app_settings WHERE setting_key = 'system_prompt'", String.class);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt + "\n\nFormat every response using clean Markdown. Use headings, lists, and fenced code blocks when they improve clarity."));
        if (request.history() != null) request.history().stream()
                .filter(item -> item != null && ("user".equals(item.role()) || "assistant".equals(item.role()))
                        && item.content() != null && !item.content().isBlank())
                .forEach(item -> messages.add(Map.of("role", item.role(), "content", item.content())));
        messages.add(Map.of("role", "user", "content", request.message().trim()));

        List<ChatMessage> savedMessages = new ArrayList<>();
        if (request.history() != null) savedMessages.addAll(request.history().stream()
                .filter(item -> item != null && ("user".equals(item.role()) || "assistant".equals(item.role()))
                        && item.content() != null && !item.content().isBlank()).toList());
        savedMessages.add(new ChatMessage("user", request.message().trim()));
        saveChat(request, savedMessages);

        StreamingResponseBody body = output -> {
            StringBuilder assistantResponse = new StringBuilder();
            try {
                String payload = objectMapper.writeValueAsString(Map.of("model", model, "stream", true, "messages", messages));
                HttpRequest ollamaRequest = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/chat"))
                        .timeout(Duration.ofMinutes(10)).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                HttpResponse<java.io.InputStream> response = httpClient.send(ollamaRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 400) throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        JsonNode chunk = objectMapper.readTree(line);
                        String delta = chunk.path("message").path("content").asText("");
                        assistantResponse.append(delta);
                        output.write(objectMapper.writeValueAsBytes(Map.of("delta", delta, "done", chunk.path("done").asBoolean())));
                        output.write('\n');
                        output.flush();
                    }
                }
            } catch (Exception exception) {
                output.write(objectMapper.writeValueAsBytes(Map.of("error", "Unable to get a response from Ollama.")));
                output.write('\n'); output.flush();
            } finally {
                // Persist completed or partial text so a refresh/interruption never drops a long answer.
                if (assistantResponse.length() > 0) {
                    savedMessages.add(new ChatMessage("assistant", assistantResponse.toString()));
                    saveChat(request, savedMessages);
                }
            }
        };
        return ResponseEntity.ok().contentType(MediaType.valueOf("application/x-ndjson")).body(body);
    }

    private void saveChat(ChatRequest request, List<ChatMessage> messages) {
        if (request.chatId() == null || request.clientId() == null || request.title() == null) return;
        try {
            jdbcTemplate.update("INSERT INTO chat_history (chat_id, client_id, title, messages) VALUES (?, ?, ?, CAST(? AS JSON)) " +
                            "ON DUPLICATE KEY UPDATE title = VALUES(title), messages = VALUES(messages), updated_at = CURRENT_TIMESTAMP",
                    request.chatId(), request.clientId(), request.title(), objectMapper.writeValueAsString(messages));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not save chat history", exception);
        }
    }

    private List<ChatMessage> readMessages(String json) {
        try { return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, ChatMessage.class)); }
        catch (Exception exception) { return List.of(); }
    }

    public record ChatRequest(String message, List<ChatMessage> history, String chatId, String clientId, String title) { }
    public record ChatMessage(String role, String content) { }
    public record SavedChat(String id, String title, List<ChatMessage> messages, long updatedAt) { }
}
