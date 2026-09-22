package com.pm.patientservice.health;

import com.pm.patientservice.grpc.BillingServiceGrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillingServiceHealthIndicatorTest {

    private final BillingServiceGrpcClient billingClient = mock(BillingServiceGrpcClient.class);
    private final BillingServiceHealthIndicator indicator = new BillingServiceHealthIndicator(billingClient);

    @Test
    void reportsUpWhenBillingServiceIsServing() {
        when(billingClient.isReady()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("service", "billing-service");
    }

    @Test
    void reportsDownWhenBillingServiceIsNotServing() {
        when(billingClient.isReady()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails())
                .containsEntry("service", "billing-service")
                .containsEntry("reason", "gRPC health service is not serving");
    }

    @Test
    void reportsDownWhenBillingHealthCheckFails() {
        when(billingClient.isReady()).thenThrow(new IllegalStateException("connection refused"));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("service", "billing-service");
        assertThat(health.getDetails().get("error").toString()).contains("connection refused");
    }
}
