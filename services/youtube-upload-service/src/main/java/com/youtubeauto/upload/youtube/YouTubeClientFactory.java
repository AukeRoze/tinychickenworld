package com.youtubeauto.upload.youtube;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.YouTubeScopes;
import com.youtubeauto.upload.config.YouTubeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileReader;
import java.util.List;

/**
 * Wires up an authorized YouTube Data API client.
 *
 * First-run flow: opens a browser for OAuth consent and stores the
 * refresh token under app.youtube.credentials-dir. Subsequent runs
 * load the stored token automatically.
 *
 * For headless / production deploys you typically pre-seed the
 * credentials directory with the StoredCredential file produced
 * during a one-time local authorization.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouTubeClientFactory {

    private static final List<String> SCOPES = List.of(
            YouTubeScopes.YOUTUBE_UPLOAD,
            YouTubeScopes.YOUTUBE_READONLY,
            // captions.insert requires force-ssl for regular channels —
            // youtubepartner alone only covers CMS content partners, which is
            // why every SRT upload 403'd silently until ep 3's guard caught it.
            // NOTE: scope changes need a ONE-TIME re-consent: delete the
            // StoredCredential file in app.youtube.credentials-dir, restart,
            // and run through the OAuth browser flow again.
            YouTubeScopes.YOUTUBE_FORCE_SSL,
            // Deep analytics + reporting — for the self-learning loop's
            // retention curves and traffic-source breakdown.
            "https://www.googleapis.com/auth/yt-analytics.readonly",
            "https://www.googleapis.com/auth/youtubepartner"
    );

    private final YouTubeProperties props;
    private volatile YouTube cached;
    private volatile Credential cachedCredential;

    /** Returns the OAuth credential — used by AnalyticsApiService to call
     *  the YouTube Analytics REST API directly. */
    public Credential getCredential() {
        if (cachedCredential != null) return cachedCredential;
        get();   // forces auth + caches credential as a side-effect
        return cachedCredential;
    }

    public synchronized YouTube get() {
        if (cached != null) return cached;
        try {
            NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            GsonFactory json = GsonFactory.getDefaultInstance();

            GoogleClientSecrets secrets;
            try (var reader = new FileReader(props.clientSecretsPath())) {
                secrets = GoogleClientSecrets.load(json, reader);
            }

            FileDataStoreFactory store = new FileDataStoreFactory(new File(props.credentialsDir()));
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    transport, json, secrets, SCOPES)
                    .setDataStoreFactory(store)
                    .setAccessType("offline")
                    .build();

            // Bind0000Receiver: our own minimal HTTP server because the stock
            // LocalServerReceiver (jetty) silently binds to localhost only —
            // unreachable from outside the Docker container. We bind 0.0.0.0
            // and report 127.0.0.1 as redirect URI (Google requires loopback).
            Bind0000Receiver receiver = new Bind0000Receiver(8089, "/Callback");
            Credential cred = new AuthorizationCodeInstalledApp(flow, receiver)
                    .authorize("user");
            this.cachedCredential = cred;

            cached = new YouTube.Builder(transport, json, cred)
                    .setApplicationName(props.applicationName())
                    .build();
            log.info("YouTube client initialised");
            return cached;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build YouTube client: " + e.getMessage(), e);
        }
    }
}
