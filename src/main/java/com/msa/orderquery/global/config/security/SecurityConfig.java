package com.msa.orderquery.global.config.security;

import com.msa.orderquery.global.error.*;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Gateway와 독립적으로 JWT를 검증하고 명시한 GET 경로 외에는 공개하지 않습니다. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain security(
            HttpSecurity http, ObjectMapper mapper, Environment environment) throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .logout(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        c ->
                                c.authenticationEntryPoint(
                                                (req, res, ex) ->
                                                        error(
                                                                res,
                                                                CommonErrorCode.UNAUTHORIZED,
                                                                mapper))
                                        .accessDeniedHandler(
                                                (req, res, ex) ->
                                                        error(
                                                                res,
                                                                CommonErrorCode.FORBIDDEN,
                                                                mapper)))
                .authorizeHttpRequests(
                        c -> {
                            c.requestMatchers(
                                            HttpMethod.GET,
                                            "/actuator/health",
                                            "/actuator/health/liveness",
                                            "/actuator/health/readiness")
                                    .permitAll();
                            if (environment.acceptsProfiles(Profiles.of("local & !prod"))) {
                                c.requestMatchers(
                                                HttpMethod.GET,
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**")
                                        .permitAll();
                            }
                            c.requestMatchers(
                                            HttpMethod.GET,
                                            "/api/v1/orders",
                                            "/api/v1/orders/*",
                                            "/api/v1/orders/*/inquiries",
                                            "/api/v1/orders/*/inquiries/*")
                                    .hasRole("BUYER");
                            c.requestMatchers(
                                            HttpMethod.GET,
                                            "/api/v1/seller/orders",
                                            "/api/v1/seller/orders/*",
                                            "/api/v1/seller/orders/*/inquiries",
                                            "/api/v1/seller/orders/*/inquiries/*")
                                    .hasRole("SELLER");
                            c.anyRequest().denyAll();
                        })
                .oauth2ResourceServer(
                        c ->
                                c.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(
                                                (req, res, ex) ->
                                                        error(
                                                                res,
                                                                CommonErrorCode.UNAUTHORIZED,
                                                                mapper)))
                .build();
    }

    // Bearer 인증만 사용하며 사용자 헤더·쿠키·세션으로 인증을 우회하지 않습니다.
    private void error(HttpServletResponse response, ErrorCode code, ObjectMapper mapper)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (code == CommonErrorCode.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, MDC.get("traceId")));
    }
}
