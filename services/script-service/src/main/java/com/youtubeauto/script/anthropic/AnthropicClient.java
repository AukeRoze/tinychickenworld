package com.youtubeauto.script.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.script.config.AnthropicProperties;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Thin Anthropic Messages API client. Claude has no equivalent of OpenAI's
 * response_format=json_schema, so we use forced tool_use:
 *
 *   - Define one tool whose input_schema is the JSON shape we want.
 *   - Set tool_choice={type:tool, name:...} to force Claude to call it.
 *   - The tool_use content block's "input" IS the structured JSON.
 *
 * That gives us the same "guaranteed shape" guarantee as OpenAI structured
 * outputs without bolt-on JSON parsing of free-form text.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicClient {

    private final WebClient anthropicWebClient;
    private final AnthropicProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public record ChatMessage(String role, String content) {}
    public record CompletionResult(String contentJson, Integer inputTokens, Integer outputTokens) {}

    @Retry(name = "anthropic")
    public CompletionResult callTool(String systemPrompt,
                                     List<ChatMessage> messages,
                                     String toolName,
                                     String toolDescription,
                                     JsonNode toolInputSchema) {
        return callTool(systemPrompt, messages, toolName, toolDescription,
                toolInputSchema, null, null);
    }

    /**
     * Same as {@link #callTool}, but lets a caller override the model and
     * temperature for a single call — used by the optional dialogue punch-up
     * pass, which runs a stronger (pricier) model than the cheap default
     * script model. Null overrides fall back to the configured defaults.
     */
    @Retry(name = "anthropic")
    public CompletionResult callTool(String systemPrompt,
                                     List<ChatMessage> messages,
                                     String toolName,
                                     String toolDescription,
                                     JsonNode toolInputSchema,
                                     String modelOverride,
                                     Double temperatureOverride) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : props.model());
        body.put("max_tokens", props.maxTokens());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("temperature", temperatureOverride != null
                ? temperatureOverride : props.temperature());

        ArrayNode msgArr = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode n = msgArr.addObject();
            n.put("role", m.role());
            n.put("content", m.content());
        }

        ArrayNode tools = body.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", toolName);
        tool.put("description", toolDescription);
        tool.set("input_schema", toolInputSchema);

        ObjectNode toolChoice = body.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", toolName);

        JsonNode response = anthropicWebClient.post()
                .uri("/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null) throw new IllegalStateException("Empty Anthropic response");

        JsonNode toolUse = null;
        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) {
                toolUse = block;
                break;
            }
        }
        if (toolUse == null) {
            throw new IllegalStateException("Anthropic response did not include tool_use block: " + response);
        }

        String contentJson = toolUse.path("input").toString();
        Integer inTok = response.path("usage").path("input_tokens").isMissingNode()
                ? null : response.path("usage").path("input_tokens").asInt();
        Integer outTok = response.path("usage").path("output_tokens").isMissingNode()
                ? null : response.path("usage").path("output_tokens").asInt();

        log.debug("Anthropic usage input={} output={} stop_reason={}",
                inTok, outTok, response.path("stop_reason").asText());
        return new CompletionResult(contentJson, inTok, outTok);
    }
}
