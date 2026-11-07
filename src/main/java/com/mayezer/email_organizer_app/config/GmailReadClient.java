package com.mayezer.email_organizer_app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleOAuthConstants;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;

@Component
public class GmailReadClient {
    private final String credentialsPath;
    private final String tokensPath;
    private Gmail gmail;

    public GmailReadClient(
        @Value("${EMAIL_ORGANIZER_APP_GMAIL_CREDENTIALS_PATH:}") String credentialsPath,
        @Value("${EMAIL_ORGANIZER_APP_GMAIL_TOKENS_PATH:}") String tokensPath
    ) {
        this.credentialsPath = credentialsPath;
        this.tokensPath = tokensPath;
    }

    public synchronized Gmail get() throws IOException, GeneralSecurityException {
        if (gmail == null) {
            gmail = load(credentialsPath, tokensPath);
        }
        return gmail;
    }

    static Gmail load(String credentialsPath, String tokensPath) throws IOException, GeneralSecurityException {
        if (credentialsPath.isBlank() || tokensPath.isBlank()) {
            throw new IOException("Gmail authorization is not configured");
        }
        var tokens = Path.of(tokensPath);
        if (!Files.isRegularFile(tokens.resolve(StoredCredential.DEFAULT_DATA_STORE_ID))) {
            throw new IOException("Gmail authorization is missing");
        }
        var stored = StoredCredential.getDefaultDataStore(new FileDataStoreFactory(tokens.toFile())).get("user");
        if (stored == null || stored.getRefreshToken() == null) {
            throw new IOException("Gmail authorization is missing");
        }

        var json = GsonFactory.getDefaultInstance();
        GoogleClientSecrets.Details client;
        try (var reader = Files.newBufferedReader(Path.of(credentialsPath))) {
            client = GoogleClientSecrets.load(json, reader).getDetails();
        }
        var transport = GoogleNetHttpTransport.newTrustedTransport();
        // Refresh in memory so the App and the sync worker do not write to the same token file.
        var credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
            .setTransport(transport)
            .setJsonFactory(json)
            .setTokenServerEncodedUrl(GoogleOAuthConstants.TOKEN_SERVER_URL)
            .setClientAuthentication(new ClientParametersAuthentication(client.getClientId(), client.getClientSecret()))
            .setRequestInitializer(GmailReadClient::configureRequest)
            .build()
            .setAccessToken(stored.getAccessToken())
            .setExpirationTimeMilliseconds(stored.getExpirationTimeMilliseconds())
            .setRefreshToken(stored.getRefreshToken());
        return new Gmail.Builder(transport, json, request -> {
            credential.initialize(request);
            configureRequest(request);
        }).setApplicationName("Email Organizer App").build();
    }

    static void configureRequest(HttpRequest request) {
        request.setConnectTimeout(2_000);
        request.setReadTimeout(5_000);
        request.setNumberOfRetries(1);
        request.setFollowRedirects(false);
        request.setLoggingEnabled(false);
        request.setCurlLoggingEnabled(false);
    }
}
