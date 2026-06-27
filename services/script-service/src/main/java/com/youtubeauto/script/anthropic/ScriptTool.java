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
                      "visualDesc":      { "type": "string",
                                           "description": "STILL description of the shot. Stage every FRAMED cast member: name each one and give them a clear action or position — in a wide or medium shot no character listed in 'characters' should stand around unmentioned (that makes the video model press a motionless, morphing body into the frame). State the shot type explicitly (e.g. 'wide establishing shot', 'extreme close-up of the wings and the egg', 'over-the-shoulder past Pip'); close-up / insert wording lets the compiler relax the cast lock so unframed members are not crammed in. PROP CONTINUITY: track the story's key prop / recurring motif (e.g. the egg). In every scene where it is logically present, either show it or say where it rests (e.g. 'the pale-cream egg sits safely on a soft cloth nearby'); when the characters change location the prop moves WITH them and you say so — never silently drop it, leave it behind, or let it reappear in a different spot next scene. Do NOT mention the prop in scenes where it is genuinely absent (no ghost copies). PROP STATE: if the prop changes state at a beat (the egg gets a crack, then hatches), the change is PERMANENT and one-way — from that scene onward describe the CURRENT state and never re-assert a superseded one (do not keep ordering 'CRITICAL: perfectly smooth and uncracked' once it has cracked). Only hard-lock a state with 'CRITICAL: …' in the scenes where that state actually holds, and at the change beat describe the change happening DYNAMICALLY at the trigger moment, not just the end state (e.g. 'at the exact moment Pip's wing taps it, the perfectly smooth shell visibly changes to reveal exactly ONE thin hairline crack spreading from the point of contact, no other damage') — so the model animates the change instead of clinging to the earlier 'uncracked' lock. DO NOT re-specify locked attributes the compiler already injects: never describe the characters' relative SIZES or proportions here (the compiler adds the canonical 'Relative size' rule — Pip smallest, Mo larger, Bo taller/slimmer), and in particular never write that the chicks are 'the same size as each other' — that directly contradicts the size canon and causes scale-flicker. Describe action, composition and shot type, not the identity/size locks. NO ENVIRONMENT/STYLE RESTATEMENT (token economy — Auke 2026-06-17): do NOT restate the setting, location, time of day, sunlight/lighting, weather, colour mood or art style in visualDesc. The compiler already prints ALL of these ONCE at the top of the prompt (the 'DIRECTOR'S BRIEF & ENVIRONMENT' block, from locationId / timeOfDay / weather / phase) — repeating the full scene description again in the action line (as scene 20 did) just burns prompt tokens and pushes the model's attention off the action. Keep visualDesc to the characters' staging plus ONE short shot-type tag (e.g. 'Wide shot:' or 'Close-up:'); the prop/egg-continuity notes above still belong here. START FRAME: the visualDesc is rendered as the clip's FIRST frame, so describe the composition at second 0 — never the end state. If the shot moves, frame the OPENING: a pull-back / reveal starts TIGHT (e.g. close on the egg) and the motion widens to show the rest, and a fall/slide starts at the moment of losing balance — do NOT describe the wide end-frame or the after-the-fall pose as the still if you want the move itself to play. ONE primary action per beat: never stack several simultaneous moves on one character in a 4-5s shot (e.g. tip the hat AND wink AND talk at once) — pick the key beat, or sequence it ('first Pip winks, then she tips her hat'). NEVER stage disembodied limbs: if wings or feet act, the body and face that own them must be visible in the frame — no 'three pairs of wings' in a tight close-up with no bodies, which mutates into limb-creatures. ANATOMY: the chicks have WINGS and a round fluffy body, never human limbs — no knees, hands, fingers, thumbs, elbows or arms (a chick taps its fluffy lap/belly with a wingtip, waves or gestures with a wing, never a 'thumbs-up'). And never OCCLUDE a character's signature identity features: do not pile objects on the head (it hides the cowlick/comb), let a hat brim cover the eyes, or cover the glasses, and do not put a prop in the beak of a character who has a line (it blocks the talking motion and morphs the beak) — have them hold props under a wing instead. ACCESSORY GESTURES: describe any hat/glasses/scarf gesture as a light TOUCH or nudge with a wingtip (e.g. 'touches the brim of her straw hat with a wingtip', 'pushes her glasses up the beak', 'tugs her scarf snug') — NEVER 'tip / lift / raise / take off / remove / doff' the hat or put it 'in her wing' or 'on the ground': removal-implying verbs make the model lift the accessory off, breaking the never-off identity lock the compiler injects. ACCESSORY OWNERSHIP (CRITICAL — must match each character's DNA): a character may only ever touch / adjust / wear ITS OWN signature accessory. Pip has the straw farmer hat (and red bandana), Mo has the thick red knitted scarf, Bo has the round eyeglasses (and green scarf), and the duckling has NONE. NEVER write a character interacting with an accessory it does not own — e.g. NEVER 'Mo adjusts his glasses' or 'Pip pushes up her glasses' (glasses are Bo's) — because the prompt also hard-locks 'Mo must NEVER wear glasses', and the image model, having no timeline, then morphs a stray pair of glasses onto Mo or copies Bo's whole look onto him. If you want Mo to fiddle with an accessory, it is his scarf; for Pip it is her hat; for Bo her glasses. When unsure, give the character a neutral beat (a head tilt, a wing reaching toward the prop) instead of an accessory it does not own." },
                      "locationId":      { "type": "string",
                                           "description": "Bible location id for this scene. SINGLE-LOCATION RULE: the ENTIRE episode plays in ONE location — choose the single location that best fits this topic and set that SAME locationId on EVERY scene. Never switch settings between scenes (no garden -> coop -> pond hopping); switching places mid-episode confuses young viewers and the validator will reject it. Get visual variety from FRAMING and TIME-OF-DAY within that one place, never from changing the place." },
                      "phase":           { "type": "string",
                                           "description": "Episode-structure phase id this scene belongs to (one of: hook, setup, humor, development, emotion, climax, resolution, closer). The KEY BEATS: 'humor' = exactly ONE ~10s dedicated funny scene; 'emotion' = exactly ONE ~10s dedicated tender scene; 'climax' = the KEY MOMENT / reveal / payoff (e.g. the egg hatching). setup/development/resolution are supporting scenes around those beats. Drives quality/Veo routing downstream." },
                      "timeOfDay":       { "type": "string",
                                           "description": "Time-of-day mood id from the bible timeOfDay list (e.g. goldenHour, midday, dusk, night). Rotate across scenes for variety; keep consistent within a single beat. This field DRIVES the light in the compiled prompt, so it MUST match the time you describe in visualDesc — never write a dusk/evening/night scene (twilight, deep evening sky, moonlight) in visualDesc while leaving timeOfDay on a daytime value like midday or goldenHour, or the prompt orders bright midday sunlight over a dusk action (a contradiction the video model renders as flicker or a wrongly-bright shot)." },
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
                                           "description": "MOTION brief: the start->end MOVEMENT for AI video (camera move + what the character physically DOES from start to finish + ambient motion), e.g. 'Camera pushes slowly in as Pip's eyes widen and she leans toward the pebble, one wing reaching out; petals drift past'. REQUIRED on hero (hook/climax) beats, and STRONGLY recommended on EVERY scene of a fully-animated episode: without it the animated 'Action' falls back to the STILL visualDesc and the shot just sits there with no directed movement (a frozen or nervously-twitching clip). Give every animated beat at least one clear verb-driven movement line synced to the dialogue. Only leave empty for a still Ken-Burns pan. visualDesc stays the STILL composition; this drives the motion. Keep ONE camera intention per beat: the camera move here must match the shot in visualDesc and must not contradict it — do NOT write 'pull back to reveal' for a beat framed as a tight push-in, and do NOT copy the same push-in onto a wide reveal." },
                      "propStates": {
                        "type": "object",
                        "additionalProperties": { "type": "string" },
                        "description": "Optional: the CURRENT state of each hero prop in THIS scene, as { propId: stateId }, e.g. { \\"egg\\": \\"cracked\\" }. Only the story's key object (the egg) is a hero prop. Use the bible's state ids in order — for the egg: intact -> hairline -> cracked -> hatched. ONE-WAY / MONOTONE: once the egg reaches a state it NEVER goes back to an earlier one in a later scene; set the SAME or a LATER state each subsequent scene, never an earlier one. Only set a prop here in scenes where that prop is actually present (the same scenes you mention it in visualDesc); omit it (or omit the whole object) when the prop is absent. You do NOT describe the prop's look here — the compiler injects the canonical appearance and anti-drift automatically; this field only picks which state it is in. At the change beat, set the NEW state here AND describe the change happening dynamically in visualDesc/motionDesc." },
                      "durationSeconds": { "type": "integer", "minimum": 2, "maximum": 60 },
                      "characters": {
                        "type": "array",
                        "description": "Character ids that are part of THIS scene's cast (used for cast-continuity so a sidekick doesn't pop out for a single beat). HARD LIMIT: max 2 for normal scenes, max 3 ONLY for hook/climax/closer beats — fewer characters per shot = better visual consistency, so prefer solo shots and two-shots and bring others in via their own scenes. Frame the listed characters in visualDesc (give each one something to DO). It is fine to leave a listed member off-frame for an insert, over-the-shoulder or viewer-participation beat — the compiler relaxes the lock for unframed members — but do NOT list a character who has no line AND no part in the beat just to keep the count up: either give them a role or drop them from this scene.",
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
                            "text":    { "type": "string",
                                         "description": "The spoken line, ALWAYS in clear natural English — never Dutch or any other language, and never mixed. It is read aloud and lip-synced downstream. NOT A SOUND EFFECT: a 'line' is something a character SAYS with its voice. NEVER put a physical-impact onomatopoeia here as if spoken — e.g. do NOT write a line {speaker: bo, text: 'Bonk!'} for the sound of Bo plopping onto the egg. That collision sound is FOLEY, not speech: coded as a line the model makes the chick lip-sync the word 'bonk' (a morphing beak), it burns a speaker turn that overstuffs the 10-second beat, and it prints 'Bonk!' in the subtitles. Instead let the character's real line simply end (e.g. Bo says '…like a real hen!') and describe the physical impact in visualDesc/motionDesc ('Bo plops down with a soft bonk'); the impact sound is added on the AUDIO sound-effects layer automatically. Exclamations a chick genuinely vocalises ('Wheee!', 'Whoosh!', 'Uh-oh!') are fine as spoken lines — only true collision/impact foley (bonk, plop, thud, thump, splat, clunk) must not be a spoken line." }
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
