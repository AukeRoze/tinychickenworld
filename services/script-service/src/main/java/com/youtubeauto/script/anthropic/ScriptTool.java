package com.youtubeauto.script.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON schema for the structured script Claude emits via forced tool_use.
 * Mirrors {@link GeneratedScript}; change them together.
 */
public final class ScriptTool {

    public static final String NAME = "emit_script";
    public static final String DESCRIPTION =
            "Emit the finished video script as a structured object. " +
            "Every scene's lines must use only the character ids listed in the cast. " +
            "Always use this tool. Do not respond with free-form text.";

    private static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["title", "hook", "cta", "scenes"],
              "properties": {
                "title": { "type": "string" },
                "hook":  { "type": "string" },
                "cta":   { "type": "string" },
                "scenes": {
                  "type": "array",
                  "minItems": 3,
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["seq", "lines", "visualDesc", "characters",
                                 "locationId", "durationSeconds", "emotion"],
                    "properties": {
                      "seq":             { "type": "integer", "minimum": 1 },
                      "visualDesc":      { "type": "string" },
                      "locationId":      { "type": "string" },
                      "phase":           { "type": "string",
                                           "description": "Episode-structure phase id this scene belongs to (e.g. hook, setup, development, climax, resolution, closer). Drives quality/Veo routing downstream." },
                      "timeOfDay":       { "type": "string",
                                           "description": "Time-of-day mood id from the bible timeOfDay list (e.g. goldenHour, midday, dusk, night). Rotate across scenes for variety; keep consistent within a single beat." },
                      "weather":         { "type": "string",
                                           "description": "Optional weather mood id from the bible weather list (e.g. clear, lightRain, breezy, snow). Usually consistent for the whole video." },
                      "goal":            { "type": "string",
                                           "description": "The shot's purpose in one short phrase — what happens / what the viewer should grasp (e.g. 'Pip discovers the shiny pebble')." },
                      "emotion":         { "type": "string",
                                           "description": "Primary emotion of the main character in this shot, with intensity 1-5 (e.g. 'wonder (5/5)', 'gentle joy (3/5)'). Drives the performance." },
                      "motionSpeed":     { "type": "string",
                                           "description": "Pace of the action: slow | natural | quick. Most preschool beats are slow/natural." },
                      "endPose":         { "type": "string",
                                           "description": "Optional: the character's pose/state at the END of the shot (e.g. 'leaning in, beak open in a gasp'). Used to generate a last-frame for directed Veo motion. Set this for hook/climax (hero) shots." },
                      "motionDesc":      { "type": "string",
                                           "description": "Optional MOTION brief for HOOK and CLIMAX (hero) scenes only — describe the start->end MOVEMENT for AI video (camera move + what the character physically does from start to finish + ambient motion), e.g. 'Camera pushes slowly in as Pip's eyes widen and she leans toward the pebble, one wing reaching out; petals drift past'. Leave EMPTY for non-hero scenes. visualDesc stays a STILL description; this drives Veo." },
                      "durationSeconds": { "type": "integer", "minimum": 2, "maximum": 60 },
                      "characters": {
                        "type": "array",
                        "description": "Character ids physically IN FRAME this scene. HARD LIMIT: max 2 for normal scenes, max 3 ONLY for hook/climax/closer beats. Fewer characters per shot = better visual consistency — prefer solo shots and two-shots; bring the others in via their own scenes.",
                        "minItems": 1,
                        "maxItems": 3,
                        "items": { "type": "string" }
                      },
                      "lines": {
                        "type": "array",
                        "description": "Spoken lines. MAY BE EMPTY ([]) for exactly ONE silent visual beat per script — a scene that acts purely in image (see SILENT VISUAL BEAT rules). All other scenes need dialogue.",
                        "minItems": 0,
                        "items": {
                          "type": "object",
                          "additionalProperties": false,
                          "required": ["speaker", "text"],
                          "properties": {
                            "speaker": { "type": "string" },
                            "text":    { "type": "string" }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    private ScriptTool() {}

    public static JsonNode schema(ObjectMapper mapper) {
        try { return mapper.readTree(SCHEMA); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
