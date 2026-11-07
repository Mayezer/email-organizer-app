package com.mayezer.email_organizer_app.service;

import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.mayezer.email_organizer_app.dto.ApiTokenResponse;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class ApiAuthService {

    private final RestClient restClient;
    private final Environment environment;
    private ApiTokenResponse tokens;

    public synchronized String getAccessToken() {
        if (tokens == null) {
            tokens = authenticate();
        }
        return tokens.getAccess();
    }

    public synchronized String renewAfterUnauthorized(String rejectedAccessToken) {
        if (tokens == null || !tokens.getAccess().equals(rejectedAccessToken)) {
            return getAccessToken();
        }

        String previousRefresh = tokens.getRefresh();
        try {
            ApiTokenResponse renewed = postTokens("/api/v1/authentication/token/refresh/",
                Map.of("refresh", previousRefresh));
            if (!StringUtils.hasText(renewed.getRefresh())) {
                renewed.setRefresh(previousRefresh);
            }
            tokens = renewed;
        } catch (HttpClientErrorException.Unauthorized exception) {
            tokens = null;
            tokens = authenticate();
        }
        return tokens.getAccess();
    }

    private ApiTokenResponse authenticate() {
        String username = environment.getProperty("EMAIL_ORGANIZER_API_USERNAME");
        String password = environment.getProperty("EMAIL_ORGANIZER_API_PASSWORD");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API credentials are missing");
        }

        ApiTokenResponse authenticated = postTokens("/api/v1/authentication/token/",
            Map.of("username", username, "password", password));
        if (!StringUtils.hasText(authenticated.getRefresh())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API did not return a refresh token");
        }
        return authenticated;
    }

    private ApiTokenResponse postTokens(String path, Map<String, String> body) {
        ApiTokenResponse response = restClient.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(ApiTokenResponse.class);

        if (response == null || !StringUtils.hasText(response.getAccess())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Email API did not return an access token");
        }
        return response;
    }
}
