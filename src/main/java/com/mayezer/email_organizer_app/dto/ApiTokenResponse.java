package com.mayezer.email_organizer_app.dto;

import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter 
@NoArgsConstructor 
public class ApiTokenResponse {

    private String access;
    private String refresh;
}
