# FamilyHub API

> **This is a fork** of [joe-bor/family-hub-api](https://github.com/joe-bor/family-hub-api)
> for a self-hosted family wall calendar (plan: `family-dashboard/` in the syno-media repo).
> Differences from upstream so far:
> - Testcontainers is the Spring Boot-managed 2.0.x (`testcontainers-postgresql`), which
>   is what makes the test suite run on Docker Engine 29 and Colima.
> - The image defaults to the `prod` profile and honours `JAVA_OPTS`.
> - `/h2-console` and relaxed frame options exist only when `security.h2-console.enabled`
>   is true (the dev profile); prod sends `X-Frame-Options: DENY`.
> - A forgotten sign-in password is reset from the server with
>   `reset-password <username>` (see [Resetting a forgotten password](#resetting-a-forgotten-password));
>   there is deliberately no forgotten-password screen. `PUT /api/auth/password` changes it
>   from a signed-in device, and any change signs out every other device.
> - A member's avatar is a built-in picture (`builtin:<key>`), an uploaded photo or a
>   character from an avatar pack, stored on the member as a PNG/JPEG/WebP data URL of at
>   most 64 KB (checked by its bytes). Avatar packs are family data at `/api/avatar-packs`
>   and travel in backups; their content stays private and is never part of this repo.
> - Five wrong sign-in passwords for a username within 15 minutes lock it for 15 minutes
>   (429 saying how long is left); the server's `reset-password` still works.
> - A rewards shop (`/api/rewards`): parents keep a catalogue priced in chore points with
>   their own PIN; anyone asks for a reward they can afford, which holds the points; a
>   parent's PIN approves (a `REDEEMED` line spends them in the points log) or declines.
> - Followed teams (`/api/sports`): a family follows teams, whose games go on the calendar
>   in their fans' lanes (never into Google Calendar) with live scores while a game is on.
>   Sources sit behind `SportsDataProvider`; MLB's official Stats API is the first. Live
>   scores refresh every 30 s only while a followed game is on, one request for all devices.
> - CI builds a multi-arch image (amd64 + arm64) and needs no repository secrets; the
>   release-please and add-to-project workflows are gone because this repo is push-mirrored
>   from Gitea to GitHub.
>
> Run it with the Compose stack in `syno-media/docker/project/family-dashboard/`.


[![CI](https://github.com/joe-bor/family-hub-api/actions/workflows/ci.yml/badge.svg)](https://github.com/joe-bor/family-hub-api/actions/workflows/ci.yml)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4-brightgreen)

REST API backend for **[FamilyHub](https://familyhub.joe-bor.me/)** — the shared family organizer. Spring Boot + Java 21, JWT auth, PostgreSQL. Pairs with the [React frontend](https://github.com/joe-bor/FamilyHub).

## Overview

FamilyHub runs on a single shared household account: one family login, with members modeled as colored profiles rather than separate logins. This API serves:

- **Calendar** — events with recurrence (RRULE), all-day, and multi-day support
- **Chores** — recurring daily / weekly / monthly routines with per-period completion
- **Lists** — shared grocery and to-do lists
- **Meals** — weekly meal planning
- **Recipes** — a recipe library, including import-from-URL
- **Google Calendar** — OAuth connect, calendar selection, and a scheduled one-way sync

## Tech stack

- **Java 21**, **Spring Boot 4** (Web MVC, Data JPA, Validation)
- **Spring Security** + **JWT** (jjwt) — stateless auth
- **PostgreSQL** with **Flyway** migrations (prod) · **H2** in-memory (dev)
- **iCal4j** for RRULE expansion · **Google Calendar API** for sync · **jsoup** for recipe import
- **Maven** (wrapper) · **Lombok**

## API

Base path: `/api`. All routes require a `Bearer` JWT except `/api/health`, `/api/auth/**` (apart from `PUT /api/auth/password`), and the Google OAuth callback.

| Group | Base | What |
|-------|------|------|
| Auth | `/api/auth` | `register`, `login`, `check-username`, `password` (change, signed in) |
| Family | `/api/family` | get / update / delete the family |
| Members | `/api/family/members` | CRUD member profiles |
| Calendar | `/api/calendar/events` | event CRUD (range query, recurrence) |
| Chores | `/api/chores` | board + recurring templates + completion |
| Lists | `/api/lists` | lists, items, preferences |
| Meals | `/api/meals` | weekly board + slots |
| Recipes | `/api/recipes` | CRUD + `import` from URL |
| Sports | `/api/sports` | leagues, team search, followed teams, games, live scores, the live panel |
| Rewards | `/api/rewards` | the shop: catalogue (parent PIN), asking, approving or declining (parent PIN) |
| Avatar packs | `/api/avatar-packs` | import / list / fetch / delete sets of characters to pick avatars from |
| Google | `/api/google` | OAuth, calendar selection, sync |
| Health | `/api/health` | liveness probe |

The shared calendar contract is documented in [`calendar-events-api-reference.md`](https://github.com/joe-bor/family-hub/blob/main/docs/calendar-events-api-reference.md) in the workspace repo.

## Getting started

**Prerequisites:** JDK 21 (Docker is only needed to run the test suite).

```bash
# dev profile = H2 in-memory; no external database required
JWT_SECRET=$(openssl rand -base64 48) \
TOKEN_ENCRYPTION_KEY=$(openssl rand -base64 32) \
./mvnw spring-boot:run
```

The API starts on **http://localhost:8080**. Verify it's up:

```bash
curl http://localhost:8080/api/health
```

In dev, the H2 console is available at `/h2-console`.

## Configuration

Configuration is supplied via environment variables. The `dev` profile is active by default (H2, Flyway disabled); `prod` uses PostgreSQL with Flyway.

| Variable | Required | Notes |
|----------|----------|-------|
| `JWT_SECRET` | ✅ | HMAC signing key; use a long random value (HS384) |
| `TOKEN_ENCRYPTION_KEY` | ✅ | Base64-encoded 32-byte key (AES-256) for stored Google tokens |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | — | Google Calendar OAuth (optional in dev) |
| `GOOGLE_REDIRECT_URI` / `GOOGLE_FRONTEND_REDIRECT` | — | OAuth redirect overrides |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | prod | PostgreSQL connection (`prod` profile) |
| `CORS_ALLOWED_ORIGINS` | prod | Comma-separated origins (dev defaults to `localhost:5173,localhost:3000`) |

Activate the production profile with `SPRING_PROFILES_ACTIVE=prod` (or `--spring.profiles.active=prod`).

## Database & migrations

- **dev** — H2 in-memory (`jdbc:h2:mem`), schema via Hibernate `ddl-auto: update`, Flyway off, data resets on restart.
- **prod** — PostgreSQL, schema owned by **Flyway** migrations in `src/main/resources/db/migration` (`V1`–`V16`).

## Testing

```bash
./mvnw test
```

Tests run against a real PostgreSQL via **Testcontainers** (Flyway migrations applied), so Docker must be running. `JWT_SECRET` is supplied through the environment in CI.

## Build & deploy

```bash
./mvnw clean package      # -> target/app.jar
```

- **Docker** — multi-stage build (`eclipse-temurin:21` → `21-jre`, non-root user). Images publish to the **GitHub Container Registry**: `ghcr.io/joe-bor/family-hub-api` (tagged `latest`, commit SHA, and semver on release).
- **Versioning** — automated with **release-please**; merging the release PR cuts a GitHub Release and retags the image. Current release: **v1.6.0**.
- **Production** runs as a container on a DigitalOcean droplet against Neon PostgreSQL. See the [deployment guide](https://github.com/joe-bor/family-hub/blob/main/docs/deployment-guide.md).

## Resetting a forgotten password

The family signs in with one shared password and the app has no forgotten-password
screen, so a lost password is reset by whoever runs the server. From the directory with
the Compose file:

```bash
docker compose run --rm api reset-password <username>
```

It asks for the new password twice (hidden) and never accepts it as an argument, so it
stays out of shell history. It starts the app without its web server or scheduled jobs,
uses the same database settings as the running server, and signs out every device: each
token carries the password version it was issued under, and a reset bumps the version.
Scripts can pipe the password instead: `printf '%s\n%s\n' "$pw" "$pw" | docker compose run
-T --rm api reset-password <username>`. Exit codes: 0 done, 1 refused (unknown username,
passwords differ, outside 8–100 characters), 2 wrong usage.

## Project structure

```
com.familyhub.demo
├── controller   REST endpoints
├── service      business logic
├── repository   Spring Data JPA
├── model        JPA entities
├── dto          request / response payloads
├── mapper       entity ⇄ DTO
├── security     JWT + Spring Security config
├── filter       JWT authentication filter
├── config       application configuration
├── scheduler    Google Calendar sync job
├── event        application events
└── exception    error handling
```

## Docs

- **Agent entry:** [`AGENTS.md`](AGENTS.md) (canonical) / [`CLAUDE.md`](CLAUDE.md) — this repo is mentor-first for AI agents.
- **Product source of truth:** the PRD, roadmap, and backlog live in the [`family-hub`](https://github.com/joe-bor/family-hub) workspace repo under `docs/product/`.

## License

[AGPL-3.0](LICENSE) — Copyright © 2026 Joezari Borlongan. Open source; any fork or hosted derivative (including a modified API served over a network) must also publish its source.

## Git hooks (required for contributors)

This fork mirrors to a public repository. Enable the fail-closed secret-scanning hooks once per clone:

```bash
brew install gitleaks
git config core.hooksPath scripts/git-hooks
```

The rules they enforce are in `CLAUDE.md` under "Secrets: hard rules".
