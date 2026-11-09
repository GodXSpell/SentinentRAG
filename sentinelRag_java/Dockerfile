# Multi-stage Dockerfile for Sentinel-RAG Java Control Plane
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# 1. Copy proto definitions from root
COPY proto /proto

# 2. Copy pom.xml from subfolder into build working dir
COPY sentinelRag_java/pom.xml .
RUN mvn dependency:go-offline -B || true

# 3. Copy Java source files from subfolder
COPY sentinelRag_java/src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine AS runner

WORKDIR /app

RUN apk add --no-cache curl

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/webhooks/github | grep -E '200|400|405' || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "app.jar"]