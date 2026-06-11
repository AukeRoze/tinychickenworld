package com.youtubeauto.upload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import com.youtubeauto.upload.youtube.YouTubeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Deep YouTube Analytics — retention curves, CTR, traffic sources,
 * audience demographics. Uses the YouTube Analytics API directly via
 * REST (the Java client library has poor coverage of the metrics we
 * want) — we re-use the OAuth credentials from YouTubeClientFactory.
 *
 * Requires scope: https://www.googleapis.com/auth/yt-analytics.readonly
 * (Already requested by YouTubeClientFactory — re-OAuth might be needed
 * the first time this runs if the existing token predates the scope.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsApiService {

    private static final String API = "https://youtubeanalytics.googleapis.com/v2";
    private final YouTubeClientFactory factory;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Returns the audience retention curve for a video — relative views
     *  per fraction of the video. Empty map on failure. */
    public Map<String, Object> retentionCurve(String channelId, String videoId) {
        try {
            String url = API + "/reports?"
                    + "ids=channel%3D%3D" + channelId
                    + "&startDate=2020-01-01"
                    + "&endDate=" + LocalDate.now().toString()
                    + "&metrics=audienceWatchRatio,relativeRetentionPerformance"
                    + "&dimensions=elapsedVideoTimeRatio"
                    + "&filters=video%3D%3D" + videoId;
            JsonNode resp = call(url);
            return resp == null ? Map.of() : mapper.convertValue(resp, Map.class);
        } catch (Exception e) {
            log.warn("retention curve fetch failed for {}: {}", videoId, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    /** Returns key engagement metrics: views, watch-time, AVD, CTR, etc. */
    public Map<String, Object> engagement(String channelId, String videoId) {
        try {
            String url = API + "/reports?"
                    + "ids=channel%3D%3D" + channelId
                    + "&startDate=2020-01-01"
                    + "&endDate=" + LocalDate.now().toString()
                    + "&metrics=views,estimatedMinutesWatched,averageViewDuration,"
                            + "averageViewPercentage,subscribersGained,likes,comments,shares,"
                            + "cardClickRate,cardImpressions,annotationClickThroughRate"
                    + "&filters=video%3D%3D" + videoId;
            JsonNode resp = call(url);
            return resp == null ? Map.of() : mapper.convertValue(resp, Map.class);
        } catch (Exception e) {
            log.warn("engagement fetch failed for {}: {}", videoId, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    /** Traffic source breakdown — where viewers came from. */
    public Map<String, Object> trafficSources(String channelId, String videoId) {
        try {
            String url = API + "/reports?"
                    + "ids=channel%3D%3D" + channelId
                    + "&startDate=2020-01-01"
                    + "&endDate=" + LocalDate.now().toString()
                    + "&metrics=views"
                    + "&dimensions=insightTrafficSourceType"
                    + "&filters=video%3D%3D" + videoId;
            JsonNode resp = call(url);
            return resp == null ? Map.of() : mapper.convertValue(resp, Map.class);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private JsonNode call(String url) {
        try {
            Credential cred = factory.getCredential();
            if (cred == null) return null;
            String token = cred.getAccessToken();
            if (token == null) {
                cred.refreshToken();
                token = cred.getAccessToken();
            }
            return WebClient.builder().build().get().uri(url)
                    .header("Authorization", "Bearer " + token)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            log.warn("Analytics API call failed: {}", e.getMessage());
            return null;
        }
    }
}
