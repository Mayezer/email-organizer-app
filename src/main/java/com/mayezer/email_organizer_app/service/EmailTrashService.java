package com.mayezer.email_organizer_app.service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.mayezer.email_organizer_app.config.AppAccount;
import com.mayezer.email_organizer_app.config.GmailTrashClient;

@Service
public class EmailTrashService {
    private final EmailApiService emails;
    private final AppAccount account;
    private final GmailTrashClient client;
    private final Semaphore slots = new Semaphore(2);
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public EmailTrashService(EmailApiService emails, AppAccount account, GmailTrashClient client) {
        this.emails = emails;
        this.account = account;
        this.client = client;
    }

    public boolean trash(String gmailId) {
        if (!slots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        try {
            if (!active.add(gmailId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            try {
                if (emails.prepareTrash(gmailId)) {
                    return true;
                }
                moveInGmail(gmailId);
                try {
                    emails.confirmTrash(gmailId);
                    return true;
                } catch (ResponseStatusException failure) {
                    // The pending row lets the read-only worker confirm the Gmail result on the next sync.
                    return false;
                }
            } finally {
                active.remove(gmailId);
            }
        } finally {
            slots.release();
        }
    }

    private void moveInGmail(String gmailId) {
        try {
            if (!client.authorized()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            var gmail = client.get();
            String address = gmail.users().getProfile("me").setFields("emailAddress").execute().getEmailAddress();
            if (!account.gmailAddress().equalsIgnoreCase(address)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            var result = gmail.users().messages().trash("me", gmailId).setFields("id,labelIds").execute();
            if (!gmailId.equals(result.getId()) || result.getLabelIds() == null || !result.getLabelIds().contains("TRASH")) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            }
        } catch (GoogleJsonResponseException failure) {
            throw new ResponseStatusException(switch (failure.getStatusCode()) {
                case 400, 401, 403 -> HttpStatus.FORBIDDEN;
                case 404, 410 -> HttpStatus.NOT_FOUND;
                case 429 -> HttpStatus.TOO_MANY_REQUESTS;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            });
        } catch (IOException | GeneralSecurityException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
