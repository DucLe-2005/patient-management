package com.pm.patientservice.health;

import com.pm.patientservice.grpc.BillingServiceGrpcClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class BillingServiceHealthIndicator implements HealthIndicator {
    private final BillingServiceGrpcClient billingServiceGrpcClient;

    public BillingServiceHealthIndicator(BillingServiceGrpcClient billingServiceGrpcClient) {
        this.billingServiceGrpcClient = billingServiceGrpcClient;
    }

    @Override
    public Health health() {
        try {
            if (billingServiceGrpcClient.isReady()) {
                return Health.up()
                        .withDetail("service", "billing-service")
                        .withDetail("protocol", "gRPC")
                        .build();
            }

            return Health.down()
                    .withDetail("service", "billing-service")
                    .withDetail("reason", "gRPC health service is not serving")
                    .build();
        }
        catch (RuntimeException error) {
            return Health.down(error)
                    .withDetail("service", "billing-service")
                    .build();
        }
    }
}
