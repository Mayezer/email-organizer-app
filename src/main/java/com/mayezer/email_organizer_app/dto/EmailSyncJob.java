package com.mayezer.email_organizer_app.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmailSyncJob(
    UUID id,
    String status,
    @JsonProperty("imported_count") long importedCount,
    @JsonProperty("analyzed_count") long analyzedCount,
    @JsonProperty("analysis_failed_count") long analysisFailedCount,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt
) {}
