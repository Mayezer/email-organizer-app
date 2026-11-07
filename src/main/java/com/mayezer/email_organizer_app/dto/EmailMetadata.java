package com.mayezer.email_organizer_app.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmailMetadata(
    @JsonProperty("gmail_id") String gmailId,
    @JsonProperty("thread_id") String threadId,
    String sender,
    String subject,
    @JsonProperty("received_at") Instant receivedAt,
    @JsonProperty("imported_at") Instant importedAt,
    @JsonProperty("processing_status") String processingStatus,
    @JsonProperty("effective_category") String effectiveCategory
) {}
