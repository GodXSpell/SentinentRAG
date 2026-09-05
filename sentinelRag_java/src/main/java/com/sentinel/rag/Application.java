package com.sentinel.rag;

import com.sentinel.rag.grpc.RagEngineGrpc;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.scheduling.annotation.EnableAsync;

// @ConfigurationPropertiesScan: see QualityGateThresholdsConfig — registers any
// @ConfigurationProperties class as a bean automatically.
//
// @ImportGrpcClients: Spring Boot 4.1's gRPC client starter does NOT auto-detect and
// register stub beans just because spring-boot-starter-grpc-client is on the classpath —
// each stub type must be explicitly named here (or scanned via basePackageClasses) before
// Spring will create it as an injectable bean. Without this, RagEngineGrpcClient's
// constructor injection of RagEngineGrpc.RagEngineBlockingStub fails at startup with
// "no qualifying bean," the same class of error @ConfigurationPropertiesScan fixed earlier
// for QualityGateThresholdsConfig. The target host:port for this stub's channel is
// configured separately in application.yml under spring.grpc.client.channels.*.
//
// @EnableAsync: required for @Async methods to actually run on a separate thread.
// Without this, Spring silently ignores @Async and runs the method synchronously on
// the calling thread — no error, no warning, just quietly not async.
@SpringBootApplication
@ConfigurationPropertiesScan
@ImportGrpcClients(types = RagEngineGrpc.RagEngineBlockingStub.class)
@EnableAsync
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}