# Security Compliance Summary

## 1. Executive Summary

This document maps security controls that are actually implemented in the QTracker repository to the control themes requested for the following KPMG standards: API Security, Security Testing, Application Container Security, and Solution Acquisition, Development & Maintenance. The assessment is evidence-based and limited to repository artifacts in `src/`, `docs/`, `Dockerfile`, `docker-compose.yml`, Maven build files, and the current automated test suite.

QTracker implements a meaningful baseline of security controls: Spring Security protects authenticated routes, object and workflow permissions are enforced for many control-centric operations, login lockout and request throttling are present, security headers are configured, correlation IDs and audit logging exist, and there is targeted automated test coverage for several security behaviors. At the same time, coverage is not uniform across the entire API surface. Some metadata and attachment endpoints rely only on broad authentication or session state, container hardening is incomplete outside the main application image, and no CI/CD or automated security scanning pipeline is present in the repository.

## 2. Compliance Overview

| Standard | Overall status | Repository-based summary |
|---|---|---|
| Security Standard – API Security | Partially compliant | Strong baseline for authentication, selected RBAC/ABAC controls, workflow restrictions, headers, rate limiting, and error handling; however, some endpoints still lack object-level authorization or user-context validation. |
| Security Standard – Security Testing | Partially compliant | The repository contains focused unit, MVC, and integration tests for key security behaviors, but there is no evidence of CI-enforced SAST, DAST, dependency scanning, or release security gates. |
| Security Standard – Application Container Security | Partially compliant | The application container drops root and uses a multi-stage build, but Compose-level hardening is limited, backup uses a host bind mount, and no image scanning or digest pinning is present. |
| Security Standard – Solution Acquisition, Development & Maintenance | Partially compliant | Architecture, API, and data flow documentation exist and environment separation is externalized, but documented hardening statements are not fully aligned with runtime configuration and no delivery pipeline controls are checked into the repository. |

## 3. API Security Mapping

| Requirement summary | Where QTracker satisfies or addresses it | Status |
|---|---|---|
| Enforce authentication for application routes and APIs | `SecurityConfig.securityFilterChainDev()` and `SecurityConfig.securityFilterChainSso()` require authentication for `/api/**` and all non-public routes, while allowing only `/login`, `/actuator/health`, and static assets without authentication. | Fully compliant |
| Support authenticated login flows with secure password verification | `SecurityConfig` configures Spring Security form login in `dev` and OAuth2 login in `ssodev`. `DevAuthenticationProvider.authenticate()` loads users from `UserPrincipalService`, validates passwords through `PasswordEncoder`, and builds Spring Security authorities. `PasswordConfig.passwordEncoder()` uses `BCryptPasswordEncoder`. | Fully compliant |
| Protect against brute-force login attempts | `LoginAttemptService.recordFailure()`, `LoginAttemptService.isLocked()`, and `LoginAttemptService.recordSuccess()` implement a 5-attempt lockout for 15 minutes. `DevAuthenticationProvider.authenticate()` blocks locked users. | Fully compliant |
| Apply RBAC and role-aware authorization | `ControlPermissionService.resolve()` and `AuthorizationPolicy` implement role- and status-aware permissions for `ADMIN`, `SOQM_LEAD`, `PROCESS_OWNER`, `FACILITATOR`, and `CONTROL_OPERATOR`. `DashboardController` restricts admin dashboards to `SOQM_LEAD`. `ControlController.createControl()` restricts control creation to `SOQM_LEAD`. | Partially compliant |
| Enforce object-level authorization (BOLA) for control data | `AuthorizationPolicy.checkCanReadControl()`, `checkCanModifyControl()`, and `checkWorkflowPermission()` use `ControlPermissionService` plus assignment data to gate access. Control update and workflow endpoints also use `ControlPermissionService.resolve(...)` directly. | Partially compliant |
| Protect against broken function-level authorization (BFLA) | Workflow transition endpoints in `WorkflowTransitionController` validate both login and assigned role membership for specific transitions. `DashboardController` blocks non-SoQM access to admin dashboards. `ControlController.createControl()` denies non-SoQM creation. | Partially compliant |
| Protect against broken object property level authorization (BOPLA) | `AuthorizationPolicy.filterReadableFields()` and `validateEditableFields()` define field-level restrictions. `ControlController.updateControl()` enforces role-specific restrictions for `soqmHeadComments` and `processOwnerComments`. `ControlPermission.allowedEditableFields` models editable field subsets. | Partially compliant |
| Apply CSRF protection for browser-authenticated flows | `SecurityConfig` enables `CookieCsrfTokenRepository.withHttpOnlyFalse()` and keeps CSRF active for form login. `SecurityConfigIntegrationTest.loginPostWithoutCsrfIsForbidden()` validates the behavior. | Requires BO/NITSO decision |
| Limit abusive or automated API usage | `RateLimitingFilter` rate-limits `POST /login`, enumeration reads for `/api/users`, `/api/roles`, `/api/notifications`, and write operations under `/api/**`. The filter returns HTTP 429 with `RATE_LIMITED` JSON. | Partially compliant |
| Add request correlation for diagnostics and investigations | `CorrelationIdFilter.doFilterInternal()` generates a UUID per request, stores it in MDC, and returns it in `X-Correlation-Id`. `GlobalExceptionHandler` includes the correlation ID in error responses. | Fully compliant |
| Return controlled error payloads instead of exposing stack traces | `GlobalExceptionHandler.handleAccessDenied()` and `handleValidation()` return structured `ErrorResponse` objects. `ApiDateFormatExceptionHandler.handleHttpMessageNotReadable()` converts bad date payloads into a controlled 400 response. | Fully compliant |
| Set secure response headers and session fixation protection | `SecurityConfig` enables HSTS, CSP, `X-Content-Type-Options`, frame options `sameOrigin`, `Referrer-Policy`, `Permissions-Policy`, XSS protection header, and session fixation migration. | Fully compliant |

