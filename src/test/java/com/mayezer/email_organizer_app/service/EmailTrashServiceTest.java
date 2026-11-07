package com.mayezer.email_organizer_app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.gmail.Gmail;
import com.mayezer.email_organizer_app.config.AppAccount;
import com.mayezer.email_organizer_app.config.GmailTrashClient;

class EmailTrashServiceTest {
    private static final String ID = "synthetic-trash";
    private static final String ACCOUNT = "linked@example.test";
    private final EmailApiService emails = mock(EmailApiService.class);
    private final GmailTrashClient client = mock(GmailTrashClient.class);
    private final EmailTrashService service = new EmailTrashService(emails, new AppAccount(ACCOUNT), client);
    private final AtomicInteger writes = new AtomicInteger();

    @Test
    void recordsIntentBeforeGmailAndDoesNotRepeatAConfirmedAction() throws Exception {
        configureGmail(ACCOUNT, 200, () -> verify(emails).prepareTrash(ID));
        when(emails.prepareTrash(ID)).thenReturn(false, true);
        assertTrue(service.trash(ID));
        assertTrue(service.trash(ID));
        verify(emails).confirmTrash(ID);
        assertEquals(1, writes.get());
    }

    @Test
    void reportsPendingReconciliationWhenGmailSucceedsAndConfirmationFails() throws Exception {
        configureGmail(ACCOUNT, 200, () -> {});
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(emails).confirmTrash(ID);
        assertFalse(service.trash(ID));
        assertEquals(1, writes.get());
    }

    @Test
    void rejectsForeignIdsBeforeAccessingGmailAndRejectsMismatchedGmailAccounts() throws Exception {
        when(emails.prepareTrash("foreign")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.trash("foreign")).getStatusCode());
        verifyNoInteractions(client);

        configureGmail("other@example.test", 200, () -> {});
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.trash(ID)).getStatusCode());
        verify(emails, never()).confirmTrash(ID);
        assertEquals(0, writes.get());
    }

    @Test
    void rejectsMissingAuthorizationAndGmailScopeDenialWithoutConfirming() throws Exception {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.trash(ID)).getStatusCode());
        verify(client, never()).get();

        configureGmail(ACCOUNT, 403, () -> {});
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.trash(ID)).getStatusCode());
        verify(emails, never()).confirmTrash(ID);
    }

    @Test
    void rejectsARepeatedRequestWhileGmailIsStillProcessingIt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        configureGmail(ACCOUNT, 200, () -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> service.trash(ID));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> service.trash(ID)).getStatusCode());
            } finally {
                release.countDown();
            }
            assertTrue(request.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, writes.get());
        verify(emails).confirmTrash(ID);
    }

    private void configureGmail(String address, int status, DuringTrash during) throws Exception {
        var transport = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
                var path = URI.create(url).getPath();
                var response = new MockLowLevelHttpResponse().setContentType("application/json");
                if (path.equals("/gmail/v1/users/me/profile")) {
                    assertEquals("GET", method);
                    response.setContent("{\"emailAddress\":\"" + address + "\"}");
                } else {
                    assertEquals("/gmail/v1/users/me/messages/" + ID + "/trash", path);
                    assertEquals("POST", method);
                    writes.incrementAndGet();
                    during.run();
                    response.setStatusCode(status).setContent(status == 200
                        ? "{\"id\":\"" + ID + "\",\"labelIds\":[\"TRASH\"]}" : "{}");
                }
                return new MockLowLevelHttpRequest(url).setResponse(response);
            }
        };
        when(client.authorized()).thenReturn(true);
        when(client.get()).thenReturn(new Gmail.Builder(transport, GsonFactory.getDefaultInstance(), null)
            .setApplicationName("Synthetic trash tests").build());
    }

    @FunctionalInterface
    private interface DuringTrash {
        void run() throws IOException;
    }
}
