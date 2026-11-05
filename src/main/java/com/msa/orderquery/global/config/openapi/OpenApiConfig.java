package com.msa.orderquery.global.config.openapi;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

import java.util.List;

/** 일반 Gateway의 주문 조회 API만 문서화하며 Command checkout이나 Saga API를 복제하지 않습니다. */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI orderQueryOpenApi(
            @Value("${query.openapi.gateway-base-url:http://localhost:8000}") String gateway) {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("STOCKDROP Order Query")
                                .version("v1")
                                .description(
                                        "구매자와 판매자의 본인 주문 및 비공개 문의 조회입니다. Kafka 반영 지연이 있을 수 있으며 결제"
                                            + " 확정은 Order Command에서 확인합니다."))
                .servers(
                        List.of(
                                new Server().url(gateway).description("일반 Gateway"),
                                new Server().url("http://localhost:8085").description("로컬 개발 서비스")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
