# Stage 1: build the Vue app.
FROM node:24-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
# Corepack installs the pnpm version named in package.json.
RUN corepack enable && pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

# Stage 2: build the Spring Boot jar with the built Vue app as static content.
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /backend
COPY backend/ ./
COPY --from=frontend /frontend/dist src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -ntp -DskipTests package \
 && cp target/*.jar /app.jar

# Stage 3: run as user 1000. No CMD, so "docker compose run app --spring.profiles.active=bot" appends arguments.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app.jar app.jar
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
