# QTracker

Developer documentation for the QTracker application.

## Project Overview
QTracker is a Spring Boot web application for control lifecycle management, workflow transitions, role-based access control, notifications, reminders, and attachment handling.

Architecture in this repository:
- Modular monolith
- Layered flow: Controller -> Service -> Repository -> PostgreSQL
- Server-rendered UI (Thymeleaf) plus REST APIs

Core implementation areas:
- Controllers: `src/main/java/com/kpmg/qtracker/controller`
- Services: `src/main/java/com/kpmg/qtracker/service`
- Repositories: `src/main/java/com/kpmg/qtracker/repository`
- Security: `src/main/java/com/kpmg/qtracker/config` and `src/main/java/com/kpmg/qtracker/security`
- Schedulers: `src/main/java/com/kpmg/qtracker/scheduler`

## Documentation
Detailed system architecture and documentation are located in the `docs/sar/` (Solution Architecture Reference) directory:
- `01_ARCHITECTURE_AND_API.md`: Architecture overview and API endpoints.
- `02_INFRA_LOGGING_ENCRYPTION.md`: Infrastructure, logging, and security.
- `03_EVIDENCE_AND_QUESTIONNAIRE.md`: Evidence collection and questionnaire handling.
- `diagrams/`: Mermaid diagrams and image exports for system architecture, CI/CD, and workflows.

## Features
Mapped to real modules and classes:

- Control lifecycle management
  - `ControlController`, `ControlTabsController`
  - `ControlService`, `ControlAssignmentService`, `ControlDetailsService`, `ControlDocumentsService`
- Workflow transitions and approvals
  - `WorkflowController`, `WorkflowTransitionController`, `WorkflowApiController`
  - `WorkflowServiceImpl`, `WorkflowRequiredFieldService`
- Role-based authorization and permission checks
  - `SecurityConfig`, `ControlPermissionService`, `PermissionService`, `ControlScope`
- Notifications (in-app and email, NOTIFICATIONS_v5: texts T1-T12 in table `notification_template`, the shorter
  texts of 2026-10-20 since V21: "Dear {NAME}," with the person's name, {LINK} on a line of its own - "Go to Control"
  in the e-mail, left out in-app)
  - `service/notification`: `WorkflowNotifications` (Initiate, Create Control, every workflow move),
    `PlannedNotificationScheduler` (occurrence day, reminders, deadline, overdue), `NotificationProducer`
    (in-app notification + e-mail in the outbox `email_outbox`), `EmailOutboxDispatcher` (sends, retries),
    `MailTransport` (`smtp` or `log`)
  - `NotificationService`: the Notifications section (read, mark read)
- Create Control (the next cycle of a Monthly or Quarterly control, by hand only)
  - `ControlNextCycleController`, `ControlCycleService`
- Attachment upload/download/delete
  - `FileAttachmentController`, `FileStorageService`
- Dashboard and performance endpoints
  - `DashboardController`, `MyDashboardController`, `DashboardDeadlineController`, `PerformanceController`
- Audit logging
  - `AdminAuditService`, `AdminAuditLogRepository`
- Excel export
  - Apache POI usage in control export flow

## Technology Stack
Current repository versions/configuration:

