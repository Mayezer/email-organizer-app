package com.mayezer.email_organizer_app.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

public record EmailMetadata(
    @JsonProperty("gmail_id") String gmailId,
    @JsonProperty("thread_id") String threadId,
    String sender,
    String subject,
    @JsonProperty("received_at") Instant receivedAt,
    @JsonProperty("imported_at") Instant importedAt,
    @JsonProperty("processing_status") String processingStatus,
    @JsonProperty("effective_category") String effectiveCategory,
    @JsonProperty("board_column") String boardColumn,
    @JsonProperty("update_revision") String updateRevision,
    @JsonProperty("has_attachments") Boolean hasAttachments,
    @JsonProperty("has_links") Boolean hasLinks,
    @JsonInclude(Include.NON_NULL) @JsonProperty("trashed_at") Instant trashedAt
) {}
