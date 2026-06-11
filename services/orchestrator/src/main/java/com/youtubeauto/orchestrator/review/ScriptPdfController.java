package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.PdfWriter;
import com.youtubeauto.orchestrator.client.ScriptServiceClient;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * GET /api/v1/videos/{id}/script.pdf — returns the script as a downloadable
 * PDF. If {@code language=nl} query parameter is present (default), the
 * script is translated on-demand via TranslationService first.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ScriptPdfController {

    private final VideoJobRepository repo;
    private final ScriptServiceClient scriptClient;
    private final TranslationService translator;

    @GetMapping("/api/v1/videos/{id}/script.pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID id) {
        Optional<VideoJob> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        VideoJob job = opt.get();
        if (job.getScriptJobId() == null) return ResponseEntity.notFound().build();

        try {
            JsonNode script = scriptClient.get(job.getScriptJobId()).path("script");
            JsonNode nl = translator.translateScript(
                    job.getScriptId() == null ? null : job.getScriptId().toString(), script);
            byte[] pdf = render(job, script, nl);
            String safeTopic = job.getTopic() == null ? "script" :
                    job.getTopic().replaceAll("[^a-zA-Z0-9._-]+", "_");
            String fname = "tinychickenworld_" + safeTopic + "_NL.pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header("Content-Disposition",
                            ContentDisposition.attachment().filename(fname).build().toString())
                    .body(pdf);
        } catch (Exception e) {
            log.error("PDF render failed for job {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private byte[] render(VideoJob job, JsonNode en, JsonNode nl) throws Exception {
        Font h1     = new Font(Font.HELVETICA, 24, Font.BOLD,   new Color(60, 60, 60));
        Font h2     = new Font(Font.HELVETICA, 14, Font.BOLD,   new Color(40, 100, 180));
        Font label  = new Font(Font.HELVETICA, 9,  Font.BOLD,   new Color(110, 110, 110));
        Font body   = new Font(Font.HELVETICA, 11, Font.NORMAL, new Color(20, 20, 20));
        Font small  = new Font(Font.HELVETICA, 9,  Font.ITALIC, new Color(110, 110, 110));

        Document doc = new Document(PageSize.A4, 50, 50, 50, 50);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, bytes);
        doc.open();

        // Header
        doc.add(new Paragraph("Tiny Chicken World — Script", h1));
        doc.add(new Paragraph(job.getTopic() == null ? "" : job.getTopic(), body));
        doc.add(new Paragraph("Vertaling: Nederlands  ·  Status: " + job.getStatus(), small));
        doc.add(Chunk.NEWLINE);

        // Title section (NL primary, EN below)
        String titleNl = nl == null ? "" : nl.path("title").asText("");
        String titleEn = en.path("title").asText("");
        if (!titleNl.isBlank() || !titleEn.isBlank()) {
            doc.add(new Paragraph("TITEL", label));
            if (!titleNl.isBlank()) doc.add(new Paragraph(titleNl, body));
            if (!titleEn.isBlank()) doc.add(new Paragraph("(EN: " + titleEn + ")", small));
            doc.add(Chunk.NEWLINE);
        }

        // Hook
        String hookNl = nl == null ? "" : nl.path("hook").asText("");
        String hookEn = en.path("hook").asText("");
        if (!hookNl.isBlank() || !hookEn.isBlank()) {
            doc.add(new Paragraph("HOOK (0-8 sec)", label));
            if (!hookNl.isBlank()) doc.add(new Paragraph(hookNl, body));
            if (!hookEn.isBlank()) doc.add(new Paragraph("(EN: " + hookEn + ")", small));
            doc.add(Chunk.NEWLINE);
        }

        // Scenes
        var nlScenes = translator.scenesBySeq(nl);
        doc.add(new Paragraph("SCENES", h2));
        for (JsonNode s : en.path("scenes")) {
            int seq = s.path("seq").asInt();
            int dur = s.path("durationSeconds").asInt();
            String phase = s.path("phase").asText("");
            String enVisual = s.path("visualDesc").asText("");
            String enNarr   = s.path("narration").asText("");

            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("Scene " + seq + "  ·  " + dur + "s"
                    + (phase.isBlank() ? "" : "  ·  " + phase.toUpperCase()), h2));

            var nlEntry = nlScenes.get(seq);
            String nlVisual = nlEntry == null ? "" : nlEntry.getOrDefault("visualDesc", "");
            String nlNarr   = nlEntry == null ? "" : nlEntry.getOrDefault("narration", "");

            doc.add(new Paragraph("BEELD", label));
            if (!nlVisual.isBlank()) doc.add(new Paragraph(nlVisual, body));
            if (!enVisual.isBlank()) doc.add(new Paragraph("(EN: " + enVisual + ")", small));

            doc.add(new Paragraph("NARRATIE", label));
            if (!nlNarr.isBlank())   doc.add(new Paragraph(nlNarr, body));
            if (!enNarr.isBlank())   doc.add(new Paragraph("(EN: " + enNarr + ")", small));

            // Dialogue lines
            JsonNode lines = s.path("lines");
            if (lines.isArray() && lines.size() > 0) {
                doc.add(new Paragraph("DIALOOG", label));
                for (JsonNode l : lines) {
                    String sp = l.path("speaker").asText("");
                    String tx = l.path("text").asText("");
                    Paragraph line = new Paragraph();
                    line.add(new Chunk(sp.toUpperCase() + ":  ",
                            new Font(Font.HELVETICA, 11, Font.BOLD, new Color(40, 100, 180))));
                    line.add(new Chunk(tx, body));
                    doc.add(line);
                }
            }
        }

        doc.close();
        return bytes.toByteArray();
    }
}
