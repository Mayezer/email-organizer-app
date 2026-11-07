package com.mayezer.email_organizer_app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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

class EmailOriginalServiceTest {
    @Test
    void combinesMessagesInOneTextAndKeepsOnlyNewLinesFromActiveHtml() throws Exception {
        List<String> paths = new ArrayList<>();
        var service = service(paths);

        var original = service.read(conversation("plain", "html", "duplicate"));
        assertEquals("Primeira mensagem\nSegunda linha\n\nSegunda mensagem\nLinha após a quebra\nVer mais",
            original.text());
        assertFalse(original.partial());
        assertTrue(original.links().isEmpty());
        assertEquals(List.of(
            "/gmail/v1/users/linked@example.test/messages/plain",
            "/gmail/v1/users/linked@example.test/messages/html",
            "/gmail/v1/users/linked@example.test/messages/duplicate"
        ), paths);
    }

    @Test
    void mapsMissingMessagesAndGmailFailures() throws Exception {
        var service = service(new ArrayList<>());
        var missing = assertThrows(ResponseStatusException.class, () -> service.read(conversation("missing")));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        var unavailable = assertThrows(ResponseStatusException.class, () -> service.read(conversation("busy")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        var remaining = service.read(conversation("missing", "plain"));
        assertEquals("Primeira mensagem\nSegunda linha", remaining.text());
        assertTrue(remaining.partial());
    }

    @Test
    void retainsContentUniqueToEitherMimeAlternative() throws Exception {
        var original = service(new ArrayList<>()).read(conversation("alternatives"));
        assertEquals("Conteúdo comum\nSó na versão de texto\n\nSó na versão HTML", original.text());
    }

    @Test
    void extractsWebDestinationsFromTextHtmlAndAmpWithoutOpeningThem() throws Exception {
        List<String> paths = new ArrayList<>();
        var original = service(paths).read(conversation("links-text", "links-html", "links-amp"));

        assertEquals(List.of(
            "http://example.test/agenda(1)",
            "https://example.test/inscricao?a=1&b=2",
            "https://example.test/aviso",
            "https://example.test/amp"
        ), original.links());
        assertTrue(original.text().contains("https://example.test/rotulo-enganoso"));
        assertEquals(3, paths.size());
    }

    private EmailConversation conversation(String... ids) {
        return new EmailConversation(List.of(ids), false);
    }

    private EmailOriginalService service(List<String> paths) throws Exception {
        var transport = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
                URI uri = URI.create(url);
                paths.add(uri.getPath());
                assertEquals("GET", method);
                assertTrue(uri.getQuery().contains("format=full"));
                var request = new MockLowLevelHttpRequest(url);
                var response = new MockLowLevelHttpResponse().setContentType("application/json");
                if (url.contains("/messages/plain?")) {
                    response.setContent(message("plain", "text/plain", "Primeira mensagem\nSegunda linha"));
                } else if (url.contains("/messages/html?")) {
                    response.setContent(message("html", "text/html", """
                        <p>Segunda mensagem<br>Linha após a quebra</p><p><a href="javascript:alert(1)">Ver mais</a></p>
                        <blockquote>Primeira mensagem<br>Segunda linha</blockquote>
                        <script>alert(1)</script><style>div{display:none}</style>
                        <img src="https://example.test/tracker" onerror="alert(2)">
                        <iframe src="https://example.test/frame">Indesejado</iframe>
                        """));
                } else if (url.contains("/messages/duplicate?")) {
                    response.setContent(message("duplicate", "text/plain", "> Primeira mensagem\n> Segunda linha"));
                } else if (url.contains("/messages/alternatives?")) {
                    response.setContent("""
                        {"id":"alternatives","payload":{"mimeType":"multipart/alternative","parts":[
                          {"mimeType":"text/html","body":{"data":"%s"}},
                          {"mimeType":"text/plain","body":{"data":"%s"}}
                        ]}}
                        """.formatted(encode("<p>Conteúdo comum</p><p>Só na versão HTML</p>"),
                            encode("Conteúdo comum\nSó na versão de texto")));
                } else if (url.contains("/messages/links-text?")) {
                    response.setContent(message("links-text", "text/plain", """
                        Confira a agenda (http://example.test/agenda(1)).
                        javascript:alert("https://example.test/inativo")
                        data:text/plain,https://example.test/inativo
                        https:///example.test/invalido
                        https://example.test/%zz
                        """));
                } else if (url.contains("/messages/links-html?")) {
                    response.setContent(message("links-html", "text/html", """
                        <base href="https://example.test/base">
                        <a href="https://example.test/inscricao?a=1&amp;b=2">https://example.test/rotulo-enganoso</a>
                        <a href="http://example.test/agenda(1)">Agenda repetida</a>
                        <a href="javascript:alert(1)">https://example.test/inativo</a>
                        <a href="data:text/html,inativo">Dados</a>
                        <a href="https://example.test:70000/invalido">Porta inválida</a>
                        <a href="https://example.test@other.example.test/">Credenciais</a>
                        <a href="//example.test/relativo">Relativo</a>
                        <a href="/relativo">Relativo à base</a>
                        <p hidden><a href="https://example.test/oculto">Oculto</a></p>
                        <img src="https://example.test/recurso">
                        <p>Confira https://example.test/aviso.</p>
                        """));
                } else if (url.contains("/messages/links-amp?")) {
                    response.setContent(message("links-amp", "text/x-amp-html", """
                        <a href="https://example.test/amp"><amp-img src="https://example.test/recurso"></amp-img></a>
                        """));
                } else {
                    response.setStatusCode(url.contains("/messages/missing?") ? 404 : 429);
                }
                return request.setResponse(response);
            }
        };
        var client = mock(GmailReadClient.class);
        when(client.get()).thenReturn(new Gmail.Builder(transport, GsonFactory.getDefaultInstance(), null)
            .setApplicationName("Synthetic original tests").build());
        return new EmailOriginalService(new AppAccount("linked@example.test"), client);
    }

    private String message(String id, String mime, String text) {
        return """
            {"id":"%s","threadId":"same-thread","payload":{"mimeType":"multipart/mixed","parts":[
              {"mimeType":"%s","body":{"data":"%s"}},
              {"mimeType":"text/plain","filename":"attachment.txt","body":{"attachmentId":"ignored","size":5}}
            ]}}
            """.formatted(id, mime, encode(text));
    }

    private String encode(String text) {
        return Base64.getUrlEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