### API Security Observations

- Control-centric endpoints are the strongest area. `ControlController`, `WorkflowTransitionController`, `DashboardController`, and parts of the workflow/UI stack apply explicit permission checks.
- Protection is not uniform across all endpoints. `UserController`, `RoleController`, and `NotificationApiController` expose user or notification data to any authenticated caller without additional subject or role validation.
- `NotificationApiController.getNotifications(@RequestParam Long userId)` trusts caller-supplied `userId` and does not use `AuthorizationPolicy.checkUserAccess()`.
- `FileAttachmentController` does not call `AuthorizationPolicy.checkAttachmentAccess()` or `ControlPermissionService`; upload, download, view, info, and delete operations rely on route authentication and, in some methods, only the session’s `currentUser` attribute for audit logging.
- `AuthorizationPolicy.filterReadableFields()` and `validateEditableFields()` are implemented but are not visibly invoked by controllers in the current repository, so field-level authorization is only partially centralized.
- CSRF is enabled for browser flows, but `SecurityConfig` excludes all `/api/**` routes from CSRF. Because QTracker is a session-based browser application, that exemption should be explicitly accepted as a compensating-control decision if it remains intentional.

## 4. Security Testing Mapping

| Requirement summary | Where QTracker satisfies or addresses it | Status |
|---|---|---|
| Test authentication, public route exposure, CSRF behavior, and filter composition | `SecurityConfigIntegrationTest` validates redirection of unauthenticated API calls, access to public endpoints, CSRF enforcement on login, successful login session population, presence of `CorrelationIdFilter`, presence of `RateLimitingFilter`, and the `X-Correlation-Id` header. | Fully compliant |
| Test login lockout and credential handling | `LoginAttemptServiceTest` validates lockout threshold, lock expiration, and reset on success. `DevAuthenticationProviderTest` validates successful authentication, bad credentials, and locked-user denial. | Fully compliant |
| Test rate limiting behavior | `RateLimitingFilterTest` validates that login attempts are blocked with HTTP 429 after the configured threshold and that separate buckets apply per path and method. | Fully compliant |
| Test object- and function-level authorization outcomes | `AuthorizationPolicyTest`, `ControlControllerSecurityTest`, and `ApiSecurityMockMvcIT` validate allowed and forbidden access for read, create, edit, and workflow transition scenarios. | Fully compliant |
| Test attachment and audit behavior | `FileAttachmentControllerTest` validates upload limits and audit logging for add/remove actions. `ControlTabsControllerAuditTest` validates that audit entries are written only when changes occur and that forbidden edits return 403. | Fully compliant |
| Test API error handling | `GlobalExceptionHandlerTest` validates 403 and validation error handling behavior. | Fully compliant |
| Maintain end-to-end security coverage through the real filter chain | `SecurityConfigIntegrationTest` runs with the Spring Security chain enabled, but `ApiSecurityMockMvcIT` explicitly uses `@AutoConfigureMockMvc(addFilters = false)`, so not all security tests execute through the full filter chain. | Partially compliant |
| Run automated security scanning as part of build or pipeline | The repository contains no CI/CD configuration and `pom.xml` contains no SAST, dependency scanning, or container scanning plugin definitions. | Requires BO/NITSO decision |
| Maintain DAST / penetration / manual security verification evidence | No repository evidence of DAST scripts, penetration test artifacts, or manual test sign-off was found. | Requires BO/NITSO decision |
| Enforce release security gates in CI/CD | No `.github/`, Azure Pipelines, Jenkinsfile, or similar pipeline definitions were found in the repository. | Requires BO/NITSO decision |

