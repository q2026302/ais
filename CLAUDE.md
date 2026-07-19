# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ais — a text-to-image web application. Two sub-projects side-by-side:

- **`ais-api/`** — Spring Boot 4.1.0 backend (Java 25, Gradle 9.5.1)
- **`ais-web/`** — Vue 3 + Vite 8 frontend (TypeScript 6, Yarn)

Both are currently initial scaffolds with no business logic.

## Build & Run Commands

### Backend (`ais-api/`)

```sh
# Build the whole project
./gradlew build

# Run tests (JUnit Platform)
./gradlew test

# Run a single test class
./gradlew test --tests "com.gs.ais.AisApplicationTests"

# Run tests matching a pattern
./gradlew test --tests "com.gs.ais.*"

# Run Spring Boot dev server (port 8080)
./gradlew bootRun
```

**Project coordinates**: group `com.gs`, version `0.0.1-SNAPSHOT`
**Test dependencies** use dedicated starters: `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-actuator-test`.

### Frontend (`ais-web/`)

```sh
# Install dependencies (no lockfile committed yet)
yarn

# Start dev server with hot-reload (default http://localhost:5173)
yarn dev

# Type-check + build for production
yarn build

# Type-check only
yarn type-check

# Preview production build
yarn preview
```

## Architecture

### Backend (`ais-api/`)

Spring Boot 4.1.0 at package `com.gs.ais`. Uses Java 25 toolchain.

- **`AisApplication.java`** — `@SpringBootApplication` entry point, currently empty of other classes.
- **Dependencies**: `spring-boot-starter-webmvc` (REST API), `spring-boot-starter-data-jpa` (JPA/Hibernate), `spring-boot-starter-actuator` (health/metrics), `mysql-connector-j` (runtime only).
- **Test dependencies**: `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-actuator-test`, `junit-platform-launcher`.
- **Gradle**: Wrapper configured with `./gradlew`, Gradle 9.5.1 from Tencent Cloud mirror (`gradle-wrapper.properties`), network timeout 10s.
- **No controllers, entities, repositories, or services defined yet** — this is a blank scaffold.

### Frontend (`ais-web/`)

Vue 3 SPA scaffolded via `create-vue`. Uses Vite 8, TypeScript 6, and `vue-tsc` for type-checking.

- **`src/App.vue`** — single root component, `src/main.ts` mounts to `#app`.
- **No router, state management (Pinia), or HTTP client configured yet** — vanilla Vue 3 scaffold.
- **`@/` import alias** maps to `src/` (configured in both `vite.config.ts` and `tsconfig.app.json`).
- **`vue-tsc --build`** (not plain `tsc`) for type-checking; produces a `.tsbuildinfo` file in `node_modules/.tmp/`.
- **`npm-run-all2`** (`run-p`) runs type-check and build concurrently in `yarn build`.
- **Volar** (`Vue.volar`) recommended VS Code extension.
- **Node.js**: `>=22.18.0` or `>=24.12.0` per `package.json` engines.
