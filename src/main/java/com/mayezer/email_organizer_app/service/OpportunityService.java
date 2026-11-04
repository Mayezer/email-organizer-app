package com.mayezer.email_organizer_app.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.mayezer.email_organizer_app.model.Opportunity;

@Service 
public class OpportunityService {

    public List<Opportunity> listOpportunities() {
        ArrayList<Opportunity> listOpportunitys = new ArrayList<>();
        Opportunity opportunity = new Opportunity("", "", "");

        listOpportunitys.add(opportunity);
        return listOpportunitys;
    }
}
