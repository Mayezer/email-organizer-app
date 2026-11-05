package com.mayezer.email_organizer_app.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.mayezer.email_organizer_app.model.Opportunity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter 
@Setter 
@AllArgsConstructor 
@Service
public class OpportunityService {

    private final RestClient restClient;
    private final ApiAuthService apiAuthService;

    public List<Opportunity> listOpportunities() {
        String accessToken = apiAuthService.getAccessToken();
        Opportunity[] opportunities = restClient
            .get()
            .uri("/api/v1/emails/")
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .body(Opportunity[].class);

        return Arrays.asList(opportunities);
    }
}
