# Technical Section for PRD

## 1. System Description (Technical)
QTracker is a Spring Boot web application for control lifecycle management with role-based workflow, notifications, reminders, attachments, and audit trails.

Implementation style:
- Modular monolith
- Layered architecture: Controller -> Service -> Repository -> PostgreSQL
- Server-rendered UI (Thymeleaf) plus REST APIs

Primary package structure:
- Controllers: `com.kpmg.qtracker.controller`
- Services: `com.kpmg.qtracker.service`
- Repositories: `com.kpmg.qtracker.repository`
- Security: `com.kpmg.qtracker.config`, `com.kpmg.qtracker.security`

## 2. Technical Scope of the Solution
In scope (implemented in repository):
- Control lifecycle CRUD and status management
- Assignment/details/documents management on control records
- Multi-step workflow transitions and workflow history
- In-app notifications and optional email delivery
- Scheduled reminders and scheduled auto-creation of recurring controls
- File upload/download/view/delete with authorization checks
- Dashboard/performance APIs and Excel export
- Profile-based security (`dev`, `ssodev`) and environment configuration (`dev`, `stage`, `prod`)

## 3. Functional Modules (Mapped to Real Classes)

### 3.1 Authentication and Access Control
- Security configuration: `SecurityConfig`
- Dev authentication provider: `DevAuthenticationProvider`
- User principal loading: `UserPrincipalService`
- Permission policy: `AuthorizationPolicy`, `ControlPermissionService`, `PermissionService`
- Role endpoints: `RoleController`, `UserController`, `PermissionController`

### 3.2 Control Lifecycle Module
- Controllers: `ControlController`, `ControlTabsController`
- Services: `ControlService`, `ControlAssignmentService`, `ControlDetailsService`, `ControlDocumentsService`, `ControlIdGeneratorService`
- Repositories: `ControlRepository`, `ControlAssignmentRepository`, `ControlDetailsRepository`, `ControlDocumentsRepository`

### 3.3 Workflow Module
- Controllers: `WorkflowController`, `WorkflowTransitionController`, `WorkflowApiController`
- Services: `WorkflowServiceImpl`, `WorkflowRequiredFieldService`
- Repositories: `WorkflowStepRepository`, `WorkflowHistoryRepository`
- Status enum: `WorkflowStatus`
- Action enum: `WorkflowActionType`

### 3.4 Notifications and Reminders Module
- Notification API: `NotificationApiController`
- Services: `NotificationService`, `NotificationTemplateService`, `EmailNotificationService`, `ReminderNotificationService`
- Scheduler: `ControlReminderScheduler` + frequency schedulers (`MonthlyNotificationScheduler`, `QuarterlyNotificationScheduler`, `SemiAnnualNotificationScheduler`, `AnnualNotificationScheduler`, `RecurringNotificationScheduler`, `AdhocNotificationScheduler`)
- Repositories: `NotificationRepository`, `ControlNotificationLogRepository`

### 3.5 Auto-Creation Module
- Scheduler: `ControlAutoCreationScheduler`
- Service: `ControlAutoCreationService`
- Repositories: `ControlRepository`, `ControlAssignmentRepository`

### 3.6 Attachments and Audit Module
- Controller: `FileAttachmentController`
- Service: `FileStorageService`, `AdminAuditService`
- Repository: `AdminAuditLogRepository`

### 3.7 Dashboard and Performance Module
- Controllers: `DashboardController`, `MyDashboardController`, `DashboardDeadlineController`, `PerformanceController`, `ViewController`
- Services: `DashboardService`, `PerformanceService`

## 4. Domain Model and Data Model

### 4.1 Domain Entities (JPA)
- `User` -> table `users`
- `Control` -> table `controls`
- `ControlAssignment` -> table `controls` (projection mapping)
- `ControlDetails` -> table `controls` (projection mapping)
- `ControlDocuments` -> table `controls` (projection mapping)
- `WorkflowStep` -> table `workflow_steps`
- `WorkflowHistory` -> table `workflow_history`
- `Notification` -> table `notifications`
- `ControlNotificationLog` -> table `control_notification_log`
- `AdminAuditLog` -> table `admin_audit_log`

### 4.2 Core Database Schema (Flyway)
Migrations:
- `V1__init_schema.sql`
- `V2__seed_initial_admin.sql`

Core tables:
- `users`
- `controls`
- `workflow_steps`
- `workflow_history`
- `notifications`
- `control_notification_log`
- `admin_audit_log`

Key constraints implemented:
- PK/UK/FK constraints from migrations
- `controls.created_by` references `users.id`
- unique keys for `users.username`, `users.mail`, `controls.control_id`
- check constraints for workflow/status fields

### 4.3 Relationship Notes
- `controls` is the central aggregate table.
- Assignment/details/documents are modeled as focused entity projections over `controls`.
- Workflow state is split into current steps (`workflow_steps`) and historical trail (`workflow_history`).
- Notification dedupe for schedulers is stored in `control_notification_log`.

## 5. Technical Workflow Logic

