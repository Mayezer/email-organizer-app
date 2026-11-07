package com.mayezer.email_organizer_app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

@Configuration
public class EmailApiConfig {

    @Bean
    public RestClient emailApiClient(
        RestClient.Builder builder,
        @Value("${email-organizer.api.base-url:http://localhost:8000}") String baseUrl
    ) {
        return builder.baseUrl(baseUrl)
            .bufferContent((uri, method) -> method.equals(HttpMethod.POST) || method.equals(HttpMethod.PATCH))
            .build();
    }
}
