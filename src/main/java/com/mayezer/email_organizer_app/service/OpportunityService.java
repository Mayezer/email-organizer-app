package com.mayezer.email_organizer_app.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.mayezer.email_organizer_app.model.Opportunity;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class OpportunityService {

    private final EmailApiService emailApiService;

    public List<Opportunity> listOpportunities() {
        return emailApiService.list("professional opportunities and recruitment", Opportunity[].class);
    }
}
