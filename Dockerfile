# ── Eclipse Temurin Java 21 JDK Build Stage ─────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY mvnw* ./
COPY .mvn .mvn
COPY pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

COPY src ./src

RUN ./mvnw clean package -DskipTests

# ── Runtime Stage ───────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]