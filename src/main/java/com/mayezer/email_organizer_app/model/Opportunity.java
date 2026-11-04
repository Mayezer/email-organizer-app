package com.mayezer.email_organizer_app.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter 
@AllArgsConstructor 
public class Opportunity {

    private String sender;
    private String subject;
    private String summary;
}
