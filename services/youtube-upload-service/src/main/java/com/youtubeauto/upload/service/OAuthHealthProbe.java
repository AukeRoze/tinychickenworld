package com.youtubeauto.upload.service;

import com.youtubeauto.upload.config.YouTubeProperties;
import com.youtubeauto.upload.youtube.YouTubeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Architecture.md §8 risk, finally covered: "YouTube OAuth refresh token
 * verloopt stilletjes → upload faalt". A revoked/expired refresh token only
 * surfaces at the worst moment — publish time. This probe makes a tiny
 * authenticated call twice a day so a dead token shows up in the logs (and on
 * {@code GET /api/v1/upload/oauth-health}) DAYS before it costs a release.
 *
 * Safety: the probe only runs when a StoredCredential exists — otherwise
 * {@code factory.get()} would start the INTERACTIVE consent flow and block the
 * scheduler thread waiting for a browser that never comes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthHealthProbe {

    private final YouTubeClientFactory factory;
    private final YouTubeProperties props;

    private volatile boolean healthy = true;
    private volatile String lastError;
    private volatile OffsetDateTime checkedAt;

    /** Twice a day, first check 2 minutes after startup. */
    @Scheduled(initialDelay = 2L * 60L * 1000L, fixedDelay = 12L * 60L * 60L * 1000L)
    public void check() {
        File stored = new File(props.credentialsDir(), "StoredCredential");
        if (!stored.exists()) {
            healthy = false;
            lastError = "No StoredCredential yet — run the one-time OAuth consent flow.";
            checkedAt = OffsetDateTime.now();
            log.warn("OAuth health: {}", lastError);
            return;
        }
        try {
            factory.get().channels().list(List.of("id")).setMine(true).execute();
            if (!healthy) log.info("OAuth health RECOVERED — token works again.");
            healthy = true;
            lastError = null;
        } catch (Exception e) {
            healthy = false;
            lastError = e.getMessage();
            log.error("OAUTH HEALTH FAILED — the refresh token is dead or lacks scopes; "
                    + "uploads WILL fail until you delete StoredCredential and re-consent: {}",
                    e.getMessage());
        }
        checkedAt = OffsetDateTime.now();
    }

    public boolean isHealthy()        { return healthy; }
    public String lastError()         { return lastError; }
    public OffsetDateTime checkedAt() { return checkedAt; }
}
