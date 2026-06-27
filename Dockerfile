# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the Maven wrapper first so dependency resolution is cached unless the build files change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw && ./mvnw -B dependency:go-offline

# Copy sources and build the (skip tests here; CI runs them).
COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is used by the compose healthcheck; run as a non-root user.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 appuser
COPY --from=build /app/target/*.jar app.jar

# Persisted at runtime: write-ahead journal (data/) and per-host logs (logs/).
RUN mkdir -p data logs && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
