package com.mayezer.email_organizer_app;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.mayezer.email_organizer_app.dto.EmailConversation;
import com.mayezer.email_organizer_app.service.EmailApiService;
import com.mayezer.email_organizer_app.service.EmailAttachmentService;
import com.mayezer.email_organizer_app.service.EmailOriginalService;
import com.mayezer.email_organizer_app.service.EmailOriginalService.OriginalMessage;

@SpringBootTest(properties = {
    "EMAIL_ORGANIZER_APP_USERNAME=owner",
    "EMAIL_ORGANIZER_APP_GMAIL_ACCOUNT=linked@example.test",
})
@AutoConfigureMockMvc
class AppSecurityTest {

    private static final String PASSWORD = "synthetic-app-password";
    private static final String SESSION = "/api/session";
    private static final String FILE_ID = "a".repeat(64);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EmailApiService emails;

    @MockitoBean
    private EmailOriginalService originals;

    @MockitoBean
    private EmailAttachmentService attachments;

    @DynamicPropertySource
    static void password(DynamicPropertyRegistry registry) {
        String hash = new BCryptPasswordEncoder(4).encode(PASSWORD);
        registry.add("EMAIL_ORGANIZER_APP_PASSWORD_HASH", () -> hash);
    }

    @Test
    void blocksAnonymousReadsAndActionsBeforeCallingDjango() throws Exception {
        for (String path : new String[] {
            "/api/emails", "/api/email-pages", "/api/email-changes?cursor=synthetic",
            "/api/opportunities", "/api/email-sync-jobs/3174af9d-512a-4c83-9e36-f1e28c78163a", "/home",
            "/api/emails/synthetic-id/original", "/api/emails/synthetic-id/attachments/" + FILE_ID,
        }) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/email-sync-jobs")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/emails/synthetic-id/manual-category")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/emails/synthetic-id/board-column")).andExpect(status().isUnauthorized());
        verifyNoInteractions(emails);
        verifyNoInteractions(originals, attachments);
    }

    @Test
    void loginRequiresCsrfAndTheExplicitlyLinkedUser() throws Exception {
        Cookie csrf = csrfCookie(null);
        mvc.perform(post(SESSION + "/login").param("username", "owner").param("password", PASSWORD))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(SESSION + "/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
            .param("username", "another-user").param("password", PASSWORD))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(emails);
    }

    @Test
    void usesServerSessionAndInvalidatesItOnLogout() throws Exception {
        Cookie csrf = csrfCookie(null);
        var login = mvc.perform(post(SESSION + "/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .param("username", "owner").param("password", PASSWORD))
            .andExpect(status().isNoContent()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(get(SESSION).session(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.gmailAccount").value("linked@example.test"))
            .andExpect(jsonPath("$.access").doesNotExist())
            .andExpect(jsonPath("$.refresh").doesNotExist());
        mvc.perform(get("/api/emails").session(session)).andExpect(status().isOk());
        mvc.perform(post(SESSION + "/logout").session(session)).andExpect(status().isForbidden());
        Cookie authenticatedCsrf = csrfCookie(session);
        mvc.perform(post(SESSION + "/logout").session(session)
                .cookie(authenticatedCsrf).header("X-XSRF-TOKEN", authenticatedCsrf.getValue()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/emails")).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @Test
    void opensOnlyAnAuthorizedMessageAndMarksTheBodyAsNonCacheable() throws Exception {
        Cookie csrf = csrfCookie(null);
        var session = (MockHttpSession) mvc.perform(post(SESSION + "/login")
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .param("username", "owner").param("password", PASSWORD))
            .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        when(emails.readConversation("other-account")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/emails/other-account/original").session(session))
            .andExpect(status().isNotFound());
        verifyNoInteractions(originals, attachments);

        var conversation = new EmailConversation(List.of("synthetic-id", "other-message"), false);
        when(emails.readConversation("synthetic-id")).thenReturn(conversation);
        when(originals.read(conversation)).thenReturn(
            new OriginalMessage("Corpo sintético", false, List.of("https://example.test/agenda"), List.of()));
        mvc.perform(get("/api/emails/synthetic-id/original").session(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.text").value("Corpo sintético"))
            .andExpect(jsonPath("$.partial").value(false))
            .andExpect(jsonPath("$.links[0]").value("https://example.test/agenda"));
    }

    @Test
    void authorizesTheSourceMessageAndReturnsTheFileAsANonCacheableDownload() throws Exception {
        Cookie csrf = csrfCookie(null);
        var session = (MockHttpSession) mvc.perform(post(SESSION + "/login")
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .param("username", "owner").param("password", PASSWORD))
            .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);

        when(emails.readConversation("other-account")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/emails/other-account/attachments/" + FILE_ID).session(session))
            .andExpect(status().isNotFound());
        verifyNoInteractions(attachments);

        byte[] bytes = {0, 1, -1};
        doAnswer(call -> {
            call.getArgument(2, EmailAttachmentService.DownloadTarget.class).write("relatório.bin", bytes);
            return null;
        }).when(attachments).download(eq("synthetic-id"), eq(FILE_ID), any());
        mvc.perform(get("/api/emails/synthetic-id/attachments/" + FILE_ID).session(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''relat%C3%B3rio.bin")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(content().contentType("application/octet-stream"))
            .andExpect(content().bytes(bytes));
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        var request = get(SESSION);
        if (session != null) {
            request.session(session);
        }
        Cookie cookie = mvc.perform(request).andExpect(status().isOk()).andReturn()
            .getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        return cookie;
    }
}
