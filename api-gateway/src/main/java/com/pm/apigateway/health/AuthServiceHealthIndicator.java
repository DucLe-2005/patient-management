package com.pm.apigateway.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class AuthServiceHealthIndicator implements ReactiveHealthIndicator {
    private final WebClient webClient;

    public AuthServiceHealthIndicator(
            WebClient.Builder webClientBuilder,
            @Value("${AUTH_SERVICE_ROUTE_URL}") String authServiceUrl
    ) {
        this.webClient = webClientBuilder
                .baseUrl(authServiceUrl)
                .build();
    }

    @Override
    public Mono<Health> health() {
        return webClient.get()
                .uri("/actuator/health/readiness")
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .map(response ->
                        Health.up()
                                .withDetail("service", "auth-service")
                                .withDetail("readinessEndpoint", "/actuator/health/readiness")
                                .build()
                )
                .onErrorResume(error ->
                        Mono.just(
                                Health.down(error)
                                        .withDetail("service", "auth-service")
                                        .withDetail("readinessEndpoint", "/actuator/health/readiness")
                                        .build()
                        )
                );
    }
}
