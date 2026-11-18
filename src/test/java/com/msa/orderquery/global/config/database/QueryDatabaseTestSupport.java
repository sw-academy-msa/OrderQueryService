package com.msa.orderquery.global.config.database;

import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 개발 DB를 읽지 않으며 실제 PostgreSQL의 트랜잭션과 잠금을 검증합니다. */
public abstract class QueryDatabaseTestSupport {
    protected static final PostgreSQLContainer DB =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("orders_query_test")
                    .withInitScript("db/query-schema.sql");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }
}
