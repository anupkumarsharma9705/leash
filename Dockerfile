# syntax=docker/dockerfile:1

# 1. Build the operator console.
# VITE_API_BASE_URL can be overridden at build time for cross-origin deployments.
# Defaults to empty (relative paths), which works when frontend and backend share the same origin.
ARG VITE_API_BASE_URL=""
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN VITE_API_BASE_URL=${VITE_API_BASE_URL} npm run build

# 2. Build the Spring Boot jar with the console baked into static resources.
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app/backend
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
# A transient failure here is usually the wrapper downloading the Maven distribution.
# Retry twice before failing the build; the layers above stay cached.
RUN chmod +x mvnw \
    && (./mvnw -B -q -DskipTests dependency:go-offline \
        || (echo "Maven download failed; retrying in 5s" && sleep 5 && ./mvnw -B -q -DskipTests dependency:go-offline) \
        || (echo "Maven download failed again; retrying in 15s" && sleep 15 && ./mvnw -B -q -DskipTests dependency:go-offline))
COPY backend/src ./src
COPY --from=frontend /app/frontend/dist ./src/main/resources/static
RUN ./mvnw -B -DskipTests package

# 3. Runtime: JRE only, non-root, no Maven and no Node.
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 1001 leash \
    && useradd --system --uid 1001 --gid leash --no-create-home leash
WORKDIR /app
COPY --from=backend /app/backend/target/leash-0.0.1-SNAPSHOT.jar app.jar
USER leash
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
