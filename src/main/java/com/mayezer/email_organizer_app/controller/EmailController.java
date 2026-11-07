package com.mayezer.email_organizer_app.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mayezer.email_organizer_app.dto.EmailChanges;
import com.mayezer.email_organizer_app.dto.EmailItem;
import com.mayezer.email_organizer_app.dto.EmailPage;
import com.mayezer.email_organizer_app.dto.EmailSyncJob;
import com.mayezer.email_organizer_app.service.EmailApiService;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
public class EmailController {

    private final EmailApiService emailApiService;

    @GetMapping("/api/emails")
    public List<EmailItem> listEmails() {
        return emailApiService.listEmails();
    }

    @GetMapping("/api/email-pages")
    public EmailPage listEmailPage(
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "category", required = false) String category,
        @RequestParam(name = "at", required = false) String at
    ) {
        return emailApiService.listPage(cursor, category, at);
    }

    @GetMapping("/api/email-changes")
    public EmailChanges listEmailChanges(
        @RequestParam(name = "cursor") String cursor,
        @RequestParam(name = "until", required = false) String until,
        @RequestParam(name = "category", required = false) String category,
        @RequestParam(name = "include_updates", defaultValue = "false") boolean includeUpdates
    ) {
        return emailApiService.listChanges(cursor, until, category, includeUpdates);
    }

    @PostMapping("/api/email-sync-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EmailSyncJob startSync() {
        return emailApiService.startSync();
    }

    @GetMapping("/api/email-sync-jobs/{id}")
    public EmailSyncJob syncStatus(@PathVariable UUID id) {
        return emailApiService.syncStatus(id);
    }

    @PatchMapping("/api/emails/{gmailId}/manual-category")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignManualCategory(
        @PathVariable String gmailId, @RequestBody ManualCategory assignment
    ) {
        emailApiService.assignManualCategory(gmailId, assignment.category());
    }

    public record ManualCategory(String category) {}
}
