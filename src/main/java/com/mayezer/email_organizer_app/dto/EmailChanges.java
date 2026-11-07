package com.mayezer.email_organizer_app.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmailChanges(
    List<EmailMetadata> items,
    @JsonProperty("change_cursor") String changeCursor,
    @JsonProperty("until_cursor") String untilCursor
) {}
