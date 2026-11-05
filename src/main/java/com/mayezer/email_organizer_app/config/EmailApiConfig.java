package com.mayezer.email_organizer_app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

@Configuration
public class EmailApiConfig {

    @Bean
    public RestClient emailApiClient(RestClient.Builder builder) {
        return builder.baseUrl("http://localhost:8000").bufferContent((uri, method) -> method.equals(HttpMethod.POST)).build();
    }
}
