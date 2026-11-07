package com.mayezer.email_organizer_app.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mayezer.email_organizer_app.service.EmailTrashService;

@RestController
public class EmailTrashController {
    private final EmailTrashService trash;

    public EmailTrashController(EmailTrashService trash) {
        this.trash = trash;
    }

    @PostMapping("/api/emails/{gmailId}/trash")
    public ResponseEntity<Void> trash(@PathVariable String gmailId) {
        EmailOriginalController.validateGmailId(gmailId);
        return (trash.trash(gmailId) ? ResponseEntity.noContent() : ResponseEntity.accepted())
            .cacheControl(CacheControl.noStore()).build();
    }
}
