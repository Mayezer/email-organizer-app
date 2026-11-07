package com.mayezer.email_organizer_app.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmailConversation(@JsonProperty("gmail_ids") List<String> gmailIds, boolean partial) {}
