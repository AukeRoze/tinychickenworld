package com.youtubeauto.script.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.script.anthropic.AnthropicClient;
import com.youtubeauto.script.anthropic.AnthropicClient.CompletionResult;
import com.youtubeauto.script.anthropic.GeneratedScript;
import com.youtubeauto.script.anthropic.ScriptTool;
import com.youtubeauto.script.api.dto.GenerateScriptRequest;
import com.youtubeauto.script.config.AnthropicProperties;
import com.youtubeauto.script.dedupe.VariationProfile;
import com.youtubeauto.script.service.PromptBuilder.BuiltPrompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Pure-function service: request + profile in, GeneratedScript + token usage out. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptGenerator {

    private final AnthropicClient anthropic;
    private final PromptBuilder prompts;
    private final AnthropicProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Result(GeneratedScript script, String rawJson, String model,
                         Integer promptTokens, Integer completionTokens,
                         VariationProfile profile, String storyArc) {}

    public Result generate(GenerateScriptRequest req, VariationProfile profile) {
        return generate(req, profile, null, null);
    }

    public Result generate(GenerateScriptRequest req, VariationProfile profile,
                           String structureFeedback) {
        return generate(req, profile, structureFeedback, null);
    }

    public Result generate(GenerateScriptRequest req, VariationProfile profile,
                           String structureFeedback, String criticFeedback) {
        BuiltPrompt bp = prompts.build(req, profile, structureFeedback, criticFeedback);
        CompletionResult c = anthropic.callTool(
                bp.systemPrompt(),
                bp.messages(),
                ScriptTool.NAME,
                ScriptTool.DESCRIPTION,
                ScriptTool.schema(mapper)
        );
        try {
            GeneratedScript s = mapper.readValue(c.contentJson(), GeneratedScript.class);
            validate(s, req);
            return new Result(s, c.contentJson(), props.model(),
                    c.inputTokens(), c.outputTokens(), profile, bp.arcId());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse script JSON: " + e.getMessage(), e);
        }
    }

    private void validate(GeneratedScript s, GenerateScriptRequest req) {
        int total = s.scenes().stream().mapToInt(GeneratedScript.Scene::durationSeconds).sum();
        double drift = Math.abs(total - req.targetSeconds()) / (double) req.targetSeconds();
        if (drift > 0.25) {
            log.warn("Script duration drift {}s vs target {}s ({}%)",
                    total, req.targetSeconds(), Math.round(drift * 100));
        }
        for (int i = 0; i < s.scenes().size(); i++) {
            if (s.scenes().get(i).seq() != i + 1) {
                throw new IllegalStateException("Scene seq out of order at index " + i);
            }
        }
    }
}
