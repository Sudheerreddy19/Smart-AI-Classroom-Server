# ── Stage 1: Build Spring Boot with Maven ───────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Cache Maven dependencies first (faster rebuilds)
COPY mvnw* ./
COPY .mvn .mvn
COPY pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw dependency:go-offline -q

# Build the application
COPY src ./src
RUN ./mvnw clean package -DskipTests -q

# ── Stage 2: Minimal Runtime Image ──────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/*.jar app.jar

# Use non-root user
USER appuser

ENV PORT=8080
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]