package com.youtubeauto.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed view of ONE assembly scene — the unit persisted (as a JSON array) in
 * {@code VideoJob.assemblyScenesJson} and threaded through the whole pipeline
 * (script → voice/image merge → Veo → assembly).
 *
 * <p><b>Persisted format is sacred.</b> This class round-trips the exact JSON
 * the legacy {@code Map<String, Object>} representation produced:
 * <ul>
 *   <li>every known key is a typed field with the same JSON name and the same
 *       value type (seq as int, durationSeconds as a plain JSON number that may
 *       be an integer or a double — hence {@link Number});</li>
 *   <li>any key this class does not know (older jobs, other writers) is kept
 *       verbatim in the {@link #extras} map via
 *       {@code @JsonAnySetter}/{@code @JsonAnyGetter};</li>
 *   <li>absent keys stay absent on write ({@code @JsonInclude(NON_NULL)}), so a
 *       scene that never had e.g. {@code clipPath} doesn't suddenly grow a null
 *       entry — and clearing a field (set to null) removes the key, mirroring
 *       the old {@code map.remove(...)}.</li>
 * </ul>
 *
 * <p>HTTP payloads stay {@code Map<String, Object>} at the service-client
 * boundary; use {@link #toMap()}/{@link #toMapList(List)} at that seam.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SceneDto {

    /** Shared converter for the Map seams. Plain default ObjectMapper — the
     *  same configuration PipelineOrchestrator always (de)serialised with. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Integer seq;
    /** Scripted scene length. Usually an Integer, but kept as {@link Number}
     *  because persisted jobs may carry a Double (e.g. ffprobe-derived
     *  stretch values) — the exact numeric type must round-trip. */
    private Number durationSeconds;
    private String phase;
    private String narration;
    private String visualDesc;
    private String imagePath;
    private String audioPath;
    private String clipPath;
    private String endImagePath;
    private List<String> characters;
    private String locationId;
    private String timeOfDay;
    private String weather;
    private String emotion;
    private String goal;
    private String motionSpeed;
    private String endPose;
    private String motionDesc;
    private String cameraFraming;
    /** Per-character dialogue lines: {speaker, text[, emotion]}. */
    private List<Map<String, Object>> lines;
    /** Per-line voice timing: {startMs, durMs, ...} (numbers). */
    private List<Map<String, Object>> lineTimings;
    @JsonProperty("locked")
    private Boolean locked;

    /** Round-trip bucket for every key this class doesn't model. */
    private final Map<String, Object> extras = new LinkedHashMap<>();

    // ── extras (unknown-key round-trip) ──────────────────────────────────

    @JsonAnyGetter
    public Map<String, Object> extras() {
        return extras;
    }

    @JsonAnySetter
    public void putExtra(String key, Object value) {
        extras.put(key, value);
    }

    // ── typed convenience accessors ──────────────────────────────────────

    /** Primitive seq. Throws NPE when seq is absent — identical to the legacy
     *  {@code ((Number) map.get("seq")).intValue()} behaviour. */
    @JsonIgnore
    public int effectiveSeq() {
        return seq;
    }

    @JsonIgnore
    public boolean hasImage() {
        return imagePath != null && !imagePath.isBlank();
    }

    @JsonIgnore
    public boolean hasClip() {
        return clipPath != null && !clipPath.isBlank();
    }

    @JsonIgnore
    public boolean isLocked() {
        return Boolean.TRUE.equals(locked);
    }

    /** Characters, never null — legacy code defaulted a missing list to {@code List.of()}. */
    @JsonIgnore
    public List<String> charactersOrEmpty() {
        return characters == null ? List.of() : characters;
    }

    // ── Map seams ────────────────────────────────────────────────────────

    /** This scene as the exact {@code Map<String, Object>} the legacy code
     *  carried (same keys, same value types; absent fields stay absent). */
    public Map<String, Object> toMap() {
        return MAPPER.convertValue(this, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    public static SceneDto fromMap(Map<String, Object> map) {
        return MAPPER.convertValue(map, SceneDto.class);
    }

    public static List<Map<String, Object>> toMapList(List<SceneDto> scenes) {
        List<Map<String, Object>> out = new ArrayList<>(scenes.size());
        for (SceneDto s : scenes) out.add(s.toMap());
        return out;
    }

    public static List<SceneDto> fromMapList(List<Map<String, Object>> maps) {
        List<SceneDto> out = new ArrayList<>(maps.size());
        for (Map<String, Object> m : maps) out.add(fromMap(m));
        return out;
    }

    // ── getters / setters (Jackson bean access) ──────────────────────────

    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }

    public Number getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Number durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }

    public String getVisualDesc() { return visualDesc; }
    public void setVisualDesc(String visualDesc) { this.visualDesc = visualDesc; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    public String getAudioPath() { return audioPath; }
    public void setAudioPath(String audioPath) { this.audioPath = audioPath; }

    public String getClipPath() { return clipPath; }
    public void setClipPath(String clipPath) { this.clipPath = clipPath; }

    public String getEndImagePath() { return endImagePath; }
    public void setEndImagePath(String endImagePath) { this.endImagePath = endImagePath; }

    public List<String> getCharacters() { return characters; }
    public void setCharacters(List<String> characters) { this.characters = characters; }

    public String getLocationId() { return locationId; }
    public void setLocationId(String locationId) { this.locationId = locationId; }

    public String getTimeOfDay() { return timeOfDay; }
    public void setTimeOfDay(String timeOfDay) { this.timeOfDay = timeOfDay; }

    public String getWeather() { return weather; }
    public void setWeather(String weather) { this.weather = weather; }

    public String getEmotion() { return emotion; }
    public void setEmotion(String emotion) { this.emotion = emotion; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public String getMotionSpeed() { return motionSpeed; }
    public void setMotionSpeed(String motionSpeed) { this.motionSpeed = motionSpeed; }

    public String getEndPose() { return endPose; }
    public void setEndPose(String endPose) { this.endPose = endPose; }

    public String getMotionDesc() { return motionDesc; }
    public void setMotionDesc(String motionDesc) { this.motionDesc = motionDesc; }

    public String getCameraFraming() { return cameraFraming; }
    public void setCameraFraming(String cameraFraming) { this.cameraFraming = cameraFraming; }

    public List<Map<String, Object>> getLines() { return lines; }
    public void setLines(List<Map<String, Object>> lines) { this.lines = lines; }

    public List<Map<String, Object>> getLineTimings() { return lineTimings; }
    public void setLineTimings(List<Map<String, Object>> lineTimings) { this.lineTimings = lineTimings; }

    public Boolean getLocked() { return locked; }
    public void setLocked(Boolean locked) { this.locked = locked; }
}
