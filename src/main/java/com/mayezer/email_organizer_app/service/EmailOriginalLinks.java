package com.mayezer.email_organizer_app.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

final class EmailOriginalLinks {
    private static final int MAX_LINKS = 100;
    private static final int MAX_LENGTH = 4096;
    private static final Pattern TEXT_LINK = Pattern.compile(
        "(?i)(?<![^\\s\\p{Z}])[<(\\[{'\"“‘>]*(https?://[^\\s\\p{Z}<>\"“”‘’]+)");
    private static final Pattern INVALID_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Z}]");
    private final LinkedHashSet<String> destinations = new LinkedHashSet<>();

    void addText(String text) {
        var links = TEXT_LINK.matcher(text);
        while (destinations.size() < MAX_LINKS && links.find()) {
            if (links.end(1) - links.start(1) <= MAX_LENGTH) {
                char before = links.start(1) == 0 ? '\0' : text.charAt(links.start(1) - 1);
                add(trimProse(links.group(1), before));
            }
        }
    }

    void add(String destination) {
        if (destinations.size() >= MAX_LINKS || destination.length() > MAX_LENGTH
            || INVALID_CHARACTERS.matcher(destination).find()) {
            return;
        }
        try {
            var url = new URI(destination);
            if (("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                && url.getHost() != null && url.getRawUserInfo() == null
                && url.getPort() <= 65535 && !url.getRawAuthority().endsWith(":")) {
                destinations.add(destination);
            }
        } catch (URISyntaxException ignored) {
            // Invalid destinations remain part of the inert message text.
        }
    }

    void include(EmailOriginalLinks links) {
        for (String destination : links.destinations) {
            if (destinations.size() >= MAX_LINKS) {
                break;
            }
            destinations.add(destination);
        }
    }

    List<String> values() {
        return List.copyOf(destinations);
    }

    boolean isEmpty() {
        return destinations.isEmpty();
    }

    private static String trimProse(String candidate, char before) {
        int parentheses = 0;
        int brackets = 0;
        for (int i = 0; i < candidate.length(); i++) {
            switch (candidate.charAt(i)) {
                case '(' -> parentheses++;
                case ')' -> parentheses--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                default -> {}
            }
        }
        int end = candidate.length();
        while (end > 0) {
            char last = candidate.charAt(end - 1);
            if (last == ')' && before == '(' && parentheses < 0) {
                parentheses++;
            } else if (last == ']' && before == '[' && brackets < 0) {
                brackets++;
            } else if (".,;!".indexOf(last) < 0 && !(before == '\'' && last == '\'')) {
                break;
            }
            end--;
        }
        return candidate.substring(0, end);
    }
}
