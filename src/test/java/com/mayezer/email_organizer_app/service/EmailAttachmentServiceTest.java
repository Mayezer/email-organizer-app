package com.mayezer.email_organizer_app.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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
import com.mayezer.email_organizer_app.config.GmailReadClient;
import com.mayezer.email_organizer_app.dto.EmailConversation;
import com.mayezer.email_organizer_app.service.EmailAttachmentService.Attachment;

class EmailAttachmentServiceTest {
    private static final String MESSAGE = "/gmail/v1/users/linked@example.test/messages/";
    private final List<String> paths = new ArrayList<>();

    @Test
    void listsNestedFilesWithoutFetchingThemAndDownloadsExactBytesOnDemand() throws Exception {
        var client = client();
        var service = new EmailAttachmentService(account(), client);
        var files = describe(client, "source");
        assertEquals(List.of(MESSAGE + "source"), paths);
        assertEquals(List.of("relatório.bin", "local.txt", "grande.zip", "removido.txt", "cresceu.txt"),
            files.stream().map(Attachment::filename).toList());
        var remote = files.get(0);
        assertEquals(3, remote.size());
        assertFalse(remote.tooLarge());
        service.download(remote.gmailId(), remote.id(), (filename, bytes) -> {
            assertEquals("relatório.bin", filename);
            assertArrayEquals(new byte[] {0, -1, 42}, bytes);
        });
        var inline = files.get(1);
        service.download(inline.gmailId(), inline.id(), (filename, bytes) -> assertArrayEquals(new byte[] {65}, bytes));
        assertEquals(List.of(MESSAGE + "source", MESSAGE + "source", MESSAGE + "source/attachments/remote",
            MESSAGE + "source"), paths);
    }

    @Test
    void rejectsForeignIdsMissingFilesAndOversizedContentBeforeSendingBytes() throws Exception {
        var client = client();
        var files = describe(client, "source");
        var service = new EmailAttachmentService(account(), client);
        paths.clear();
        assertFailure(service, "different-message", files.getFirst().id(), HttpStatus.NOT_FOUND);
        assertEquals(List.of(MESSAGE + "different-message"), paths);
        var oversized = files.get(2);
        assertTrue(oversized.tooLarge());
        assertFailure(service, "source", oversized.id(), HttpStatus.CONTENT_TOO_LARGE);
        assertEquals(List.of(MESSAGE + "different-message", MESSAGE + "source"), paths);
        assertFailure(service, "source", files.get(3).id(), HttpStatus.NOT_FOUND);
        assertFailure(service, "source", files.get(4).id(), HttpStatus.CONTENT_TOO_LARGE);
        assertTrue(paths.contains(MESSAGE + "source/attachments/missing"));
    }

    @Test
    void retainsBothPermitsUntilTheClientResponseIsWritten() throws Exception {
        var client = client();
        var remote = describe(client, "source").getFirst();
        var service = new EmailAttachmentService(account(), client);
        service.download("source", remote.id(), (firstName, firstBytes) ->
            service.download("source", remote.id(), (secondName, secondBytes) -> {
                int calls = paths.size();
                assertFailure(service, "source", remote.id(), HttpStatus.SERVICE_UNAVAILABLE);
                assertEquals(calls, paths.size());
            }));
        service.download("source", remote.id(), (name, bytes) -> assertEquals(3, bytes.length));
    }

    private void assertFailure(EmailAttachmentService service, String message, String id, HttpStatus status) {
        var failure = assertThrows(ResponseStatusException.class,
            () -> service.download(message, id, (name, bytes) -> fail("A resposta não deve conter um arquivo")));
        assertEquals(status, failure.getStatusCode());
    }

    private List<Attachment> describe(GmailReadClient client, String message) {
        var original = new EmailOriginalService(account(), client)
            .read(new EmailConversation(List.of(message), false));
        assertTrue(original.text().isEmpty());
        return original.attachments();
    }

    private AppAccount account() {
        return new AppAccount("linked@example.test");
    }

    private GmailReadClient client() throws Exception {
        var transport = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                var path = URI.create(url).getPath();
                paths.add(path);
                var response = new MockLowLevelHttpResponse().setContentType("application/json");
                if (path.endsWith("/attachments/remote")) {
                    response.setContent("{\"size\":3,\"data\":\"AP8q\"}");
                } else if (path.endsWith("/attachments/missing")) {
                    response.setStatusCode(404).setContent("{}");
                } else if (path.endsWith("/attachments/grew")) {
                    response.setContent("{\"size\":10485761,\"data\":\"YQ\"}");
                } else {
                    response.setContent("""
                        {"id":"%s","payload":{"mimeType":"multipart/mixed","parts":[
                          {"mimeType":"multipart/related","parts":[
                            {"mimeType":"application/octet-stream","filename":"relatório.bin",
                             "body":{"attachmentId":"remote","size":3}},
                            {"mimeType":"text/plain","filename":"local.txt","body":{"data":"QQ","size":1}}]},
                          {"filename":"grande.zip","body":{"attachmentId":"big","size":10485761}},
                          {"filename":"removido.txt","body":{"attachmentId":"missing","size":5}},
                          {"filename":"cresceu.txt","body":{"attachmentId":"grew","size":1}}
                        ]}}
                        """.formatted(path.substring(path.lastIndexOf('/') + 1)));
                }
                return new MockLowLevelHttpRequest(url).setResponse(response);
            }
        };
        var client = mock(GmailReadClient.class);
        when(client.get()).thenReturn(new Gmail.Builder(transport, GsonFactory.getDefaultInstance(), null)
            .setApplicationName("Synthetic attachment tests").build());
        return client;
    }
}
