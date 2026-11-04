package com.mayezer.email_organizer_app.controller;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.mayezer.email_organizer_app.model.Opportunity;
import com.mayezer.email_organizer_app.service.OpportunityService;


@Controller
@RequestMapping("/home")
public class HomeController {

    private OpportunityService opportunityService;

    public HomeController(OpportunityService opportunityService) {
        this.opportunityService = opportunityService;
    }

    @GetMapping
    public String getHome(Model homeText) {
        List<Opportunity> opportunities = opportunityService.listOpportunities();

        homeText
        .addAttribute("homeTitle", "Email Organizer - My opportunities")
        .addAttribute("opportunities", opportunities);

        return "home";
    }
}