### Security Testing Observations

- The repository has meaningful regression coverage for recently implemented security controls, especially authentication, lockout, rate limiting, control authorization, workflow restrictions, audit logging, and exception handling.
- The current evidence supports unit and integration testing of application logic, not a complete application security testing program.
- There is no repository evidence of dependency vulnerability scanning, image scanning, SAST, DAST, or deployment-time control validation.

## 5. Application Container Security Mapping

| Requirement summary | Where QTracker satisfies or addresses it | Status |
|---|---|---|
| Build application images in a controlled and minimal way | `Dockerfile` uses a multi-stage build: `eclipse-temurin:21-jdk` for build and `eclipse-temurin:21-jre` for runtime. The runtime image only receives the packaged JAR. | Fully compliant |
| Avoid running the application container as root | `Dockerfile` creates `groupadd --system app`, `useradd --system ... app`, changes ownership of `/app/app.jar`, and sets `USER app`. | Fully compliant |
| Avoid privileged mode and host networking | `docker-compose.yml` does not define `privileged: true`, `network_mode: host`, host PID/IPC, or host device mappings for the checked-in services. | Fully compliant |
| Restrict writable volumes to necessary data paths | The app service mounts a named volume only for uploads. The database uses a named data volume. The backup service binds `./backups` to `/backups` for dump retention. | Partially compliant |
| Harden container runtime with read-only root filesystem, dropped capabilities, and no-new-privileges | No such settings are present for the `app` or `db` services in `docker-compose.yml`. The backup service uses `tmpfs` for `/var/lib/postgresql/data`, but that is not a full hardening profile. | Partially compliant |
| Run all containers with explicit least-privilege users | The application image runs as `app`, but `docker-compose.yml` does not specify explicit users for the `db` or `db-backup` services. | Partially compliant |
| Manage image provenance and CVE exposure | Base images are named explicitly (`eclipse-temurin:21-jdk`, `eclipse-temurin:21-jre`, `postgres:17`, `postgres:17-alpine`), but they are not pinned by digest and the repository contains no image scanning workflow or CVE gate. | Partially compliant |
| Keep secrets out of images and externalize runtime configuration | `docker-compose.yml` uses `.env` and `env_file`; `application-dev.yml`, `application-stage.yml`, and `application-prod.yml` externalize datasource and file storage settings to environment variables. | Partially compliant |
| Implement backup operations for stateful data | `db-backup` runs `pg_dump` daily via `crond`, writes gzip-compressed dumps to `/backups`, and deletes files older than 30 days. The service waits for a healthy database before running. | Partially compliant |
| Keep architecture and deployment documentation aligned with runtime | `docs/Solution Architecture.md` states that runtime hardening includes read-only filesystem, dropped capabilities, and tmpfs, but those controls are not present in `docker-compose.yml` for the application service. | Partially compliant |

### Container Security Observations

- The application image is meaningfully hardened compared with the rest of the deployment definition because it runs as a dedicated non-root user.
- The checked-in Compose file does not show privileged containers or host networking, which is positive.
- The repository does not show image digest pinning, image scanning, non-root enforcement for every service, or Compose-level controls such as `read_only`, `cap_drop`, `security_opt: no-new-privileges:true`, or explicit resource-limited tmpfs use for the app service.
- Backup automation exists and is operationally useful, but there is no repository evidence of backup encryption, offsite retention, integrity verification, or recovery testing.

## 6. Solution Acquisition, Development & Maintenance Mapping

