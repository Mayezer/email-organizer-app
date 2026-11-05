package com.mayezer.email_organizer_app.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter 
@NoArgsConstructor 
public class Opportunity {

    private String sender;
    private String subject;
    private String summary;
}
