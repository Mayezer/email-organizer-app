package com.mayezer.email_organizer_app.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.mayezer.email_organizer_app.config.AppAccount;
import com.mayezer.email_organizer_app.config.GmailReadClient;

@Service
public class EmailAttachmentService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_BASE64 = 4 * ((MAX_BYTES + 2) / 3);
    // Gmail wraps the base64url data in JSON. Bound the decompressed response before parsing it.
    private static final int MAX_GMAIL_RESPONSE = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_PER_MESSAGE = 20;
    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[\\p{Cc}\\p{Cf}<>:\"/\\\\|?*]");
    private final Semaphore downloads = new Semaphore(2);
    private final AppAccount account;
    private final GmailReadClient client;

    public EmailAttachmentService(AppAccount account, GmailReadClient client) {
        this.account = account;
        this.client = client;
    }

    static List<Attachment> describe(String gmailId, MessagePart payload) {
        return files(gmailId, payload).stream().map(FilePart::attachment).toList();
    }

    public void download(String gmailId, String fileId, DownloadTarget target) throws IOException {
        if (account.gmailAddress().isBlank() || !downloads.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            var file = retrieve(gmailId, fileId);
            target.write(file.filename(), file.bytes());
        } finally {
            downloads.release();
        }
    }

    private Download retrieve(String gmailId, String fileId) {
        try {
            String mailbox = account.gmailAddress();
            var messages = client.get().users().messages();
            var message = parse(messages.get(mailbox, gmailId).setFormat("full").setFields("id,payload")
                .executeUnparsed(), Message.class);
            if (!gmailId.equals(message.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            }
            var file = files(gmailId, message.getPayload()).stream()
                .filter(part -> part.attachment().id().equals(fileId))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            var body = file.body();
            checkSize(body.getSize());
            if (body.getAttachmentId() != null && !body.getAttachmentId().isBlank()) {
                body = parse(messages.attachments().get(mailbox, gmailId, body.getAttachmentId())
                    .setFields("size,data").executeUnparsed(), MessagePartBody.class);
            }
            return new Download(file.attachment().filename(), decode(body));
        } catch (GoogleJsonResponseException exception) {
            int status = exception.getStatusCode();
            throw new ResponseStatusException(status == 404 || status == 410
                ? HttpStatus.NOT_FOUND : HttpStatus.SERVICE_UNAVAILABLE);
        } catch (IOException | GeneralSecurityException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static <T> T parse(HttpResponse response, Class<T> type) throws IOException {
        try {
            var input = response.getContent();
            if (input == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            }
            byte[] json = input.readNBytes(MAX_GMAIL_RESPONSE + 1);
            if (json.length > MAX_GMAIL_RESPONSE) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            }
            return GsonFactory.getDefaultInstance()
                .fromInputStream(new ByteArrayInputStream(json), StandardCharsets.UTF_8, type);
        } finally {
            response.disconnect();
        }
    }

    private static byte[] decode(MessagePartBody body) {
        checkSize(body.getSize());
        String data = body.getData();
        if (data == null || data.isEmpty()) {
            if (Integer.valueOf(0).equals(body.getSize())) {
                return new byte[0];
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (data.length() > MAX_BASE64) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(data);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
        checkSize(bytes.length);
        if (body.getSize() != null && body.getSize() != bytes.length) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
        return bytes;
    }

    private static void checkSize(Integer size) {
        if (size != null && size > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        }
    }

    private static List<FilePart> files(String gmailId, MessagePart payload) {
        var parts = new ArrayList<FilePart>();
        collect(gmailId, payload, "0", 0, parts);
        return parts;
    }

    private static void collect(String gmailId, MessagePart part, String path, int depth, List<FilePart> files) {
        if (part == null || depth > MAX_DEPTH || files.size() >= MAX_PER_MESSAGE) {
            return;
        }
        if (EmailMimeParts.isAttachment(part)) {
            var body = part.getBody();
            if (body != null) {
                Integer size = body.getSize();
                files.add(new FilePart(new Attachment(gmailId, fileId(gmailId, path), filename(part.getFilename()),
                    size != null && size >= 0 ? size : null, size != null && size > MAX_BYTES), body));
            }
            return;
        }
        var children = part.getParts();
        if (children != null) {
            for (int i = 0; i < children.size() && files.size() < MAX_PER_MESSAGE; i++) {
                collect(gmailId, children.get(i), path + "." + i, depth + 1, files);
            }
        }
    }

    private static String fileId(String gmailId, String path) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((gmailId + ":" + path).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String filename(String original) {
        if (original == null || original.isBlank()) {
            return "anexo";
        }
        String name = UNSAFE_FILENAME.matcher(original).replaceAll("_").strip().replaceAll("^\\.+|\\.+$", "");
        if (name.isBlank()) {
            return "anexo";
        }
        if (name.codePointCount(0, name.length()) > 180) {
            return name.substring(0, name.offsetByCodePoints(0, 180));
        }
        return name;
    }

    @FunctionalInterface
    public interface DownloadTarget {
        void write(String filename, byte[] bytes) throws IOException;
    }

    public record Attachment(String gmailId, String id, String filename, Integer size, boolean tooLarge) {}

    private record FilePart(Attachment attachment, MessagePartBody body) {}
    private record Download(String filename, byte[] bytes) {}
}
