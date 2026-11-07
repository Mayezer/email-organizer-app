package com.mayezer.email_organizer_app.controller;

import java.security.Principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mayezer.email_organizer_app.config.AppAccount;

@RestController
public class AppSessionController {

    private final AppAccount account;

    public AppSessionController(AppAccount account) {
        this.account = account;
    }

    @GetMapping("/api/session")
    public Session session(Principal principal) {
        return new Session(principal == null ? null : account.gmailAddress());
    }

    public record Session(String gmailAccount) {}
}
