package com.youtubeauto.orchestrator.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Serves an mp4 with HTTP Range support so the browser's &lt;video&gt; element can
 * stream and seek.
 *
 * <p>Returning a bare {@code Resource} via {@code ResponseEntity.ok().body(...)}
 * uses {@code ResourceHttpMessageConverter}, which writes the WHOLE file and
 * ignores the {@code Range} header — no {@code 206 Partial Content}, no
 * {@code Accept-Ranges}. Browsers rely on range requests to stream/seek video;
 * without them a clip buffered the opening and then stalled after ~1s
 * (feedback 2026-06-13: "clip speelt soms maar 1 sec af").
 *
 * <p>We write the bytes straight to the {@link HttpServletResponse} instead of
 * returning a {@code ResourceRegion}: this app customises its message-converter
 * list and the {@code ResourceRegionHttpMessageConverter} is not registered, so
 * returning a region threw {@code HttpMessageNotWritableException}. Writing the
 * stream directly needs no converter and is fully under our control.
 */
public final class VideoStreaming {

    private VideoStreaming() { }

    private static final int BUFFER = 64 * 1024;

    /**
     * Stream {@code path} to {@code response}, honouring a single {@code Range}.
     * The caller has already validated that the file exists.
     *
     * @param path           the mp4 to serve
     * @param requestHeaders incoming headers (inject with {@code @RequestHeader HttpHeaders}); only {@code Range} is read
     * @param response       the servlet response to write status, headers and bytes to
     */
    public static void serve(Path path, HttpHeaders requestHeaders, HttpServletResponse response)
            throws IOException {
        long length = Files.size(path);
        response.setContentType("video/mp4");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");

        List<HttpRange> ranges;
        try {
            ranges = requestHeaders.getRange();
        } catch (IllegalArgumentException e) {
            // Malformed Range header → 416 with the resource size so the client retries.
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            return;
        }

        if (ranges.isEmpty()) {
            // No Range header → full body (Accept-Ranges already advertised so a
            // follow-up seek issues a ranged request).
            response.setStatus(HttpStatus.OK.value());
            response.setContentLengthLong(length);
            try (var in = Files.newInputStream(path)) {
                in.transferTo(response.getOutputStream());
            }
            return;
        }

        // Honour the first range — browsers send a single range for <video>.
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length);
        long count = end - start + 1;

        response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        response.setContentLengthLong(count);

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(start);
            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[BUFFER];
            long remaining = count;
            while (remaining > 0) {
                int read = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (read == -1) break;
                out.write(buf, 0, read);
                remaining -= read;
            }
        }
    }
}
