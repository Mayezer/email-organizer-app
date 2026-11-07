package com.mayezer.email_organizer_app.service;

import static com.mayezer.email_organizer_app.service.EmailMimeParts.header;
import static com.mayezer.email_organizer_app.service.EmailMimeParts.isAttachment;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.TextNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.MessagePart;
import com.mayezer.email_organizer_app.config.AppAccount;
import com.mayezer.email_organizer_app.config.GmailReadClient;
import com.mayezer.email_organizer_app.dto.EmailConversation;
import com.mayezer.email_organizer_app.service.EmailAttachmentService.Attachment;

@Service
public class EmailOriginalService {
    private static final String PLAIN = "text/plain";
    private static final String HTML = "text/html";
    private static final String AMP = "text/x-amp-html";
    private static final int MAX_TEXT_BYTES = 1_048_576;
    private static final Pattern CHARSET = Pattern.compile("(?i);\\s*charset\\s*=\\s*\"?([^;\"\\s]+)");
    private static final Pattern QUOTE_PREFIX = Pattern.compile("^(?:\\s*>)+\\s*");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Zs}]+");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\n(?:[\\t ]*\n){2,}");
    private final Semaphore reads = new Semaphore(3);
    private final AppAccount account;
    private final GmailReadClient client;

    public EmailOriginalService(AppAccount account, GmailReadClient client) {
        this.account = account;
        this.client = client;
    }

    public OriginalMessage read(EmailConversation conversation) {
        if (account.gmailAddress().isBlank() || !reads.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            String mailbox = account.gmailAddress();
            var messages = client.get().users().messages();
            var budget = new ReadingBudget();
            var texts = new ArrayList<String>();
            var links = new EmailOriginalLinks();
            var attachments = new ArrayList<Attachment>();
            boolean partial = conversation.partial();
            boolean unreadable = false;
            for (String gmailId : conversation.gmailIds()) {
                try {
                    var message = messages.get(mailbox, gmailId).setFormat("full").setFields("id,payload").execute();
                    if (!gmailId.equals(message.getId())) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
                    }
                    var files = EmailAttachmentService.describe(gmailId, message.getPayload());
                    attachments.addAll(files);
                    var body = new MessageBody(messages, mailbox, gmailId, budget);
                    String text = body.readPart(message.getPayload(), 0);
                    if (text.isBlank() && body.links.isEmpty() && files.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
                    }
                    if (!text.isBlank()) {
                        texts.add(text);
                    }
                    links.include(body.links);
                } catch (GoogleJsonResponseException exception) {
                    int status = exception.getStatusCode();
                    if (status != 404 && status != 410) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
                    }
                    partial = true;
                } catch (ResponseStatusException exception) {
                    if (exception.getStatusCode() != HttpStatus.UNPROCESSABLE_CONTENT) {
                        throw exception;
                    }
                    partial = true;
                    unreadable = true;
                }
            }
            if (texts.isEmpty() && links.isEmpty() && attachments.isEmpty()) {
                throw new ResponseStatusException(unreadable ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.NOT_FOUND);
            }
            return new OriginalMessage(mergeTexts(texts), partial, links.values(), List.copyOf(attachments));
        } catch (IOException | GeneralSecurityException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            reads.release();
        }
    }

    public record OriginalMessage(String text, boolean partial, List<String> links, List<Attachment> attachments) {}

    private static final class ReadingBudget {
        private int remainingBytes = MAX_TEXT_BYTES;
        private int remainingExternalParts = 2;
    }

    private static final class MessageBody {
        private final Gmail.Users.Messages messages;
        private final String mailbox;
        private final String gmailId;
        private final ReadingBudget budget;
        private final EmailOriginalLinks links = new EmailOriginalLinks();

        MessageBody(Gmail.Users.Messages messages, String mailbox, String gmailId, ReadingBudget budget) {
            this.messages = messages;
            this.mailbox = mailbox;
            this.gmailId = gmailId;
            this.budget = budget;
        }

        String readPart(MessagePart part, int depth) throws IOException {
            if (part == null || depth > 32 || isAttachment(part)) {
                return "";
            }
            String mime = part.getMimeType() == null ? "" : part.getMimeType().toLowerCase(Locale.ROOT);
            if (PLAIN.equals(mime) || HTML.equals(mime) || AMP.equals(mime)) {
                String text = decodeText(part);
                if (PLAIN.equals(mime)) {
                    links.addText(text);
                    return text.strip();
                }
                return htmlToText(text, links);
            }
            if (!mime.startsWith("multipart/") || part.getParts() == null) {
                return "";
            }
            var parts = part.getParts();
            boolean alternative = "multipart/alternative".equals(mime);
            if (alternative) {
                parts = parts.stream().filter(Objects::nonNull)
                    .sorted(Comparator.comparing(child -> !PLAIN.equalsIgnoreCase(child.getMimeType())))
                    .toList();
            }
            var texts = new ArrayList<String>();
            for (var child : parts) {
                String text = readPart(child, depth + 1);
                if (!text.isBlank()) {
                    texts.add(text);
                }
            }
            return alternative ? mergeTexts(texts) : String.join("\n\n", texts);
        }

        private String decodeText(MessagePart part) throws IOException {
            var body = part.getBody();
            if (body == null) {
                return "";
            }
            String data = body.getData();
            if ((data == null || data.isEmpty()) && body.getAttachmentId() != null) {
                if (budget.remainingExternalParts == 0 || body.getSize() == null || body.getSize() > budget.remainingBytes) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
                }
                budget.remainingExternalParts--;
                data = messages.attachments().get(mailbox, gmailId, body.getAttachmentId())
                    .setFields("data").execute().getData();
            }
            if (data == null || data.isEmpty()) {
                return "";
            }
            if (data.length() > 4 * ((budget.remainingBytes + 2) / 3)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
            }
            byte[] decoded;
            try {
                decoded = Base64.getUrlDecoder().decode(data);
            } catch (IllegalArgumentException exception) {
                return "";
            }
            if (decoded.length > budget.remainingBytes) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
            }
            budget.remainingBytes -= decoded.length;
            var charset = CHARSET.matcher(header(part, "Content-Type"));
            if (charset.find()) {
                try {
                    return new String(decoded, Charset.forName(charset.group(1)));
                } catch (IllegalArgumentException unsupportedCharset) {
                    // Some messages declare an invalid charset.
                }
            }
            return new String(decoded, StandardCharsets.UTF_8);
        }
    }

    private static String mergeTexts(List<String> texts) {
        if (texts.size() <= 1) {
            return texts.isEmpty() ? "" : texts.getFirst();
        }
        var seenTexts = new HashSet<String>();
        var seenLines = new HashSet<String>();
        var uniqueTexts = new ArrayList<String>();
        for (String text : texts) {
            var lines = text.lines().toList();
            var keys = lines.stream().map(EmailOriginalService::comparisonKey).toList();
            String fullKey = WHITESPACE.matcher(String.join(" ", keys)).replaceAll(" ").strip();
            if (!seenTexts.add(fullKey)) {
                continue;
            }
            var uniqueLines = new ArrayList<String>();
            for (int index = 0; index < lines.size(); index++) {
                if (!seenLines.contains(keys.get(index))) {
                    uniqueLines.add(lines.get(index));
                }
            }
            seenLines.addAll(keys.stream().filter(key -> !key.isEmpty()).toList());
            String uniqueText = String.join("\n", uniqueLines).strip();
            if (uniqueLines.size() < lines.size()) {
                uniqueText = EXTRA_BLANK_LINES.matcher(uniqueText).replaceAll("\n\n");
            }
            if (!uniqueText.isEmpty()) {
                uniqueTexts.add(uniqueText);
            }
        }
        return String.join("\n\n", uniqueTexts);
    }

    private static String comparisonKey(String line) {
        return WHITESPACE.matcher(QUOTE_PREFIX.matcher(line).replaceFirst("")).replaceAll(" ").strip();
    }

    private static String htmlToText(String html, EmailOriginalLinks links) {
        var body = Jsoup.parse(html).body();
        body.select("script, style, iframe, object, embed, template, svg, noscript, [hidden]").remove();
        for (var element : body.select("p, div, li, h1, h2, h3, h4, h5, h6, blockquote, tr")) {
            element.before(new TextNode("\n"));
            element.after(new TextNode("\n"));
        }
        String text = body.wholeText().replaceAll("[\\t \\x0B\\f\\r]+", " ")
            .replaceAll(" *\\n[ \\n]*", "\n").strip();
        for (var anchor : body.select("a[href], area[href]")) {
            links.add(anchor.attr("href").strip());
            // A label can display a different address from its href.
            anchor.replaceWith(new TextNode(" "));
        }
        links.addText(body.wholeText());
        return text;
    }
}
