package com.mayezer.email_organizer_app.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

@Configuration
public class AppSecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService appUsers(
        AppAccount account,
        @Value("${EMAIL_ORGANIZER_APP_USERNAME:}") String username,
        @Value("${EMAIL_ORGANIZER_APP_PASSWORD_HASH:}") String passwordHash
    ) {
        var users = new InMemoryUserDetailsManager();
        if (!account.gmailAddress().isBlank() && !username.isBlank() && !passwordHash.isBlank()) {
            users.createUser(User.withUsername(username.strip()).password(passwordHash.strip())
                .roles("MAILBOX_OWNER").build());
        }
        return users;
    }

    @Bean
    SecurityFilterChain appSecurity(HttpSecurity http) {
        var trustResolver = new AuthenticationTrustResolverImpl();
        return http
            .authorizeHttpRequests(requests -> requests
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/session").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/session/login").permitAll()
                .requestMatchers("/api/**", "/home").hasRole("MAILBOX_OWNER")
                .anyRequest().denyAll())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((request, response, failure) -> {
                    boolean authenticated = trustResolver.isAuthenticated(
                        SecurityContextHolder.getContext().getAuthentication());
                    response.setStatus(authenticated
                        ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_UNAUTHORIZED);
                }))
            .csrf(CsrfConfigurer::spa)
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(login -> login.loginPage("/").loginProcessingUrl("/api/session/login")
                .successHandler((request, response, authentication) ->
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                .failureHandler((request, response, failure) ->
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
            .logout(logout -> logout.logoutUrl("/api/session/logout")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
            .build();
    }
}
