# Repository Guidelines

## Project Structure & Module Organization
This repository contains two sibling applications:
- `ais-api/`: Spring Boot backend. Java sources live in `src/main/java/com/gs/ais`, configuration in `src/main/resources/application.yml`, and tests in `src/test/java`.
- `ais-web/`: Vue 3 + Vite frontend. App code is in `src/`, with API clients in `src/api`, Pinia stores in `src/stores`, routes in `src/router`, reusable UI in `src/components`, and page views in `src/views`.
Generated directories such as `ais-api/build/`, `ais-web/dist/`, and `ais-web/node_modules/` should not be edited directly.

## Build, Test, and Development Commands
Run commands from the appropriate subdirectory:
- Backend: `cd ais-api && ./gradlew bootRun` starts the Spring Boot service on the configured port (`11111`) with the default `sqlite` profile.
- Backend MySQL: `cd ais-api && ./gradlew bootRun --args='--spring.profiles.active=mysql'`.
- Backend: `cd ais-api && ./gradlew test` runs JUnit tests.
- Backend: `cd ais-api && ./gradlew build` compiles, tests, and packages the service.
- Frontend: `cd ais-web && yarn` installs dependencies from `yarn.lock`.
- Frontend: `cd ais-web && yarn dev` starts the Vite dev server.
- Frontend: `cd ais-web && yarn build` runs `vue-tsc` type checks and creates the production build.
- Frontend: `cd ais-web && yarn preview` serves the built frontend locally.
- Full package: `./package.sh` builds the frontend, embeds it into the Spring Boot executable Jar, and prints the `java -jar` startup command.

## Coding Style & Naming Conventions
Use Java packages under `com.gs.ais`; name classes in PascalCase and test classes with the same package path as production code. Keep Spring components focused by responsibility (controllers, services, repositories, entities) as the backend grows.
For Vue/TypeScript, use `<script setup lang="ts">`, PascalCase component files such as `ModelSelector.vue`, camelCase variables/functions, and 2-space indentation in Vue templates and styles. Prefer typed API helpers in `src/api` and shared interfaces in `src/types`.

## Testing Guidelines
Backend tests use JUnit 5 with Spring Boot test support; add tests under `ais-api/src/test/java` and run `./gradlew test` before committing backend changes. The frontend currently has no dedicated test runner; use `yarn build` as the minimum verification for TypeScript and Vue template correctness.

## Commit & Pull Request Guidelines
The Git history only shows `init commit`, so use concise imperative commit subjects going forward, for example `Add provider settings endpoint` or `Fix session sidebar state`. Pull requests should include a short summary, test/build results, linked issues when applicable, and screenshots or screen recordings for UI changes.

## Security & Configuration Tips
- Backend defaults to the `sqlite` profile (`./data/ais.db`). Switch with `--spring.profiles.active=mysql` and set `APP_MYSQL_URL` / `APP_MYSQL_USER` / `APP_MYSQL_PASSWORD`.
- Login uses username/password + captcha. The browser computes the password MD5, then RSA-OAEP encrypts that digest in transit (`GET /api/auth/password-key` then `encryptedPassword` on login). Storage is `BCrypt(clientMd5)`.
- When the user table is empty, seed the first admin with `APP_INITIAL_ADMIN_USERNAME` (default `admin`) and `APP_INITIAL_ADMIN_PASSWORD_MD5` (a 32-character MD5 hex digest; do not configure the plaintext password on the server). If the digest is omitted, the bootstrap uses the temporary default password `admin` and warns that it must be changed immediately.
- Login IP lock defaults: `APP_LOGIN_MAX_FAILURES`, `APP_LOGIN_FAILURE_WINDOW_MINUTES`, `APP_LOGIN_LOCK_DURATION_MINUTES`, `APP_LOGIN_CAPTCHA_ENABLED`. These can later be changed in Admin → 登录防护.
- Local development can set `APP_SECURITY_ENABLED=false` to skip login.
- Do not commit real API keys, database passwords, Feishu secrets, or host-specific endpoints. Prefer environment variables (`FEISHU_*`, `APP_*`, `LLM_*`) over hardcoding values in `application.yml`.
- Data export/import lives under Admin → 数据迁移 (`POST /api/admin/export`, `POST /api/admin/import`) and requires admin auth.
- The packaged Jar serves the Vue frontend and backend API from the same port; configure deployment through `APP_*`, `LLM_*`, and `FEISHU_*` environment variables.
