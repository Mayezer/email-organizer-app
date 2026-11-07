package com.mayezer.email_organizer_app.config;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AppAccount(String gmailAddress) {

    public AppAccount(@Value("${EMAIL_ORGANIZER_APP_GMAIL_ACCOUNT:}") String gmailAddress) {
        this.gmailAddress = gmailAddress.strip().toLowerCase(Locale.ROOT);
    }
}
