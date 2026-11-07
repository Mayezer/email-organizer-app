package com.mayezer.email_organizer_app.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mayezer.email_organizer_app.model.Opportunity;
import com.mayezer.email_organizer_app.service.OpportunityService;


@RestController
@RequestMapping("/api/opportunities")
public class OpportunityController {

    private OpportunityService opportunityService;

    public OpportunityController(OpportunityService opportunityService) {
        this.opportunityService = opportunityService;
    }

    @GetMapping
    public List<Opportunity> getOpportunity() {
        return opportunityService.listOpportunities();
    }
}
