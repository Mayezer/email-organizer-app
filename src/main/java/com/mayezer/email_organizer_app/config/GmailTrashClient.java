package com.mayezer.email_organizer_app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;

@Component
public class GmailTrashClient {
    private static final String CREDENTIALS = "EMAIL_ORGANIZER_APP_GMAIL_TRASH_CREDENTIALS_PATH";
    private static final String TOKENS = "EMAIL_ORGANIZER_APP_GMAIL_TRASH_TOKENS_PATH";
    private static final String READ_TOKENS = "EMAIL_ORGANIZER_APP_GMAIL_TOKENS_PATH";
    private final String credentialsPath;
    private final String tokensPath;
    private final String readTokensPath;
    private Gmail gmail;

    public GmailTrashClient(
        @Value("${" + CREDENTIALS + ":}") String credentialsPath,
        @Value("${" + TOKENS + ":}") String tokensPath,
        @Value("${" + READ_TOKENS + ":}") String readTokensPath
    ) {
        this.credentialsPath = credentialsPath;
        this.tokensPath = tokensPath;
        this.readTokensPath = readTokensPath;
    }

    public boolean authorized() throws IOException {
        return !credentialsPath.isBlank() && !tokensPath.isBlank()
            && Files.isRegularFile(checkedTokenDirectory().resolve(StoredCredential.DEFAULT_DATA_STORE_ID));
    }

    public synchronized Gmail get() throws IOException, GeneralSecurityException {
        if (gmail == null) {
            gmail = GmailReadClient.load(credentialsPath, checkedTokenDirectory().toString());
        }
        return gmail;
    }

    private Path checkedTokenDirectory() throws IOException {
        if (tokensPath.isBlank()) {
            throw new IOException("Gmail trash token directory is missing");
        }
        Path directory = Path.of(tokensPath).toAbsolutePath().normalize();
        if (!readTokensPath.isBlank()) {
            Path read = Path.of(readTokensPath).toAbsolutePath().normalize();
            if (directory.equals(read) || (Files.exists(directory) && Files.exists(read)
                    && Files.isSameFile(directory, read))) {
                throw new IOException("Gmail trash requires a separate token directory");
            }
        }
        return directory;
    }

    public static void main(String[] args) throws IOException, GeneralSecurityException {
        var config = new GmailTrashClient(System.getenv().getOrDefault(CREDENTIALS, ""),
            System.getenv().getOrDefault(TOKENS, ""), System.getenv().getOrDefault(READ_TOKENS, ""));
        var account = new AppAccount(System.getenv().getOrDefault("EMAIL_ORGANIZER_APP_GMAIL_ACCOUNT", ""));
        if (config.credentialsPath.isBlank() || config.readTokensPath.isBlank() || account.gmailAddress().isBlank()) {
            throw new IllegalArgumentException("Configure the OAuth client, both token directories and the linked account");
        }
        Path directory = config.checkedTokenDirectory();
        var json = GsonFactory.getDefaultInstance();
        var transport = GoogleNetHttpTransport.newTrustedTransport();
        GoogleClientSecrets client;
        try (var reader = Files.newBufferedReader(Path.of(config.credentialsPath))) {
            client = GoogleClientSecrets.load(json, reader);
        }
        var flow = new GoogleAuthorizationCodeFlow.Builder(transport, json, client, List.of(GmailScopes.GMAIL_MODIFY))
            .setAccessType("offline").setApprovalPrompt("force")
            .setRequestInitializer(GmailReadClient::configureRequest).build();
        var receiver = new LocalServerReceiver.Builder().setHost("127.0.0.1").setPort(8889).build();
        var credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
        var gmail = new Gmail.Builder(transport, json, request -> {
            credential.initialize(request);
            GmailReadClient.configureRequest(request);
        }).setApplicationName("Email Organizer App").build();
        String authorizedAccount = gmail.users().getProfile("me").setFields("emailAddress").execute().getEmailAddress();
        if (!account.gmailAddress().equalsIgnoreCase(authorizedAccount) || credential.getRefreshToken() == null) {
            throw new IOException("Gmail did not authorize offline access to the linked account");
        }
        StoredCredential.getDefaultDataStore(new FileDataStoreFactory(directory.toFile()))
            .set("user", new StoredCredential(credential));
        System.out.println("Gmail trash authorization saved. Restart the App to use it.");
    }
}