- Java 21 (`pom.xml`)
- Spring Boot 3.5.7
  - `spring-boot-starter-web`
  - `spring-boot-starter-thymeleaf`
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-security`
  - `spring-boot-starter-validation`
  - `spring-boot-starter-actuator`
  - `spring-boot-starter-mail`
- PostgreSQL 17 (Docker compose image)
- Flyway (`flyway-core`, `flyway-database-postgresql`)
- Thymeleaf + static assets
- Apache POI (`poi-ooxml`)
- Docker / Docker Compose
- Maven Wrapper (`mvnw`, `mvnw.cmd`)

## Project Structure
Top-level structure:

- `src/main/java/com/kpmg/qtracker`
  - `controller`: MVC and REST controllers
  - `service`: business logic and orchestration
  - `repository`: Spring Data JPA repositories
  - `entity`: domain entities
  - `dto`: API/data transfer objects
  - `config`: application/security configuration
  - `security`: auth providers, principal services, filters
  - `scheduler`: scheduled jobs
- `src/main/resources`
  - `templates`: Thymeleaf pages
  - `static`: CSS/JS/images
  - `db/migration`: Flyway SQL migrations
  - `application.yml`, `application-dev.yml`, `application-stage.yml`, `application-prod.yml`
- `docs/sar/`: Solution Architecture documentation and diagrams
- `docker-compose.yml`, `Dockerfile`
- `.env`, `.env.local`, `.env.example`

## How to Run

### Option A: Local run (host application, local PostgreSQL)
1. Ensure PostgreSQL is available and credentials match your environment.
2. Set environment variables (or use `.env.local` as reference).
3. Run:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

There is no default profile: without `SPRING_PROFILES_ACTIVE` (or `--spring.profiles.active`) set to `dev`, `stage` or `prod` the application refuses to start (`StartupGuard`).

### Option B: Docker Compose run
Run database + app containers together:

```powershell
docker compose up --build
```

Default host app port is from `APP_PORT` in `.env` (fallback `8081`).

## Environment Variables
Repository-defined variables from `application*.yml`, `.env`, `.env.example`, `.env.local`:

### Core application
- `SPRING_PROFILES_ACTIVE` (required: `dev`, `stage` or `prod`)
- `SERVER_FORWARD_HEADERS_STRATEGY` (default `none`; `native` only behind an HTTP proxy that sets `X-Forwarded-*`)
- `AUTH_SSO_ENABLED`, `AUTH_PASSWORD_ENABLED` (exactly one is true; prod defaults to SSO, dev and stage to the password), `AUTH_LOGOUT_ENABLED` (default: off, with SSO and with the password; `true` brings the Logout link back)
- `SAML_ENTITY_ID`, `SAML_ACS_URL`, `SAML_METADATA_URI` (required with SSO, no defaults)
- `AUTH_SSO_DEBUG_ENDPOINT` (default `false`): `GET /me` shows the signed-in person the SAML claims of their own sign-in; a WARN at start when on in prod
- `SERVER_SERVLET_SESSION_COOKIE_SAME_SITE`, `SERVER_SERVLET_SESSION_COOKIE_SECURE` (with SSO default to `none` and `true`, HttpOnly too: Microsoft's POST to the Assertion Consumer Service is cross-site; HTTPS only)
- `RATE_LIMIT_WINDOW_SECONDS` (60), `RATE_LIMIT_LOGIN` (20), `RATE_LIMIT_API_WRITE` (120), `RATE_LIMIT_USER_LIST` (120), `RATE_LIMIT_HEAVY` (20): requests per window before 429; sign-ins count per name and address together, the rest per signed-in user (behind the port forward every client has the same address); Microsoft's answers to `/login/saml2/**` are not counted
- `SERVER_PORT`
- `APP_PORT`
- `APP_BASE_URL`

### Database
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`

### File storage
- `FILE_UPLOAD_DIR`

### Mail / notifications
- `SPRING_MAIL_HOST`
- `SPRING_MAIL_PORT`
- `SPRING_MAIL_USERNAME`
- `SPRING_MAIL_PASSWORD`
- `NOTIFICATIONS_EMAIL_ENABLED` (false: the e-mails are recorded `SKIPPED_DISABLED` and never sent)
- `NOTIFICATIONS_EMAIL_PROVIDER` (`smtp`, default, or `log`)
- `NOTIFICATIONS_EMAIL_FROM` (the service mailbox the e-mails come from; empty by default: with `NOTIFICATIONS_EMAIL_ENABLED=true` the start logs a WARN, the application still starts, and the e-mails go without a From address, which the SMTP relay may refuse)
- `NOTIFICATIONS_EMAIL_WHITELIST_ENFORCED` (default false: everybody gets e-mails; true: only the addresses of `NOTIFICATIONS_EMAIL_WHITELIST`, the others are recorded `SKIPPED_DISABLED` "not in the whitelist"; true with an empty list sends to nobody, with a WARN at start)
- `NOTIFICATIONS_EMAIL_WHITELIST` (comma-separated addresses, compared ignoring case)
- `NOTIFICATIONS_SCHEDULER_ENABLED` (default true: the planned notifications)

### Upload / request limits
- `MAX_UPLOAD_FILE_SIZE`
- `MAX_UPLOAD_REQUEST_SIZE`
- `MAX_HTTP_FORM_POST_SIZE`

### Session cookie
- `SESSION_COOKIE_SECURE`

Note:
- `.env` is used by Docker Compose.
- `.env.local` is a local host-run reference (not automatically consumed by Spring unless exported into environment).

## Spring Profiles

Exactly one of `dev`, `stage` and `prod` must be given (the tests use `test`); none is the default.

- `dev`
  - Uses dev datasource defaults if env vars are not provided.
  - Security chain uses form login and `DevAuthenticationProvider`.
- `stage`
  - Externalized datasource and file/upload settings.
  - JPA `ddl-auto=validate`, SQL logging disabled.
- `prod`
  - Externalized datasource and file/upload settings.
  - JPA `ddl-auto=validate`, SQL logging disabled.
  - Its own security chain; sign-in with Microsoft Entra ID (SAML 2.0) by default.

## Database and Migrations
Flyway migration scripts:

- `src/main/resources/db/migration/V1__init_schema.sql`
- `src/main/resources/db/migration/V2__seed_initial_admin.sql`

Core tables created in V1:
- `users`
- `controls`
- `workflow_steps`
- `workflow_history`
- `notifications`
- `control_notification_log` (no longer written since the notifications of v5; kept with its rows)
- `admin_audit_log`

Entity model highlights:
- `Control`, `ControlAssignment`, `ControlDetails`, and `ControlDocuments` all map to `controls` (projection-style domain mapping).
- Workflow state + history are split across `workflow_steps` and `workflow_history`.

## Schedulers
Schedulers under `src/main/java/com/kpmg/qtracker/scheduler`:

- `PlannedNotificationTrigger`: every 5 minutes Monday to Friday; `PlannedNotificationScheduler` starts at 09:30
  Asia/Almaty and makes what today calls for (the same for every frequency); off with
  `NOTIFICATIONS_SCHEDULER_ENABLED=false`
- `EmailOutboxDispatchTrigger`: every 20 seconds, sends the e-mails of `email_outbox`

The cron is in code with the `Asia/Almaty` timezone; the times come from the `Clock` bean.

## Security Overview
Authentication (`AuthMode`, exactly one of the two):
- SSO (`AUTH_SSO_ENABLED=true`, the prod default): SAML 2.0 with Microsoft Entra ID, registration `entra` (`SamlConfig`, `SsoSignIn`). Settings `SAML_ENTITY_ID`, `SAML_ACS_URL` (`https://<site>/login/saml2/sso/entra`), `SAML_METADATA_URI`; the Entra Sign on URL is `APP_BASE_URL/saml2/authenticate/entra`, the service provider metadata is at `/saml2/metadata/entra`. No password form then.
- Password form (`AUTH_PASSWORD_ENABLED=true`, the dev and stage default): `/login` with `DevAuthenticationProvider`.
- Without a session `/api/**` answers 401 JSON (`SESSION_EXPIRED`), pages go to the sign-in and come back to the page asked for. Sessions end after 15 minutes without a request; `js/session.js` shows "Session expired, reload the page" on such a 401 (nothing is reloaded or cleared) and, while someone types, calls `GET /api/session/keepalive` at most once in 5 minutes.
- Before SSO the imported people need their real address (the import gave them `<login>@qtracker.local`, which SSO refuses): `java -jar qtracker.jar --update-user-emails migration/<file>.csv` with the columns `User` (login or display name) and `Email`; a dry run unless `--apply`, all or nothing, idempotent, audited; the console shows line numbers and user ids only, the list with the addresses is written next to the file. With SSO the Admin Panel's Add User needs the corporate e-mail and sets no password.
- Logout (`AUTH_LOGOUT_ENABLED`, off by default since 2026-10-16): there is no Logout link and no `/logout`, and the sidebar says nothing about it. Switched on with SSO, `/logout` ends only the QTracker session (`/signed-out` says so).

Authorization:
- Endpoint protection configured in `SecurityConfig`
- Per-control permissions in `ControlPermissionService`; one read rule for pages and the REST API in `PermissionService.readAccess` / `requireReadable` (no field is hidden on read)
- Roles from `users.access_level` and `users.access_scope` (`AccessPolicy.Profile`): SoQM Team, User (My or All controls, Edit or Read Only), KDN (read-only, KDN controls only); Facilitator, Control Operator and Process Owner are assignments on a control, not user roles. The old `users.role` / `secondary_role` columns were dropped in V22
- Administration (Admin Panel) belongs to SoQM Team; `admin_access` follows the level (V11)

Request protection:
- CSRF token repository enabled (with configured exclusions)
- `RateLimitingFilter` for selected high-risk paths
- `CorrelationIdFilter` for request correlation
- Security headers configured (including CSP, HSTS, frame options, referrer policy)

## File Storage
Attachment handling:
- Storage root from `file.upload.dir` / `FILE_UPLOAD_DIR`
- IO via `FileStorageService`
- Upload endpoints in `FileAttachmentController`

Security considerations in implementation:
- Filename and folder sanitization
- Read access to the control checked through `PermissionService.requireReadable` before file reads; a download serves only a file the control lists

## How to Build
Build with Maven Wrapper:

```powershell
.\mvnw.cmd clean package
```

Build without tests:

```powershell
.\mvnw.cmd -DskipTests clean package
```

## How to Test
Run tests:

```powershell
.\mvnw.cmd test
```

## Health Check
Actuator health endpoint:

- URL: `/actuator/health`

Container healthcheck in `Dockerfile` uses:
- `http://127.0.0.1:8080/actuator/health`

## Troubleshooting

### 1) Database connection failures
Symptoms:
- app cannot start, datasource errors.

Checks:
- Verify `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
- For Docker Compose, ensure `db` service is healthy.
- For local run, ensure PostgreSQL is listening on expected host/port.

### 2) Flyway migration/checksum issues
Symptoms:
- Flyway validation failure at startup.

Checks:
- Do not modify already applied migration scripts in shared environments.
- Use a new versioned migration for schema changes.
- If local-only mismatch occurred, align local DB state carefully before rerun.

### 3) SMTP/email not sending
Symptoms:
- in-app notification exists but email is missing (see `email_outbox`: status, attempts, last_error).

Checks:
- Verify `NOTIFICATIONS_EMAIL_ENABLED=true` when email is required.
- Validate `SPRING_MAIL_*` values and SMTP connectivity.
- If whitelist enforcement is enabled, confirm recipients are in `NOTIFICATIONS_EMAIL_WHITELIST`.

## License / Contact
No project license metadata is defined in `pom.xml`.
For ownership and support, use your internal project contacts and repository maintainers.