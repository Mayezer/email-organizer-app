package com.mayezer.email_organizer_app.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.mayezer.email_organizer_app.dto.EmailChanges;
import com.mayezer.email_organizer_app.dto.EmailConversation;
import com.mayezer.email_organizer_app.dto.EmailItem;
import com.mayezer.email_organizer_app.dto.EmailMetadata;
import com.mayezer.email_organizer_app.dto.EmailPage;
import com.mayezer.email_organizer_app.dto.EmailSyncJob;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class EmailApiService {

    private static final String EMAILS_PATH = "/api/v1/emails/";
    private static final String EMAIL_PAGES_PATH = "/api/v1/email-pages/";
    private static final String EMAIL_CHANGES_PATH = "/api/v1/email-changes/";
    private static final String SYNC_PATH = "/api/v1/email-sync-jobs/";
    private static final int PAGE_SIZE = 5;
    private static final int MAX_CONVERSATION_MESSAGES = 5;
    private final RestClient restClient;
    private final ApiAuthService apiAuthService;

    public List<EmailItem> listEmails() {
        return list(null, EmailItem[].class);
    }

    public EmailConversation readConversation(String gmailId) {
        EmailConversation conversation = withAuthentication(token -> {
            try {
                return restClient.get().uri(EMAILS_PATH + "{id}/conversation/", gmailId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve().body(EmailConversation.class);
            } catch (HttpClientErrorException.NotFound exception) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        });
        if (conversation == null || conversation.gmailIds() == null
            || conversation.gmailIds().size() > MAX_CONVERSATION_MESSAGES
            || !conversation.gmailIds().contains(gmailId)
            || conversation.gmailIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,255}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API returned an invalid conversation");
        }
        return conversation;
    }

    <T> List<T> list(String category, Class<T[]> responseType) {
        Map<String, String> parameters = category == null ? Map.of() : Map.of("category", category);
        T[] result = withAuthentication(token -> fetch(EMAILS_PATH, parameters, token, responseType));
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API did not return an email list");
        }
        for (T email : result) {
            if (email == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API returned an invalid email list");
            }
        }
        return List.of(result);
    }

    public EmailPage listPage(String cursor, String category, String at, String processingStatus, String boardColumn) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (cursor != null) {
            parameters.put("cursor", cursor);
        }
        if (category != null) {
            parameters.put("category", category);
        }
        if (at != null) {
            parameters.put("at", at);
        }
        if (processingStatus != null) {
            parameters.put("processing_status", processingStatus);
        }
        if (boardColumn != null) {
            parameters.put("board_column", boardColumn);
        }
        EmailPage page = fetchPage(EMAIL_PAGES_PATH, parameters, EmailPage.class);
        if (page == null || page.changeCursor() == null || !validItems(page.items())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API returned an invalid email page");
        }
        return page;
    }

    public EmailChanges listChanges(String cursor, String until, String category, boolean includeUpdates) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("cursor", cursor);
        if (until != null) {
            parameters.put("until", until);
        }
        if (category != null) {
            parameters.put("category", category);
        }
        if (includeUpdates) {
            parameters.put("include_updates", "true");
        }
        EmailChanges changes = fetchPage(EMAIL_CHANGES_PATH, parameters, EmailChanges.class);
        if (changes == null || changes.changeCursor() == null || !validItems(changes.items())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API returned invalid email changes");
        }
        return changes;
    }

    public EmailSyncJob startSync() {
        return checkedSync(withAuthentication(token -> restClient.post().uri(SYNC_PATH)
            .headers(headers -> headers.setBearerAuth(token)).retrieve().body(EmailSyncJob.class)));
    }

    public EmailSyncJob syncStatus(UUID id) {
        return checkedSync(withAuthentication(token -> {
            try {
                return fetch(SYNC_PATH + id + "/", Map.of(), token, EmailSyncJob.class);
            } catch (HttpClientErrorException.NotFound exception) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }));
    }

    private EmailSyncJob checkedSync(EmailSyncJob job) {
        if (job == null || job.id() == null || job.status() == null
            || !Set.of("IMPORTING", "ANALYZING", "COMPLETED", "FAILED").contains(job.status())
            || job.importedCount() < 0 || job.analyzedCount() < 0 || job.analysisFailedCount() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API returned an invalid sync job");
        }
        return job;
    }

    private <T> T fetchPage(String path, Map<String, String> parameters, Class<T> responseType) {
        return withAuthentication(token -> {
            try {
                return fetch(path, parameters, token, responseType);
            } catch (HttpClientErrorException.BadRequest exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email page cursor");
            }
        });
    }

    private boolean validItems(List<EmailMetadata> items) {
        return items != null && items.size() <= PAGE_SIZE && items.stream()
            .allMatch(item -> item != null && item.gmailId() != null && item.importedAt() != null);
    }

    public void assignManualCategory(String gmailId, String category) {
        if (category == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category is required");
        }
        patchEmail(gmailId, "manual-category", Map.of("category", category),
            RestClient.ResponseSpec::toBodilessEntity);
    }

    public boolean prepareTrash(String gmailId) {
        return trashStage(gmailId, "prepare").trashed();
    }

    public void confirmTrash(String gmailId) {
        if (!trashStage(gmailId, "confirm").trashed()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API did not confirm trash");
        }
    }

    private TrashState trashStage(String gmailId, String stage) {
        TrashState state = patchEmail(gmailId, "trash", Map.of("stage", stage),
            response -> response.body(TrashState.class));
        if (state == null || state.trashed() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API returned an invalid trash state");
        }
        return state;
    }

    private record TrashState(Boolean trashed) {}

    public EmailMetadata moveCard(String gmailId, String column) {
        if (column == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Column is required");
        }
        EmailMetadata email = patchEmail(gmailId, "board-column", Map.of("column", column),
            response -> response.body(EmailMetadata.class));
        if (email == null || !gmailId.equals(email.gmailId()) || email.importedAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API returned an invalid card");
        }
        return email;
    }

    private <T> T patchEmail(String gmailId, String action, Map<String, String> body,
                             Function<RestClient.ResponseSpec, T> read) {
        return withAuthentication(token -> {
            try {
                return read.apply(restClient.patch().uri(EMAILS_PATH + "{id}/" + action + "/", gmailId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(body)
                    .retrieve());
            } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.NotFound
                     | HttpClientErrorException.Conflict exception) {
                throw new ResponseStatusException(exception.getStatusCode());
            }
        });
    }

    private <T> T withAuthentication(Function<String, T> query) {
        try {
            String accessToken = apiAuthService.getAccessToken();
            try {
                return query.apply(accessToken);
            } catch (HttpClientErrorException.Unauthorized exception) {
                return query.apply(apiAuthService.renewAfterUnauthorized(accessToken));
            }
        } catch (HttpClientErrorException.Forbidden exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email API denied access");
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API rejected authentication");
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email API is unavailable");
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API returned an invalid response");
        }
    }

    private <T> T fetch(String path, Map<String, String> parameters, String accessToken, Class<T> responseType) {
        return restClient.get().uri(path, builder -> {
                parameters.keySet().forEach(name -> builder.queryParam(name, "{" + name + "}"));
                return builder.build(parameters);
            })
            .headers(headers -> headers.setBearerAuth(accessToken))
            .retrieve().body(responseType);
    }
}
