package com.youtubeauto.upload.api.dto;

import java.util.UUID;

public record UploadResponse(UUID jobId, String youtubeVideoId, String youtubeUrl,
                             String privacyStatus,
                             /** null = no SRT was sent; true/false = caption-track
                              *  upload outcome. A false means YouTube will show
                              *  AUTO-generated captions (which garble invented
                              *  words like "tok tok") — surfaced so a silent
                              *  caption failure can't slip through anymore. */
                             Boolean captionsUploaded) {}
