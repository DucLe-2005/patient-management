package com.pm.stack;

import software.amazon.awscdk.App;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnParameter;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Token;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.CloudMapOptions;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinition;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.Secret;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;

import java.util.List;
import java.util.Map;

/**
 * Low-cost AWS development environment.
 *
 * Deploy it once with DesiredCount=0, push the images printed as stack outputs,
 * then update with DesiredCount=1. Analytics/MSK is intentionally omitted: a
 * provisioned MSK cluster is disproportionally expensive for a dev smoke test.
 */
public class DevStack extends Stack {
    private static final String NAMESPACE = "patient-management.local";
    private static final int TASK_CPU = 256;
    private static final int TASK_MEMORY_MIB = 512;

    public DevStack(App scope, String id, StackProps props) {
        super(scope, id, props);

        CfnParameter desiredCount = CfnParameter.Builder.create(this, "DesiredCount")
                .type("Number")
                .defaultValue("0")
                .description("Set to 1 only after every dev image has been pushed to ECR.")
                .build();

        // Public task ENIs avoid NAT gateway charges. Security groups still prevent
        // unsolicited traffic; only the ALB can call the API gateway.
        Vpc vpc = Vpc.Builder.create(this, "DevVpc")
                .maxAzs(2)
                .natGateways(0)
                .build();

        Cluster cluster = Cluster.Builder.create(this, "DevCluster")
                .vpc(vpc)
                .defaultCloudMapNamespace(software.amazon.awscdk.services.ecs.CloudMapNamespaceOptions.builder()
                        .name(NAMESPACE)
                        .build())
                .build();

        Repository authRepository = repository("AuthRepository", "patient-management-dev/auth-service");
        Repository patientRepository = repository("PatientRepository", "patient-management-dev/patient-service");
        Repository billingRepository = repository("BillingRepository", "patient-management-dev/billing-service");
        Repository gatewayRepository = repository("GatewayRepository", "patient-management-dev/api-gateway");

        DatabaseInstance authDatabase = database(vpc, "AuthDatabase", "auth_service");
        DatabaseInstance patientDatabase = database(vpc, "PatientDatabase", "patient_service");
        software.amazon.awscdk.services.secretsmanager.Secret jwtSecret =
                software.amazon.awscdk.services.secretsmanager.Secret.Builder.create(this, "JwtSecret")
                        .generateSecretString(SecretStringGenerator.builder()
                                .excludePunctuation(true)
                                .passwordLength(64)
                                .build())
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .build();

        FargateService billingService = service(
                cluster, "BillingService", "billing-service", billingRepository, List.of(4001, 9001),
                Token.asNumber(desiredCount.getValueAsString()), Map.of(), Map.of());

        FargateService authService = service(
                cluster, "AuthService", "auth-service", authRepository, List.of(4005),
                Token.asNumber(desiredCount.getValueAsString()), databaseEnvironment(authDatabase, "auth_service"), mergeSecrets(
                        databaseSecrets(authDatabase),
                        Map.of("JWT_SECRET", Secret.fromSecretsManager(jwtSecret))));

        FargateService patientService = service(
                cluster, "PatientService", "patient-service", patientRepository, List.of(4000),
                Token.asNumber(desiredCount.getValueAsString()), merge(
                        databaseEnvironment(patientDatabase, "patient_service"),
                        Map.of(
                                "BILLING_SERVICE_ADDRESS", "billing-service." + NAMESPACE,
                                "BILLING_SERVICE_GRPC_PORT", "9001"
                        )),
                databaseSecrets(patientDatabase));

        ApplicationLoadBalancedFargateService apiGateway = ApplicationLoadBalancedFargateService.Builder
                .create(this, "ApiGatewayService")
                .cluster(cluster)
                .serviceName("api-gateway")
                .taskDefinition(taskDefinition("ApiGatewayTask", "api-gateway", gatewayRepository, List.of(4004), Map.of(
                        "AUTH_SERVICE_URL", "http://auth-service." + NAMESPACE + ":4005",
                        "AUTH_SERVICE_ROUTE_URL", "http://auth-service." + NAMESPACE + ":4005",
                        "PATIENT_SERVICE_ROUTE_URL", "http://patient-service." + NAMESPACE + ":4000"
                ), Map.of()))
                .desiredCount(Token.asNumber(desiredCount.getValueAsString()))
                .assignPublicIp(true)
                .taskSubnets(software.amazon.awscdk.services.ec2.SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .publicLoadBalancer(true)
                .build();

        authDatabase.getConnections().allowDefaultPortFrom(authService, "Auth service database access");
        patientDatabase.getConnections().allowDefaultPortFrom(patientService, "Patient service database access");
        billingService.getConnections().allowFrom(patientService, Port.tcp(9001), "Patient service gRPC access");
        authService.getConnections().allowFrom(apiGateway.getService(), Port.tcp(4005), "Gateway authentication access");
        patientService.getConnections().allowFrom(apiGateway.getService(), Port.tcp(4000), "Gateway patient API access");

        output("ApiUrl", "http://" + apiGateway.getLoadBalancer().getLoadBalancerDnsName());
        output("AuthRepositoryUri", authRepository.getRepositoryUri());
        output("PatientRepositoryUri", patientRepository.getRepositoryUri());
        output("BillingRepositoryUri", billingRepository.getRepositoryUri());
        output("GatewayRepositoryUri", gatewayRepository.getRepositoryUri());
    }

    private Repository repository(String id, String repositoryName) {
        return Repository.Builder.create(this, id)
                .repositoryName(repositoryName)
                .removalPolicy(RemovalPolicy.DESTROY)
                .emptyOnDelete(true)
                .build();
    }

    private DatabaseInstance database(Vpc vpc, String id, String databaseName) {
        return DatabaseInstance.Builder.create(this, id)
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder()
                        .version(PostgresEngineVersion.VER_16_4)
                        .build()))
                .vpc(vpc)
                .vpcSubnets(software.amazon.awscdk.services.ec2.SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_ISOLATED)
                        .build())
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .allocatedStorage(20)
                .databaseName(databaseName)
                .credentials(Credentials.fromGeneratedSecret("app_user"))
                .publiclyAccessible(false)
                .deletionProtection(false)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    private FargateService service(
            Cluster cluster,
            String id,
            String serviceName,
            Repository repository,
            List<Integer> ports,
            Number desiredCount,
            Map<String, String> environment,
            Map<String, Secret> secrets) {

        return FargateService.Builder.create(this, id)
                .cluster(cluster)
                .serviceName(serviceName)
                .taskDefinition(taskDefinition(id + "Task", serviceName, repository, ports, environment, secrets))
                .desiredCount(desiredCount)
                .assignPublicIp(true)
                .vpcSubnets(software.amazon.awscdk.services.ec2.SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .cloudMapOptions(CloudMapOptions.builder().name(serviceName).build())
                .build();
    }

    private FargateTaskDefinition taskDefinition(
            String id,
            String serviceName,
            Repository repository,
            List<Integer> ports,
            Map<String, String> environment,
            Map<String, Secret> secrets) {

        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder.create(this, id)
                .cpu(TASK_CPU)
                .memoryLimitMiB(TASK_MEMORY_MIB)
                .build();

        ContainerDefinition container = taskDefinition.addContainer(serviceName + "Container",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromEcrRepository(repository, "dev"))
                        .environment(environment)
                        .secrets(secrets)
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .streamPrefix(serviceName)
                                .logGroup(LogGroup.Builder.create(this, id + "Logs")
                                        .retention(RetentionDays.ONE_DAY)
                                        .removalPolicy(RemovalPolicy.DESTROY)
                                        .build())
                                .build()))
                        .build());

        ports.forEach(port -> container.addPortMappings(PortMapping.builder()
                .containerPort(port)
                .protocol(Protocol.TCP)
                .build()));

        return taskDefinition;
    }

    private Map<String, String> databaseEnvironment(DatabaseInstance database, String databaseName) {
        return Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://%s:%s/%s".formatted(
                        database.getDbInstanceEndpointAddress(),
                        database.getDbInstanceEndpointPort(),
                        databaseName),
                "SPRING_DATASOURCE_USERNAME", "app_user"
        );
    }

    private Map<String, Secret> databaseSecrets(DatabaseInstance database) {
        return Map.of("SPRING_DATASOURCE_PASSWORD",
                Secret.fromSecretsManager(database.getSecret(), "password"));
    }

    private Map<String, Secret> mergeSecrets(Map<String, Secret> first, Map<String, Secret> second) {
        java.util.HashMap<String, Secret> result = new java.util.HashMap<>(first);
        result.putAll(second);
        return result;
    }

    private Map<String, String> merge(Map<String, String> first, Map<String, String> second) {
        java.util.HashMap<String, String> result = new java.util.HashMap<>(first);
        result.putAll(second);
        return result;
    }

    private void output(String id, String value) {
        CfnOutput.Builder.create(this, id).value(value).build();
    }

    public static void main(String[] args) {
        App app = new App(software.amazon.awscdk.AppProps.builder().outdir("cdk.out").build());
        new DevStack(app, "dev-stack", StackProps.builder().build());
        app.synth();
    }
}
