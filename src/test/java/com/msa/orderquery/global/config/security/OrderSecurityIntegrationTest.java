package com.msa.orderquery.global.config.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.orderquery.global.config.database.QueryDatabaseTestSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;

/** 직접 권한을 주입하지 않고 실제 서명 Bearer가 JWKS와 roles 변환기를 통과하는지 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderSecurityIntegrationTest extends QueryDatabaseTestSupport {
    static final JwtTestIssuer ISSUER = new JwtTestIssuer();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry properties) {
        properties.add("query.auth.jwk-set-uri", ISSUER::uri);
    }

    @AfterAll
    static void closeIssuer() {
        ISSUER.close();
    }

    @Autowired MockMvc mvc;
    @Autowired Environment environment;

    private JwtClaimsSet.Builder claims(String role) {
        var now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(environment.getRequiredProperty("query.auth.issuer"))
                .audience(List.of(environment.getRequiredProperty("query.auth.audience")))
                .subject(UUID.randomUUID().toString())
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(300))
                .claim("roles", List.of(role))
                .claim("token_use", "access");
    }

    @Test
    void signedBuyerAndSellerRolesUseCorrectReadPaths() throws Exception {
        String buyer = ISSUER.token(claims("BUYER").build()),
                seller = ISSUER.token(claims("SELLER").build());
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + seller))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/seller/orders").header("Authorization", "Bearer " + seller))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/seller/orders").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidClaimsAndUntrustedUserHeaderDoNotAuthenticate() throws Exception {
        for (var invalid :
                List.of(
                        claims("BUYER").issuer("https://wrong.test"),
                        claims("BUYER").audience(List.of("wrong")),
                        claims("BUYER").claim("token_use", "refresh"),
                        claims("BUYER").claim("principal_type", "service"))) {
            mvc.perform(
                            get("/api/v1/orders")
                                    .header(
                                            "Authorization",
                                            "Bearer " + ISSUER.token(invalid.build())))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/orders").header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }
}
