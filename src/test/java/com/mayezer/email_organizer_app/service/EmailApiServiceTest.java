package com.mayezer.email_organizer_app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.mayezer.email_organizer_app.controller.EmailController;
import com.mayezer.email_organizer_app.config.AppAccount;
import com.mayezer.email_organizer_app.config.EmailApiConfig;

class EmailApiServiceTest {

    private static final String BASE = "http://email-api.test";
    private static final String LOGIN = BASE + "/api/v1/authentication/token/";
    private static final String REFRESH = BASE + "/api/v1/authentication/token/refresh/";
    private static final String EMAILS = BASE + "/api/v1/emails/";
    private static final String EMAIL_PAGES = BASE + "/api/v1/email-pages/";
    private static final String EMAIL_CHANGES = BASE + "/api/v1/email-changes/";
    private MockRestServiceServer server;
    private ApiAuthService authService;
    private EmailApiService emailService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = new EmailApiConfig().emailApiClient(builder, new AppAccount("linked@example.test"), BASE);
        MockEnvironment environment = new MockEnvironment()
            .withProperty("EMAIL_ORGANIZER_API_USERNAME", "test-user")
            .withProperty("EMAIL_ORGANIZER_API_PASSWORD", "test-password");
        authService = new ApiAuthService(client, environment);
        emailService = new EmailApiService(client, authService);
    }

    @Test
    void preservesTrueFalseAndUnknownIndicatorsInThePagedResponse() throws Exception {
        server.expect(requestTo(LOGIN)).andRespond(withSuccess(
            "{\"access\":\"a1\",\"refresh\":\"r1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAIL_PAGES)).andRespond(withSuccess("""
            {"items":[
              {"gmail_id":"synthetic-checked","imported_at":"2026-11-06T11:00:00Z","has_attachments":false,"has_links":true},
              {"gmail_id":"synthetic-legacy","imported_at":"2026-11-06T11:00:00Z","has_attachments":null,"has_links":null}
            ],"change_cursor":"confirmed","next_cursor":null}
            """, MediaType.APPLICATION_JSON));
        MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build()
            .perform(get("/api/email-pages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].has_attachments").value(false))
            .andExpect(jsonPath("$.items[0].has_links").value(true))
            .andExpect(jsonPath("$.items[1].has_attachments").isEmpty())
            .andExpect(jsonPath("$.items[1].has_links").isEmpty());
        server.verify();
    }

    @Test
    void readsAllCategoriesAndStatesWithoutAFilter() throws Exception {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a1","refresh":"r1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS)).andExpect(header("Authorization", "Bearer a1"))
            .andExpect(header("X-Email-Account", "linked@example.test"))
            .andRespond(withSuccess("""
                [
                  {"gmail_id":"one","thread_id":"thread","received_at":"2026-11-06T10:00:00Z",
                   "processing_status":"COMPLETED","category":"advertising","security_status":"SUSPICIOUS",
                   "security_reason":"Solicitação de senha."},
                  {"gmail_id":"two","processing_status":"PENDING","category":null,"requires_action":null},
                  {"gmail_id":"three","processing_status":"FAILED"}
                ]
                """, MediaType.APPLICATION_JSON));

        MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build()
            .perform(get("/api/emails"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].gmail_id").value("one"))
            .andExpect(jsonPath("$[0].thread_id").value("thread"))
            .andExpect(jsonPath("$[0].received_at").value("2026-11-06T10:00:00Z"))
            .andExpect(jsonPath("$[0].security_status").value("SUSPICIOUS"))
            .andExpect(jsonPath("$[1].gmail_id").value("two"))
            .andExpect(jsonPath("$[1].requires_action").isEmpty())
            .andExpect(jsonPath("$[2].gmail_id").value("three"))
            .andExpect(jsonPath("$[2].processing_status").value("FAILED"));
        server.verify();
    }

    @Test
    void proxiesEmailPagesAndRetriesTheSameCursorAfterUnauthorized() throws Exception {
        String cursor = "opaque:+/=&";
        String category = "professional opportunities and recruitment";
        String encodedCategory = "professional%20opportunities%20and%20recruitment";
        String nextPage = EMAIL_PAGES + "?cursor=opaque%3A%2B%2F%3D%26&category=" + encodedCategory;
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a1","refresh":"r1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAIL_PAGES + "?category=" + encodedCategory))
            .andExpect(header("Authorization", "Bearer a1"))
            .andRespond(withSuccess("""
                {"items":[{"gmail_id":"one","thread_id":"thread","sender":"remetente@example.test",
                 "subject":"Mensagem de teste","received_at":null,"imported_at":"2026-11-06T10:00:00Z"}],
                 "next_cursor":"opaque:+/=&","change_cursor":"revision-1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(nextPage)).andExpect(header("Authorization", "Bearer a1"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(REFRESH)).andExpect(content().json("""
            {"refresh":"r1"}
            """)).andRespond(withSuccess("""
                {"access":"a2"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(nextPage)).andExpect(header("Authorization", "Bearer a2"))
            .andRespond(withSuccess("""
                {"items":[{"gmail_id":"two","thread_id":null,"sender":null,"subject":null,
                 "received_at":"2026-11-06T09:00:00Z","imported_at":"2026-11-06T09:30:00Z"}],
                 "next_cursor":null,"change_cursor":"revision-1"}
                """, MediaType.APPLICATION_JSON));

        var mvc = MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build();
        mvc.perform(get("/api/email-pages").param("category", category))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].gmail_id").value("one"))
            .andExpect(jsonPath("$.items[0].thread_id").value("thread"))
            .andExpect(jsonPath("$.items[0].sender").value("remetente@example.test"))
            .andExpect(jsonPath("$.items[0].subject").value("Mensagem de teste"))
            .andExpect(jsonPath("$.items[0].received_at").isEmpty())
            .andExpect(jsonPath("$.items[0].imported_at").value("2026-11-06T10:00:00Z"))
            .andExpect(jsonPath("$.change_cursor").value("revision-1"))
            .andExpect(jsonPath("$.next_cursor").value(cursor));
        mvc.perform(get("/api/email-pages").param("cursor", cursor).param("category", category))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].gmail_id").value("two"))
            .andExpect(jsonPath("$.items[0].received_at").value("2026-11-06T09:00:00Z"))
            .andExpect(jsonPath("$.next_cursor").hasJsonPath())
            .andExpect(jsonPath("$.next_cursor").isEmpty());
        server.verify();
    }

    @Test
    void proxiesConfirmedChangesAndReopensTheInboxAtTheReturnedCheckpoint() throws Exception {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a1","refresh":"r1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAIL_CHANGES + "?cursor=rev%3A1&until=rev%3A9&category="))
            .andExpect(header("Authorization", "Bearer a1"))
            .andRespond(withSuccess("""
                {"items":[{"gmail_id":"new","imported_at":"2026-11-06T11:00:00Z"}],
                 "change_cursor":"rev:6","until_cursor":"rev:9"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAIL_PAGES + "?category=&at=rev%3A6"))
            .andRespond(withSuccess("""
                {"items":[{"gmail_id":"new","imported_at":"2026-11-06T11:00:00Z"}],
                 "change_cursor":"rev:6","next_cursor":null}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAIL_CHANGES + "?cursor=invalid"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        var mvc = MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build();
        mvc.perform(get("/api/email-changes").param("cursor", "rev:1").param("until", "rev:9").param("category", ""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].gmail_id").value("new"))
            .andExpect(jsonPath("$.change_cursor").value("rev:6"))
            .andExpect(jsonPath("$.until_cursor").value("rev:9"));
        mvc.perform(get("/api/email-pages").param("category", "").param("at", "rev:6"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.change_cursor").value("rev:6"));
        mvc.perform(get("/api/email-changes").param("cursor", "invalid"))
            .andExpect(status().isBadRequest());
        server.verify();
    }

    @Test
    void forwardsBoardStatusColumnSnapshotAndTheIndependentPageCursor() throws Exception {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("{\"access\":\"a1\",\"refresh\":\"r1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAIL_PAGES + "?at=confirmed&processing_status=COMPLETED&board_column=event%20invitations"))
            .andExpect(header("Authorization", "Bearer a1"))
            .andRespond(withSuccess("""
                {"items":[{"gmail_id":"board-event","processing_status":"COMPLETED",
                 "effective_category":"event invitations","imported_at":"2026-11-06T11:00:00Z"}],
                 "change_cursor":"confirmed","next_cursor":"event-next"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAIL_PAGES + "?cursor=event-next&processing_status=COMPLETED&board_column=event%20invitations"))
            .andRespond(withSuccess("{\"items\":[],\"change_cursor\":\"confirmed\",\"next_cursor\":null}", MediaType.APPLICATION_JSON));
        var mvc = MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build();
        mvc.perform(get("/api/email-pages").param("board_column", "event invitations")
                .param("at", "confirmed").param("processing_status", "COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].gmail_id").value("board-event"))
            .andExpect(jsonPath("$.next_cursor").value("event-next"));
        mvc.perform(get("/api/email-pages").param("board_column", "event invitations")
                .param("cursor", "event-next").param("processing_status", "COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        server.verify();
    }

    @Test
    void forwardsCardMovementAndPropagatesRejectedMoves() throws Exception {
        server.expect(requestTo(LOGIN)).andRespond(withSuccess(
            "{\"access\":\"a1\",\"refresh\":\"r1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS + "synthetic-vacancy/board-column/"))
            .andExpect(header("Authorization", "Bearer a1"))
            .andExpect(content().json("{\"column\":\"event invitations\"}"))
            .andRespond(withSuccess("""
                {"gmail_id":"synthetic-vacancy","imported_at":"2026-11-06T11:00:00Z",
                 "processing_status":"COMPLETED","effective_category":"professional opportunities and recruitment",
                 "board_column":"event invitations","update_revision":"12"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS + "synthetic-pending/board-column/"))
            .andRespond(withStatus(HttpStatus.CONFLICT));
        var mvc = MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build();
        mvc.perform(patch("/api/emails/synthetic-vacancy/board-column")
                .contentType(MediaType.APPLICATION_JSON).content("{\"column\":\"event invitations\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.board_column").value("event invitations"))
            .andExpect(jsonPath("$.effective_category").value("professional opportunities and recruitment"))
            .andExpect(jsonPath("$.update_revision").value("12"));
        mvc.perform(patch("/api/emails/synthetic-pending/board-column")
                .contentType(MediaType.APPLICATION_JSON).content("{\"column\":\"event invitations\"}"))
            .andExpect(status().isConflict());
        server.verify();
    }

    @Test
    void startsAndObservesSyncWithServerSideCredentialsAndRenewsUnauthorizedOnce() throws Exception {
        String id = "452ec98e-b777-4e5b-91a9-14aef3e20a18";
        String syncPath = BASE + "/api/v1/email-sync-jobs/";
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("{\"access\":\"a1\",\"refresh\":\"r1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(syncPath))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer a1"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(REFRESH))
            .andRespond(withSuccess("{\"access\":\"a2\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(syncPath)).andExpect(header("Authorization", "Bearer a2"))
            .andRespond(withSuccess("""
                {"id":"%s","status":"IMPORTING","imported_count":5,"analyzed_count":0,"analysis_failed_count":0}
                """.formatted(id), MediaType.APPLICATION_JSON));
        server.expect(requestTo(syncPath + id + "/")).andExpect(header("Authorization", "Bearer a2"))
            .andRespond(withSuccess("""
                {"id":"%s","status":"COMPLETED","imported_count":12,"analyzed_count":11,"analysis_failed_count":1}
                """.formatted(id), MediaType.APPLICATION_JSON));
        var mvc = MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build();
        mvc.perform(post("/api/email-sync-jobs"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.imported_count").value(5))
            .andExpect(jsonPath("$.account").doesNotExist()).andExpect(jsonPath("$.access").doesNotExist());
        mvc.perform(get("/api/email-sync-jobs/" + id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.analysis_failed_count").value(1));
        server.verify();
    }

    @Test
    void proxiesProcessingUpdatesAndReportsUnavailableOrMissingJobs() throws Exception {
        String syncPath = BASE + "/api/v1/email-sync-jobs/";
        String id = "452ec98e-b777-4e5b-91a9-14aef3e20a18";
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("{\"access\":\"a1\",\"refresh\":\"r1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAIL_CHANGES + "?cursor=confirmed&include_updates=true"))
            .andRespond(withSuccess("""
                {"items":[{"gmail_id":"same-card","imported_at":"2026-11-06T11:00:00Z",
                 "processing_status":"FAILED","effective_category":""}],
                 "change_cursor":"after-analysis","until_cursor":null}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(syncPath)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(syncPath + id + "/")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        var mvc = MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build();
        mvc.perform(get("/api/email-changes").param("cursor", "confirmed").param("include_updates", "true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].processing_status").value("FAILED"))
            .andExpect(jsonPath("$.items[0].effective_category").value(""));
        mvc.perform(post("/api/email-sync-jobs")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/email-sync-jobs/" + id)).andExpect(status().isNotFound());
        server.verify();
    }

    @Test
    void reportsInvalidCursorAndUnavailableDjangoThroughThePageEndpoint() throws Exception {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a1","refresh":"r1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAIL_PAGES + "?cursor="))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(EMAIL_PAGES))
            .andRespond(withException(new ConnectException("Connection refused")));

        var mvc = MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build();
        mvc.perform(get("/api/email-pages").param("cursor", ""))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/email-pages"))
            .andExpect(status().isServiceUnavailable());
        server.verify();
    }

    @Test
    void preservesRecruitmentFilterForOpportunities() {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a1","refresh":"r1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS + "?category=professional%20opportunities%20and%20recruitment"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertEquals(List.of(), new OpportunityService(emailService).listOpportunities());
        server.verify();
    }

    @Test
    void proxiesManualCategoryAndPreservesConflictStatus() throws Exception {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("{\"access\":\"a1\",\"refresh\":\"r1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS + "synthetic-1/manual-category/"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(HttpMethod.PATCH))
            .andExpect(header("Authorization", "Bearer a1"))
            .andExpect(content().json("{\"category\":\"event invitations\"}"))
            .andRespond(withStatus(HttpStatus.CONFLICT));

        MockMvcBuilders.standaloneSetup(new EmailController(emailService)).build()
            .perform(patch("/api/emails/synthetic-1/manual-category")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"event invitations\"}"))
            .andExpect(status().isConflict());
        server.verify();
    }

    @Test
    void renewsRejectedRefreshByLoggingInAndRepeatsEmailQueryOnce() {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a1","refresh":"r1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS)).andExpect(header("Authorization", "Bearer a1"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(REFRESH)).andExpect(content().json("""
            {"refresh":"r1"}
            """)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a2","refresh":"r2"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS)).andExpect(header("Authorization", "Bearer a2"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertEquals(HttpStatus.BAD_GATEWAY,
            assertThrows(ResponseStatusException.class, emailService::listEmails).getStatusCode());
        server.verify();
    }

    @Test
    void concurrentRejectionsShareRenewalAndPreserveOrRotateRefreshToken() throws Exception {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a1","refresh":"r1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(REFRESH)).andExpect(content().json("""
            {"refresh":"r1"}
            """)).andRespond(withSuccess("""
                {"access":"a2","refresh":"r2"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(REFRESH)).andExpect(content().json("""
            {"refresh":"r2"}
            """)).andRespond(withSuccess("""
                {"access":"a3"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(REFRESH)).andExpect(content().json("""
            {"refresh":"r2"}
            """)).andRespond(withSuccess("""
                {"access":"a4"}
                """, MediaType.APPLICATION_JSON));

        String oldAccess = authService.getAccessToken();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return authService.renewAfterUnauthorized(oldAccess);
            });
            var second = executor.submit(() -> {
                start.await();
                return authService.renewAfterUnauthorized(oldAccess);
            });
            start.countDown();
            assertEquals("a2", first.get(5, TimeUnit.SECONDS));
            assertEquals("a2", second.get(5, TimeUnit.SECONDS));
        }
        assertEquals("a3", authService.renewAfterUnauthorized("a2"));
        assertEquals("a4", authService.renewAfterUnauthorized("a3"));
        server.verify();
    }

    @Test
    void distinguishesEmptyListPermissionDenialAndUnavailableApi() {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a1","refresh":"r1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(EMAILS)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(List.of(), emailService.listEmails());
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class, emailService::listEmails).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class, emailService::listEmails).getStatusCode());
        server.verify();
    }

    @Test
    void rejectsIncompleteLoginWithoutCachingIt() {
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a1"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(LOGIN))
            .andRespond(withSuccess("""
                {"access":"a2","refresh":"r2"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMAILS)).andExpect(header("Authorization", "Bearer a2"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertEquals(HttpStatus.BAD_GATEWAY,
            assertThrows(ResponseStatusException.class, emailService::listEmails).getStatusCode());
        assertEquals(List.of(), emailService.listEmails());
        server.verify();
    }
}
