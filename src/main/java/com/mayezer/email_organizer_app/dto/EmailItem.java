package com.mayezer.email_organizer_app.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmailItem(
    @JsonProperty("gmail_id") String gmailId,
    @JsonProperty("thread_id") String threadId,
    @JsonProperty("received_at") Instant receivedAt,
    String sender,
    String subject,
    @JsonProperty("processing_status") String processingStatus,
    String category,
    String summary,
    @JsonProperty("requires_action") Boolean requiresAction,
    @JsonProperty("security_status") String securityStatus,
    @JsonProperty("security_reason") String securityReason,
    @JsonProperty("has_attachments") Boolean hasAttachments,
    @JsonProperty("has_links") Boolean hasLinks
) {}