| Requirement summary | Where QTracker satisfies or addresses it | Status |
|---|---|---|
| Maintain solution architecture documentation | `docs/Solution Architecture.md` documents system layers, environments, security architecture, modules, data stores, and deployment topology. | Partially compliant |
| Maintain data flow documentation | `docs/Data Flow Diagram.md` provides level 0 and level 1 flow diagrams plus narrative flow descriptions covering security, controllers, services, repositories, schedulers, file storage, and OAuth2 path. | Fully compliant |
| Maintain API documentation | `docs/API Documentation.md` documents endpoint groups, authentication expectations, and request/response patterns. | Fully compliant |
| Separate environments and externalize configuration | `application-dev.yml`, `application-stage.yml`, `application-prod.yml`, and `SecurityConfig` profiles (`dev`, `ssodev`) show environment separation and externalized runtime properties. | Fully compliant |
| Use secure application frameworks and dependency management | `pom.xml` uses Spring Boot starters for Security, Validation, Web, JPA, Mail, and Actuator, plus Maven Wrapper and Flyway migrations. | Fully compliant |
| Implement secure coding controls aligned with OWASP-style practices | Evidence includes Spring Security, BCrypt password encoding, login lockout, session fixation protection, security headers, correlation IDs, exception handling, input validation, and filename/folder sanitization in `FileStorageService`. | Partially compliant |
| Keep access control design reflected in implementation | `AuthorizationPolicy`, `ControlPermissionService`, `PermissionController`, dashboard checks, and workflow transition checks reflect a real permission model. | Partially compliant |
| Maintain auditability for sensitive changes | `AdminAuditService.logAction()` and `logActionWithChanges()` persist administrative actions and changed field snapshots. Control update, control tabs, and attachment flows call these methods. `WorkflowTransitionController` and `WorkflowController` also persist workflow history. | Fully compliant |
| Maintain secure development and delivery process controls | No repository evidence of CI/CD workflows, protected deployment stages, approval gates, or automated security scans was found. | Requires BO/NITSO decision |
| Maintain documentation accuracy for deployed controls | Architecture and API documents exist, but some claims are broader than the checked-in runtime configuration, especially around container hardening. | Partially compliant |

### SDLC Observations

- QTracker has the core SDLC artifacts expected for an internal application: architecture, API, and data flow documentation, environment-specific configuration, migrations, and automated tests.
- The main SDLC weaknesses are not design absence but process evidence absence: there is no checked-in pipeline, no checked-in security gate configuration, and some documentation currently overstates runtime hardening.

## 7. Known Gaps or BO/NITSO Decisions Required

1. Blanket CSRF exclusion for `/api/**` is configured in `SecurityConfig` even though QTracker is a session-based browser application. This should either be narrowed or explicitly accepted with compensating controls.
2. `NotificationApiController.getNotifications()` accepts arbitrary `userId` input and does not call `AuthorizationPolicy.checkUserAccess()`. Any authenticated caller can request another user’s notifications if they know the ID.
3. `UserController` and `RoleController` return user and role metadata to any authenticated caller. If the standards require least-privilege user enumeration, this needs tighter role scoping.
4. `FileAttachmentController` does not use `AuthorizationPolicy.checkAttachmentAccess()` or `ControlPermissionService` for upload, download, view, info, or delete operations. Attachment access is therefore weaker than control and workflow access.
5. `AuthorizationPolicy.filterReadableFields()` and `validateEditableFields()` exist, but controller usage is not evident in the repository. Property-level restrictions are therefore only partially centralized.
6. The repository contains no CI/CD definition and no evidence of SAST, dependency scanning, DAST, or image scanning gates.
7. The checked-in architecture document states runtime hardening controls that are not actually present in `docker-compose.yml` for the app service.
8. The backup service provides daily dumps and 30-day retention, but there is no repository evidence of backup encryption, recovery testing, or offsite replication.
9. The application container runs as non-root, but the database and backup services do not declare explicit runtime users in Compose.
10. `AuthController.post /login` still contains a legacy plaintext-password comparison fallback if a stored password is not BCrypt. The active `dev` security chain uses `DevAuthenticationProvider`, but the controller code is still present and should not remain as an accepted authentication pattern without explicit justification.

## 8. Optional Hardening Recommendations

1. Apply `AuthorizationPolicy` consistently to attachment, notification, user, and role endpoints. In particular, derive notification context from the authenticated principal instead of a caller-supplied `userId`.
2. Narrow CSRF exclusions to only those endpoints that genuinely cannot use CSRF tokens, or document compensating controls and formal BO/NITSO acceptance for the current blanket `/api/**` exclusion.
3. Add pipeline-backed security controls: unit/integration tests, dependency vulnerability scan, SAST, and container image scan as mandatory pre-release gates.
4. Harden Compose services with controls such as `read_only`, `cap_drop`, `security_opt: no-new-privileges:true`, explicit `user`, and carefully scoped writable mounts where compatible with the runtime.
5. Pin base images by digest and document the patching cadence for `eclipse-temurin` and `postgres` images.
6. Update `docs/Solution Architecture.md` so documented runtime hardening reflects the actual checked-in deployment definition.
7. Remove or disable the legacy plaintext password comparison path in `AuthController` if it is no longer part of the supported runtime authentication model.
8. Document backup encryption, restore testing, and retention ownership if those controls are handled outside this repository.
