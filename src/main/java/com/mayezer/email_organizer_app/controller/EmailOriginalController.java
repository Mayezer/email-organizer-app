package com.mayezer.email_organizer_app.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mayezer.email_organizer_app.service.EmailApiService;
import com.mayezer.email_organizer_app.service.EmailAttachmentService;
import com.mayezer.email_organizer_app.service.EmailOriginalService;
import com.mayezer.email_organizer_app.service.EmailOriginalService.OriginalMessage;

@RestController
public class EmailOriginalController {
    private final EmailApiService emails;
    private final EmailOriginalService originals;
    private final EmailAttachmentService attachments;

    public EmailOriginalController(
        EmailApiService emails, EmailOriginalService originals, EmailAttachmentService attachments
    ) {
        this.emails = emails;
        this.originals = originals;
        this.attachments = attachments;
    }

    @GetMapping("/api/emails/{gmailId}/original")
    public ResponseEntity<OriginalMessage> read(@PathVariable String gmailId) {
        validateGmailId(gmailId);
        var conversation = emails.readConversation(gmailId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(originals.read(conversation));
    }

    @GetMapping("/api/emails/{gmailId}/attachments/{fileId}")
    public void download(@PathVariable String gmailId, @PathVariable String fileId,
                         HttpServletResponse response) throws IOException {
        validateGmailId(gmailId);
        if (!fileId.matches("[a-f0-9]{64}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        emails.readConversation(gmailId);
        attachments.download(gmailId, fileId, (filename, bytes) -> {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setContentLength(bytes.length);
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString());
            response.getOutputStream().write(bytes);
            response.flushBuffer();
        });
    }

    static void validateGmailId(String gmailId) {
        if (!gmailId.matches("[A-Za-z0-9_-]{1,255}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
