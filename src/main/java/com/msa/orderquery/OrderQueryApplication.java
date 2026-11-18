package com.msa.orderquery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Order Command와 별도 실행하며 주문 조회 모델과 Kafka 소비만 소유합니다. */
@SpringBootApplication
public class OrderQueryApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderQueryApplication.class, args);
    }
}