### 5.1 Control Lifecycle
Typical flow:
1. Request enters `ControlController` / `ControlTabsController`.
2. Access checks run through `AuthorizationPolicy` and `ControlPermissionService`.
3. Domain services update control/assignment/details/documents.
4. Repositories persist updates to PostgreSQL.

### 5.2 Workflow Transitions
Typical flow:
1. Request enters `WorkflowController` / `WorkflowTransitionController`.
2. Required field checks are executed by `WorkflowRequiredFieldService`.
3. Status transitions update `controls.performance_status` and/or `workflow_steps`.
4. History is written to `workflow_history`.
5. Notifications are created via `NotificationService`.

### 5.3 Notification Logic
- In-app notifications are persisted in `notifications`.
- Email channel is used when mail configuration and flags permit.
- Return/action notifications support dedupe windows and template-based rendering.

### 5.4 Reminder and Auto-Creation Schedulers
- `ControlReminderScheduler` triggers `ReminderNotificationService` daily.
- Candidate controls are selected with repository queries and processed with working-day logic.
- Deduplication records are persisted in `control_notification_log`.
- `ControlAutoCreationScheduler` triggers `ControlAutoCreationService` to create next control occurrences by frequency.

### 5.5 Attachments
- `FileAttachmentController` handles upload/download/view/delete.
- `FileStorageService` performs filesystem IO with filename/folder sanitization.
- Attachment metadata is stored on `controls` fields.

## 6. API Overview (High-Level Endpoint Groups)

Authentication/UI:
- `/login`, `/logout`, and MVC view routes in `ViewController`

Control domain:
- `/api/controls`
- `/api/control-details`
- `/api/control-assignment`
- `/api/control-documents`

Workflow:
- `/api/workflow` (status, actions, transitions, approvals)

Dashboard and performance:
- `/api/dashboard/admin`
- `/api/dashboard/my`
- `/api/dashboard` (deadline endpoints)
- `/api/performance`

Attachments:
- `/api/attachments`

Notifications and access metadata:
- `/api/notifications`
- `/api/users`
- `/api/roles`
- `/api/permissions`

Operational:
- `/actuator/health`

## 7. Non-Functional Technical Requirements
Repository-grounded technical requirements:
- Security: Spring Security filter chains, CSRF token repository, security headers, RBAC and policy-based authorization.
- Integrity: Flyway migrations + DB constraints + validation annotations.
- Reliability: health endpoint, container healthcheck readiness model, controlled scheduler execution.
- Maintainability: layered package structure and explicit domain service separation.
- Auditability: workflow history, notifications records, and admin audit log support.

Quantitative SLOs (latency/uptime) are not explicitly codified in repository configuration and should be defined by product/operations governance.

## 8. Dependencies
Core dependencies from build configuration:
- Java 25
- Spring Boot 3.5.7
- Spring starters: web, thymeleaf, data-jpa, security, validation, actuator, mail, test
- Spring Security Crypto
- Flyway (`flyway-core`, `flyway-database-postgresql`)
- PostgreSQL runtime driver
- Apache POI (`poi-ooxml`) for Excel export
- Lombok (compile-time)

Runtime platform dependencies:
- PostgreSQL
- SMTP server
- Filesystem or mounted volume for uploads
- OAuth2 identity provider when `ssodev` is used

## 9. Constraints and Assumptions
Constraints from code and configuration:
- Main persistence model is centered on `controls` with multiple projection entities.
- File storage is filesystem-based (path configured by `file.upload.dir`).
- Security behavior differs by active profile (`dev` form auth vs `ssodev` OAuth2 login).
- Scheduler execution depends on configuration toggles (`reminders.enabled`, `controls.auto-create.enabled`).

Assumptions for deployment:
- Environment variables provide secrets and endpoint configuration.
- Stage/prod environments provide managed PostgreSQL and SMTP connectivity.
- OAuth2 client settings are externally provided when `ssodev` is enabled.

## 10. Environment and Configuration Summary
Profiles and config files:
- Global: `application.yml`
- Dev: `application-dev.yml`
- Stage: `application-stage.yml`
- Prod: `application-prod.yml`

Security profile behavior:
- `dev`: `SecurityFilterChain` with `formLogin` and `DevAuthenticationProvider`
- `ssodev`: `SecurityFilterChain` with `oauth2Login`

Selected runtime configuration areas:
- Server port and session cookie settings
- Multipart size limits
- DataSource and JPA settings
- Flyway baseline behavior
- Mail transport settings
- Reminder/auto-create feature toggles
- Base URL and upload directory

## 11. Logging, Audit, and Security Considerations
Logging and tracing:
- Application logging configured via Spring logging levels
- Correlation ID response header and MDC context via `CorrelationIdFilter`

Audit and history:
- Workflow events: `workflow_history`
- User notifications: `notifications`
- Admin audit actions: `admin_audit_log`

Security controls implemented:
- CSRF token repository with scoped exclusions
- Request rate limiting via `RateLimitingFilter`
- Login attempt tracking/lockout support via `LoginAttemptService`
- Security headers (CSP, HSTS, frame options, referrer, permissions policy)
- BCrypt password encoding
- Authorization checks in controllers/services using `AuthorizationPolicy` and permission services