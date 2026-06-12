package com.youtubeauto.upload.service;

import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.FileContent;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelBannerResource;
import com.google.api.services.youtube.model.ChannelBrandingSettings;
import com.google.api.services.youtube.model.ImageSettings;
import com.youtubeauto.upload.youtube.YouTubeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Replaces the channel's YouTube BANNER (channel art) — the Branding-studio's
 * "⬆ Upload banner naar YouTube" knop.
 *
 * Two-step dance, exactly as the Data API v3 requires:
 * <ol>
 *   <li>{@code channelBanners.insert} — resumable upload of the image file;
 *       the response is a {@link ChannelBannerResource} whose {@code url} is a
 *       temporary handle (valid ~portion of a day), NOT yet live;</li>
 *   <li>{@code channels.update} (part=brandingSettings) with
 *       {@code brandingSettings.image.bannerExternalUrl = url} on the own
 *       channel — THAT makes the banner live. The existing brandingSettings
 *       are fetched first and mutated in place: channels.update REPLACES the
 *       whole brandingSettings object, so sending only the image would wipe
 *       the channel description/keywords set in Studio.</li>
 * </ol>
 *
 * Channel-id lookup follows the OAuthHealthProbe pattern:
 * {@code channels().list(List.of("id")).setMine(true)} (here with
 * "brandingSettings" added, see above).
 *
 * Scope note: channelBanners.insert + channels.update are covered by the
 * already-granted youtube.force-ssl scope — no re-consent needed.
 *
 * Image requirements (enforced by YouTube, we just fail with their message):
 * minimum 2048×1152, ≤ 6 MB. The orchestrator uploads 2560×1440, the
 * recommended size with the centred 1546×423 safe area.
 *
 * NOT possible by design: the channel AVATAR (profile picture). The Data API
 * has no endpoint for it — that one stays a manual Studio action.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelBannerService {

    /** YouTube rejects banner files over 6 MB. */
    private static final long MAX_BANNER_BYTES = 6L * 1024 * 1024;

    /** Outcome: the (temporary) banner URL YouTube assigned + the channel it
     *  is now live on. */
    public record Result(String bannerUrl, String channelId) {}

    private final YouTubeClientFactory clientFactory;

    public Result uploadBanner(Path bannerPath) {
        if (bannerPath == null || !Files.isRegularFile(bannerPath)) {
            throw new IllegalArgumentException("banner file not found: " + bannerPath);
        }
        long size = bannerPath.toFile().length();
        if (size > MAX_BANNER_BYTES) {
            throw new IllegalArgumentException("banner is " + size
                    + " bytes — YouTube's limit is 6MB");
        }
        try {
            YouTube yt = clientFactory.get();

            // Step 1: resumable upload of the image itself.
            String mediaType = bannerPath.toString().toLowerCase().endsWith(".png")
                    ? "image/png" : "image/jpeg";
            FileContent media = new FileContent(mediaType, bannerPath.toFile());
            YouTube.ChannelBanners.Insert insert =
                    yt.channelBanners().insert(new ChannelBannerResource(), media);
            MediaHttpUploader uploader = insert.getMediaHttpUploader();
            uploader.setDirectUploadEnabled(false);   // resumable, like the video upload
            ChannelBannerResource uploaded = insert.execute();
            String url = uploaded.getUrl();
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("channelBanners.insert returned no url");
            }
            log.info("channel banner uploaded ({} bytes) -> {}", size, url);

            // Step 2: point the own channel's brandingSettings at the new url.
            // Fetch the EXISTING brandingSettings and only swap the image —
            // channels.update replaces the whole object (see class comment).
            var listResp = yt.channels()
                    .list(List.of("id", "brandingSettings"))
                    .setMine(true)
                    .execute();
            if (listResp.getItems() == null || listResp.getItems().isEmpty()) {
                throw new IllegalStateException("channels.list(mine=true) returned no channel");
            }
            Channel own = listResp.getItems().get(0);
            ChannelBrandingSettings branding = own.getBrandingSettings() != null
                    ? own.getBrandingSettings() : new ChannelBrandingSettings();
            ImageSettings image = branding.getImage() != null
                    ? branding.getImage() : new ImageSettings();
            image.setBannerExternalUrl(url);
            branding.setImage(image);

            Channel update = new Channel();
            update.setId(own.getId());
            update.setBrandingSettings(branding);
            yt.channels().update(List.of("brandingSettings"), update).execute();
            log.info("channel {} branding updated — new banner live", own.getId());

            return new Result(url, own.getId());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("channel banner upload failed: " + e.getMessage(), e);
        }
    }
}
