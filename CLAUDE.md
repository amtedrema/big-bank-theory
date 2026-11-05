# big-bank-theory

Bigbank test task: solve both Dragons of Mugloar adventures. Public repo, so no secrets or private material.

## Stack

- `backend/`: Java 21, Spring Boot, Maven Wrapper, JUnit 5, Mockito
- `frontend/`: Vue 3, TypeScript, Pinia, Vite, Vitest
- No database: game state lives on the Mugloar server.

## Commands

If Java is not on the PATH, set JAVA_HOME to a JDK 21 before any ./mvnw command (for example export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot" in Git Bash).

- Backend tests: `cd backend && ./mvnw test` (nothing calls the game server)
- Backend (API on 8080): `cd backend && ./mvnw spring-boot:run`
- Bot (real games): `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=bot -Dspring-boot.run.arguments="--games=3 --target-score=1000000"` (without `--target-score` a game runs until its lives run out, which may be never)
- Frontend dev server (needs the backend on 8080): `cd frontend && pnpm dev`
- Frontend tests: `cd frontend && pnpm exec vitest run`
- Frontend lint (runs oxlint and ESLint with `--fix`, so it can change files): `cd frontend && pnpm lint`
- Frontend build, including type checks: `cd frontend && pnpm build`
- Formatting check: `cd frontend && pnpm exec prettier --check --experimental-cli src/` (`pnpm format` writes)
- Docker: `docker compose up --build` (app on 8080), `docker compose down`, bot: `docker compose run --rm app --spring.profiles.active=bot --games=3 --target-score=1000000`

## Design rules

- Keep the code simple, readable, optimized and consistent. Add a pattern only where it earns its place.
- The strategy lives once in the backend. The bot and the web app's advice both use it.
- The Mugloar API differs from its docs, so validate responses at the client boundary and map its HTML errors to typed errors.
- Decisions must be explainable in a live review. Record trade-offs in `README.md`.

## Rules for this task

- Do not read or reuse other candidates' solutions.
- Keep the "Use of AI tools" section in `README.md` accurate.
