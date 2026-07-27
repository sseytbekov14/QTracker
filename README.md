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
  - `SecurityConfig`, `AuthorizationPolicy`, `ControlPermissionService`, `PermissionService`
- Notifications (in-app and email)
  - `NotificationApiController`, `NotificationService`, `NotificationTemplateService`, `EmailNotificationService`
- Reminder and auto-creation scheduling
  - `ControlReminderScheduler`, `ControlAutoCreationScheduler`
  - `ReminderNotificationService`, `ControlAutoCreationService`
- Attachment upload/download/view/delete
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
.\mvnw.cmd spring-boot:run
```

### Option B: Docker Compose run
Run database + app containers together:

```powershell
docker compose up --build
```

Default host app port is from `APP_PORT` in `.env` (fallback `8081`).

## Environment Variables
Repository-defined variables from `application*.yml`, `.env`, `.env.example`, `.env.local`:

### Core application
- `SPRING_PROFILES_ACTIVE`
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
- `NOTIFICATIONS_EMAIL_ENABLED`
- `NOTIFICATIONS_EMAIL_WHITELIST_ENFORCED`
- `NOTIFICATIONS_EMAIL_WHITELIST`

### Scheduling / behavior
- `CONTROLS_AUTO_CREATE_ENABLED`
- `OVERDUE_USE_WORKING_DAYS`

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

- `dev`
  - Uses dev datasource defaults if env vars are not provided.
  - Security chain uses form login and `DevAuthenticationProvider`.
- `stage`
  - Externalized datasource and file/upload settings.
  - JPA `ddl-auto=validate`, SQL logging disabled.
- `prod`
  - Externalized datasource and file/upload settings.
  - JPA `ddl-auto=validate`, SQL logging disabled.
- `ssodev`
  - Enables OAuth2 login in `SecurityConfig`.
  - Intended for SSO-based authentication path.

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
- `control_notification_log`
- `admin_audit_log`

Entity model highlights:
- `Control`, `ControlAssignment`, `ControlDetails`, and `ControlDocuments` all map to `controls` (projection-style domain mapping).
- Workflow state + history are split across `workflow_steps` and `workflow_history`.

## Schedulers
Schedulers under `src/main/java/com/kpmg/qtracker/scheduler`:

- `ControlReminderScheduler`
  - Daily reminder run
  - `@ConditionalOnProperty(reminders.enabled=true)`
- `ControlAutoCreationScheduler`
  - Daily auto-creation run
  - `@ConditionalOnProperty(controls.auto-create.enabled=true)`
- Periodic schedulers (Monthly, Quarterly, SemiAnnual, Annual)
- `RecurringNotificationScheduler`
- `AdhocNotificationScheduler`

All scheduler cron definitions are in code with `Asia/Almaty` timezone.

## Security Overview
Authentication:
- `dev` profile: form login (`/login`) with `DevAuthenticationProvider`
- `ssodev` profile: OAuth2 login (`oauth2Login`)

Authorization:
- Endpoint protection configured in `SecurityConfig`
- Fine-grained checks in `AuthorizationPolicy` and `ControlPermissionService`
- Roles used in business logic: `FACILITATOR`, `CONTROL_OPERATOR`, `SOQM_LEAD`, `PROCESS_OWNER`, `ADMIN`

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
- Access checks before file operations through `AuthorizationPolicy`

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
- in-app notification exists but email is missing.

Checks:
- Verify `NOTIFICATIONS_EMAIL_ENABLED=true` when email is required.
- Validate `SPRING_MAIL_*` values and SMTP connectivity.
- If whitelist enforcement is enabled, confirm recipients are in `NOTIFICATIONS_EMAIL_WHITELIST`.

## License / Contact
No project license metadata is defined in `pom.xml`.
For ownership and support, use your internal project contacts and repository maintainers.