package com.mayezer.email_organizer_app.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmailPage(
    List<EmailMetadata> items,
    @JsonProperty("next_cursor") String nextCursor,
    @JsonProperty("change_cursor") String changeCursor
) {}
