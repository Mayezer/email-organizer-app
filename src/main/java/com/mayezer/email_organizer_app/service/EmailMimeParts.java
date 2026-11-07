package com.mayezer.email_organizer_app.service;

import com.google.api.services.gmail.model.MessagePart;

final class EmailMimeParts {
    private EmailMimeParts() {}

    static boolean isAttachment(MessagePart part) {
        return (part.getFilename() != null && !part.getFilename().isBlank())
            || "attachment".equalsIgnoreCase(header(part, "Content-Disposition").split(";", 2)[0].strip());
    }

    static String header(MessagePart part, String name) {
        if (part.getHeaders() != null) {
            for (var header : part.getHeaders()) {
                if (header != null && name.equalsIgnoreCase(header.getName()) && header.getValue() != null) {
                    return header.getValue();
                }
            }
        }
        return "";
    }
}
