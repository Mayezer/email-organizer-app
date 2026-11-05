package com.mayezer.email_organizer_app.service;

import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.mayezer.email_organizer_app.dto.ApiTokenResponse;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor 
@Service
public class ApiAuthService {

    private final RestClient restClient;
    private ApiTokenResponse tokens;
    private final Environment environment;

    private void authenticate() {
        String username = environment.getRequiredProperty("EMAIL_ORGANIZER_API_USERNAME");
        String password = environment.getRequiredProperty("EMAIL_ORGANIZER_API_PASSWORD");

        Map<String, String> loginRequest = Map.of("username", username, "password", password);

        tokens = restClient
            .post()
            .uri("/api/v1/authentication/token/")
            .contentType(MediaType.APPLICATION_JSON)
            .body(loginRequest)
            .retrieve()
            .body(ApiTokenResponse.class);
    }

    public String getAccessToken() {
        if (tokens == null) {
            authenticate();
        }
        return tokens.getAccess();
    }

    public void refreshAccessToken() {
        if (tokens == null) {
            authenticate();
            return;
        }
        Map<String, String> refreshRequest = Map.of("refresh", tokens.getRefresh());
        ApiTokenResponse renewedTokens = restClient
            .post()
            .uri("/api/v1/authentication/token/refresh/")
            .contentType(MediaType.APPLICATION_JSON)
            .body(refreshRequest)
            .retrieve()
            .body(ApiTokenResponse.class);

        String newAcess = renewedTokens.getAccess();
        tokens.setAccess(newAcess);
    }
}
