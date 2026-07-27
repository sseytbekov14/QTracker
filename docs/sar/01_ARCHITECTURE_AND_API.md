# QTracker — Security Assessment Report
## Document 01: Architecture, API Inventory, Authentication & Session Security
### Sections: 1 (Application Overview), 5 (Authentication), 6 (Authorization / RBAC), 7 (Session Management), 8 (File Upload Security)

**Document Version:** 1.0
**Prepared for Environment:** STAGE
**Date:** 2026-07-24
**Classification:** INTERNAL — RESTRICTED

---

## 1. Application Overview

**Application Name:** QTracker
**Purpose:** Internal quality-management tracking system for managing, reviewing, and approving operational controls within the KPMG System of Quality Management (SoQM) framework. The application supports the full control lifecycle — from creation and role-based assignment through multi-step workflow review to final completion and audit export.

**Technology Stack:**

| Layer | Technology | Version |
|---|---|---|
| Runtime | Java (OpenJDK) | 21 (LTS) |
| Application Framework | Spring Boot | 3.5.7 |
| Security Framework | Spring Security | 6.x (managed by Boot 3.5.7 BOM) |
| Web Layer | Spring MVC + Thymeleaf | Embedded in Boot 3.5.7 |
| Persistence | Spring Data JPA (Hibernate) | Embedded in Boot 3.5.7 |
| Database | PostgreSQL | 17 |
| Schema Migrations | Flyway | Managed by Boot 3.5.7 BOM |
| API Documentation | SpringDoc OpenAPI | 2.8.6 |
| Report Generation | Apache POI (OOXML) | 5.2.3 |
| Build Tool | Apache Maven | 3.x (mvnw wrapper) |
| Containerization | Docker / Docker Compose | — |
| Email (Notifications) | Spring Boot Mail (JavaMail) | Embedded in Boot 3.5.7 |

---

## 2. Software Bill of Materials (SBOM) — Third-Party Dependencies

*Physical evidence supporting this section: **ART-01** (project's pom.xml + full `./mvnw dependency:tree` output) — see Document 03, Appendix A.*

All dependencies are declared in the project's pom.xml and version-managed via the `spring-boot-starter-parent:3.5.7` BOM sourced from Maven Central.

### 2.1 Production Dependencies

| GroupId | ArtifactId | Version | Purpose |
|---|---|---|---|
| `org.springframework.boot` | `spring-boot-starter-parent` | **3.5.7** | BOM parent — governs all Spring managed versions |
| `org.springframework.boot` | `spring-boot-starter-web` | (managed) | Embedded Tomcat, Spring MVC, REST support |
| `org.springframework.boot` | `spring-boot-starter-security` | (managed) | Spring Security — auth, CSRF, headers, filter chain |
| `org.springframework.boot` | `spring-boot-starter-data-jpa` | (managed) | JPA/Hibernate ORM layer |
| `org.springframework.boot` | `spring-boot-starter-thymeleaf` | (managed) | Server-side HTML templating |
| `org.springframework.boot` | `spring-boot-starter-actuator` | (managed) | Health check endpoint (`/actuator/health`) |
| `org.springframework.boot` | `spring-boot-starter-mail` | (managed) | Email notification delivery (JavaMail) |
| `org.springdoc` | `springdoc-openapi-starter-webmvc-ui` | **2.8.6** | OpenAPI 3.0 spec generation + Swagger UI |
| `org.apache.poi` | `poi-ooxml` | **5.2.3** | Excel report generation (.xlsx) |
| `org.flywaydb` | `flyway-core` | (managed) | Database schema migration engine |
| `org.flywaydb` | `flyway-database-postgresql` | (managed, runtime) | Flyway PostgreSQL dialect support |
| `org.postgresql` | `postgresql` | (managed, runtime) | JDBC driver for PostgreSQL 17 |
| `org.projectlombok` | `lombok` | (managed, optional) | Compile-time code generation (excluded from JAR) |

All `(managed)` versions are resolved from the Spring Boot 3.5.7 BOM, which pins dependency versions to a tested, compatible set. The BOM version (3.5.7) is the single version to track for upstream CVE advisories.

### 2.2 Test-Only Dependencies (not shipped to production)

| GroupId | ArtifactId | Version | Purpose |
|---|---|---|---|
| `org.springframework.boot` | `spring-boot-starter-test` | (managed) | JUnit 5, Mockito, MockMvc |
| `org.springframework.security` | `spring-security-test` | (managed) | Security context test utilities |
| `com.h2database` | `h2` | (managed) | In-memory DB for unit tests |

### 2.3 Build Plugins

| Plugin | Version | Purpose |
|---|---|---|
| `maven-compiler-plugin` | (managed) | Java 21 compilation with Lombok annotation processor |
| `maven-surefire-plugin` | (managed) | Unit test runner (JUnit) |
| `jacoco-maven-plugin` | **0.8.12** | Code coverage reporting |
| `spring-boot-maven-plugin` | (managed) | Fat-JAR packaging, Lombok excluded from runtime |

---

## 3. API Inventory

### 3.1 Public (Unauthenticated) Endpoints

| HTTP Method | Path | Controller | Description |
|---|---|---|---|
| `GET` | `/login` | `AuthController` | Login page (form-based) |
| `POST` | `/login` | Spring Security | Credential submission handler |
| `GET` | `/logout` | `AuthController` | Session invalidation + redirect |
| `GET` | `/actuator/health` | Spring Actuator | Health probe (no auth required) |
| `GET` | `/error` | Spring MVC | Error dispatch page |
| `GET` | `/images/**`, `/css/**`, `/js/**`, `/webjars/**`, `/favicon.*` | Static | Static assets |
| `GET` | `/v3/api-docs`, `/v3/api-docs/**` | SpringDoc | OpenAPI 3.0 JSON spec |
| `GET` | `/swagger-ui/**`, `/swagger-ui.html` | SpringDoc | Swagger UI |

### 3.2 Authenticated REST API Endpoints (`/api/**`)

All endpoints below require an active authenticated session. Unauthenticated requests return `401 Unauthorized`.

#### 3.2.1 Controls (`/api/controls`)

| Method | Path | Controller | Allowed Roles | Description |
|---|---|---|---|---|
| `GET` | `/api/controls` | `ControlController` | All authenticated | List all controls |
| `POST` | `/api/controls` | `ControlController` | `SOQM_TEAM` only | Create a new control |
| `GET` | `/api/controls/{id}` | `ControlController` | Assigned / SOQM_TEAM | Get control by DB ID |
| `PUT` | `/api/controls/{id}` | `ControlController` | Assigned users (role-filtered) | Update control fields |
| `DELETE` | `/api/controls/{id}` | `ControlController` | `SOQM_TEAM` only | Delete control |
| `GET` | `/api/controls/user/{email}` | `ControlController` | All authenticated | Controls assigned to user |
| `GET` | `/api/controls/component/{component}` | `ControlController` | All authenticated | Controls by component |
| `GET` | `/api/controls/generate-id` | `ControlController` | All authenticated | Generate control ID suggestion |
| `GET` | `/api/controls/check-id-unique` | `ControlController` | All authenticated | Validate control ID uniqueness |
| `POST` | `/api/controls/{id}/rename-id` | `ControlController` | `SOQM_TEAM` | Rename control ID |
| `GET` | `/api/controls/{id}/changelog` | `ControlController` | All authenticated | Control change history |
| `GET` | `/api/controls/export/excel` | `ControlController` | `SOQM_TEAM` only | Export controls to .xlsx |
| `GET` | `/api/controls/{id}/export/completed` | `ControlController` | `SOQM_TEAM` + SharedWith | Export completed control to .xlsx |

#### 3.2.2 Workflow (`/api/workflow`)

| Method | Path | Controller | Allowed Roles | Description |
|---|---|---|---|---|
| `POST` | `/api/workflow/perform-action` | `WorkflowController` | Assigned users | Generic workflow action dispatch |
| `GET` | `/api/workflow/{controlId}/status` | `WorkflowController` | Assigned users | Get current workflow status |
| `POST` | `/api/workflow/approve` | `WorkflowController` | Assigned approvers | Approve current step |
| `POST` | `/api/workflow/return` | `WorkflowController` | Assigned approvers | Return step for revision |
| `GET` | `/api/workflow/my-approvals` | `WorkflowController` | All authenticated | List controls pending user's approval |
| `POST` | `/api/workflow/submit-to-process-owner` | `WorkflowController` | `SOQM_TEAM` | Move to PROCESS_OWNER_REVIEW |
| `POST` | `/api/workflow/return-to-operator` | `WorkflowController` | `SOQM_TEAM` | Return to REVIEW (Control Operator) |
| `POST` | `/api/workflow/complete-control` | `WorkflowController` | `PROCESS_OWNER` | Mark control COMPLETED |
| `POST` | `/api/workflow/return-to-soqm-lead` | `WorkflowController` | `PROCESS_OWNER` | Return to SOQM_HEAD_REVIEW |

#### 3.2.3 File Attachments (`/api/attachments`)

| Method | Path | Controller | Allowed Roles | Description |
|---|---|---|---|---|
| `POST` | `/api/attachments/upload/{controlId}` | `FileAttachmentController` | Assigned users | Upload file(s) to control |
| `GET` | `/api/attachments/download/{filename}` | `FileAttachmentController` | Assigned users | Download file |
| `GET` | `/api/attachments/view/{filename}` | `FileAttachmentController` | Assigned users | Inline view (PDF, image) |
| `GET` | `/api/attachments/info/{controlId}` | `FileAttachmentController` | Assigned users | Get attachment metadata |
| `DELETE` | `/api/attachments/delete/{controlId}` | `FileAttachmentController` | Assigned users | Remove attachment |

#### 3.2.4 Users (`/api/users`, `/api/admin`)

| Method | Path | Controller | Allowed Roles | Description |
|---|---|---|---|---|
| `GET` | `/api/users` | `UserController` | All authenticated | List all users (DTO, no passwords) |
| `GET` | `/api/users/{email}` | `UserController` | All authenticated | Get user by email |
| `POST` | `/api/users` | `UserController` | `adminAccess=true` | Create new user |
| `POST` | `/api/users/{id}/access` | `UserController` | `adminAccess=true` | Update user role/access |
| `PUT` | `/api/admin/users/{id}/email` | `UserController` | `adminAccess=true` | Update user email |

#### 3.2.5 Supporting Endpoints

| Method | Path | Controller | Description |
|---|---|---|---|
| `GET` | `/api/roles` | `RoleController` | List available roles |
| `GET` | `/api/notifications` | `NotificationApiController` | Get user notifications |
| `POST` | `/notifications/mark-all-read` | View controller | Mark notifications read |
| `GET` | `/api/workflow-transitions` | `WorkflowTransitionController` | Workflow transition definitions |
| `GET` | `/api/permissions` | `PermissionController` | User permission query |
| `GET` | `/api/performance` | `PerformanceController` | Performance metrics |
| `GET` | `/api/dashboard` | `DashboardController` | Dashboard summary data |

### 3.3 OpenAPI Specification

*Physical evidence supporting this section: **ART-02** (exported `openapi.json` / `openapi.yaml` from STAGE instance) and **ART-03** (Swagger UI full-screen screenshot) — see Document 03, Appendix A.*

The application ships with SpringDoc `springdoc-openapi-starter-webmvc-ui:2.8.6`. The machine-readable OpenAPI 3.0 specification is available at runtime at:

```
GET /v3/api-docs
GET /v3/api-docs.yaml
```

Swagger UI is available at `/swagger-ui/index.html`. Both endpoints are permitted without authentication in `SecurityConfig` to support internal developer tooling on the closed corporate network.

---

## 4. Authentication Architecture

### 4.1 SSO / OAuth 2.0 Integration — Status: Pending Infrastructure Provisioning

The application code is fully prepared for enterprise SSO via OAuth 2.0 / OpenID Connect (OIDC) using Microsoft Entra ID. A dedicated Spring Security profile `ssodev` is implemented in `SecurityConfig.java` (the `securityFilterChainSso` bean, activated by `@Profile("ssodev")`) which configures `.oauth2Login(Customizer.withDefaults())`.

The SSL certificate and IdP configuration parameters (client ID, tenant ID, OIDC discovery URL) have been formally requested from IT Infrastructure (Jira Ticket: #INFRA-10482 — Pending Provisioning). Until provisioning is complete, the STAGE environment operates with form-based local authentication inside a closed, isolated corporate network perimeter with no public internet exposure. This is an accepted interim compensating control documented in the risk register (Section 15, Risk #1).

**SSO Architecture — Target State (`ssodev` / Production Profile):**

```
Browser → HTTPS → QTracker App → OIDC Redirect → Microsoft Entra ID (IdP)
                                                  ↓ (ID Token + Access Token)
                                       QTracker validates token via JWKS
                                                  ↓
                                     User record resolved by entra_oid field
                                     (users.entra_oid = Entra Object ID)
```

**Password storage in SSO mode:** The `users` table contains a `password` column populated only in `dev`/`stage` local-auth mode (BCrypt hash). Under the `ssodev` profile, authentication is entirely delegated to Microsoft Entra ID. QTracker receives a validated OIDC ID Token — no password material is transmitted to or stored by QTracker. User identity is matched via the `entra_oid` field (Entra Object ID), which is a non-secret identifier. The QTracker database stores no user credentials of any kind in SSO mode.

### 4.2 Local / STAGE Authentication (`dev` and `stage` profiles)

For the STAGE environment (Spring profile: `stage`), Spring Security activates the `securityFilterChainDev` bean using `DevAuthenticationProvider` — a custom form-based authenticator.

**Authentication flow:**
1. User submits credentials to `POST /login`
2. `RateLimitingFilter` enforces login rate limit: max **20 POST requests per 60 seconds** per source IP
3. `LoginAttemptService` evaluates account lockout state: max **5 consecutive failed attempts** triggers a **15-minute lockout**
4. `DevAuthenticationProvider` loads the user record from the database via `UserPrincipalService`
5. Password verified using **BCrypt** (`PasswordEncoder` bean, `PasswordConfig`)
6. On success: `lastLoginAt` updated in database; HTTP session populated with `currentUser` and `userRole` attributes
7. On failure: failure counter incremented; generic error message returned — no enumeration of lock state or account existence

**Account status enforcement:**
- `UserEnabledGuardFilter` re-reads the `enabled` flag from the database on every authenticated request, enforcing real-time account revocation without requiring a session restart
- `DisabledException` and `LockedException` produce the same user-facing error message, preventing lockout state enumeration

### 4.3 Password Storage

| Attribute | Value |
|---|---|
| Algorithm | BCrypt |
| Strength (log rounds) | 10 (Spring Security default) |
| Storage column | `users.password` (BCrypt hash only) |
| Plaintext exposure | None — credential is verified and immediately discarded from the `Authentication` object |
| SSO mode | `users.password` column not used; authentication fully delegated to Microsoft Entra ID |

---

## 5. Authorization (RBAC) Matrix

### 5.1 Role Definitions

| Role Name (DB value) | Display Name | Description |
|---|---|---|
| `SOQM_TEAM` | SoQM Head / SoQM Delegate | Creates controls, manages full workflow, global read access, exports data, administers the system |
| `CONTROL_OPERATOR` | Control Operator | Executes assigned controls, completes required process steps, submits to SoQM for review |
| `PROCESS_OWNER` | Process Owner | Final approver — reviews controls submitted by SoQM Team, completes or returns for revision |
| `FACILITATOR` | Facilitator | Initiates control workflow, submits to Control Operator |
| *(Admin flag)* | Admin | `admin_access = true` flag on the `User` entity grants cross-cutting user management privileges; orthogonal to the role field |

The `FACILITATOR` role is used in workflow assignment. The `SOQM_TEAM` role corresponds to the SoQM Head/Delegate function across all documentation.

### 5.2 RBAC Permission Matrix

| Action | SOQM_TEAM | CONTROL_OPERATOR | PROCESS_OWNER | FACILITATOR | Admin |
|---|---|---|---|---|---|
| View all controls | ✅ | ❌ assigned only | ❌ assigned only | ❌ assigned only | ✅ |
| Create control | ✅ | ❌ | ❌ | ❌ | ✅ |
| Edit control fields | ✅ all | ✅ limited | ✅ PO comments only | ✅ limited | ✅ |
| Modify SoQM Comments | ✅ | ❌ | ❌ | ❌ | ✅ |
| Modify Process Owner Comments | ❌ | ❌ | ✅ | ❌ | ✅ |
| Delete control | ✅ | ❌ | ❌ | ❌ | ✅ |
| Export controls (bulk) | ✅ | ❌ | ❌ | ❌ | ✅ |
| Export completed control | ✅ | ❌ | ❌ | ❌ unless SharedWith | ✅ |
| Submit to Control Operator | ✅ | ❌ | ❌ | ✅ | ✅ |
| Submit to SoQM Review | ❌ | ✅ assigned | ❌ | ❌ | ✅ |
| Submit to Process Owner | ✅ | ❌ | ❌ | ❌ | ✅ |
| Complete control | ❌ | ❌ | ✅ assigned | ❌ | ✅ |
| Return to Facilitator | ✅ | ✅ | ✅ | ❌ | ✅ |
| Return to Operator | ✅ | ❌ | ❌ | ❌ | ✅ |
| Return to SoQM Team | ❌ | ❌ | ✅ | ❌ | ✅ |
| Manage users (create/update) | ❌ | ❌ | ❌ | ❌ | ✅ |
| Upload attachments | ✅ | ✅ assigned | ✅ assigned | ✅ assigned | ✅ |
| Download attachments | ✅ | ✅ assigned | ✅ assigned | ✅ assigned | ✅ |

### 5.3 Workflow Status Transition Matrix

```
DRAFT ──[SUBMIT_FOR_REVIEW / INITIATE]──────────────► IN_PROGRESS
  │                                                         │
  │                                                  [SUBMIT_TO_CONTROL_OPERATOR]
  │                                                         │
  │                                                         ▼
  │                                                      REVIEW
  │                                                    ╱        ╲
  │               [RETURN_TO_FACILITATOR]◄────────────╯    [SUBMIT_FOR_SOQM / SUBMIT_SOQM]
  │                                                              │
  │                                                              ▼
  │                                                    SOQM_HEAD_REVIEW
  │                                                    ╱              ╲
  │            [SEND_BACK_TO_OPERATOR]◄────────────────╯       [SEND_TO_PROCESS_OWNER / SOQM_COMMENT]
  │                                                                     │
  │                                                                     ▼
  │                                                          PROCESS_OWNER_REVIEW
  │         [RETURN_TO_FACILITATOR / REJECT]◄──────────────╱    │    ╲
  └──────────────────────────────────────────────────────────   │     [SEND_FOR_REVISION]──► REVIEW
                                                           [COMPLETE]
                                                                │
                                                                ▼
                                                           COMPLETED
```

**Workflow Role Mapping:**

| Status | Active Role | Permitted Actions |
|---|---|---|
| `DRAFT` | `SOQM_TEAM` / `FACILITATOR` | SUBMIT_FOR_REVIEW, INITIATE |
| `IN_PROGRESS` | `FACILITATOR` | SUBMIT_TO_CONTROL_OPERATOR |
| `REVIEW` | `CONTROL_OPERATOR` | SUBMIT_FOR_SOQM, RETURN_TO_FACILITATOR |
| `SOQM_HEAD_REVIEW` | `SOQM_TEAM` | SEND_TO_PROCESS_OWNER, SEND_BACK_TO_OPERATOR |
| `PROCESS_OWNER_REVIEW` | `PROCESS_OWNER` | COMPLETE, RETURN_TO_FACILITATOR, SEND_FOR_REVISION, REJECT |
| `COMPLETED` | — | Read-only; export available to SOQM_TEAM and SharedWith users |

### 5.4 Data Segregation

Data segregation between users is enforced at two independent levels.

**Level 1 — Service Layer (`ControlPermissionService`, `AuthorizationPolicy`):**
Every API call that reads or modifies a control resolves a `ControlPermission` object via `controlPermissionService.resolve(control, currentUser)`. This service queries `ControlAssignment` to determine whether the current user is present in the `facilitator`, `controlOperator`, `soqmLead`, `processOwner`, or `controlSharedWith` lists. `SOQM_TEAM` users have global read access; all other roles are restricted to their assigned controls.

**Level 2 — Field-level Isolation:**
- `AuthorizationPolicy.filterReadableFields()` strips `soqmHeadComments` from DTO responses for `FACILITATOR` and `CONTROL_OPERATOR` roles
- `ControlController.updateControl()` enforces role-based write restrictions: only `SOQM_TEAM` may write `soqmHeadComments`; only `PROCESS_OWNER` may write `processOwnerComments`
- `AuthorizationPolicy.validateEditableFields()` throws `AccessDeniedException` on any attempt to modify a restricted field

Row-level security is not applied at the database layer. Isolation is enforced entirely by the application service layer. The database uses a single application-level credential; no per-user row restrictions exist at the DBMS level.

---

## 6. Session Management

| Attribute | Configuration |
|---|---|
| Session mechanism | Java Servlet HTTP Sessions (embedded Tomcat) |
| Session fixation protection | `sessionFixation().migrateSession()` — new session ID issued on login, prior session invalidated |
| Session timeout | 30 minutes of inactivity (Spring Boot / Tomcat default) |
| Session cookie flags | `JSESSIONID` — `HttpOnly` by default (Tomcat); `Secure` flag active when TLS is enabled |
| CSRF protection | `CookieCsrfTokenRepository.withHttpOnlyFalse()` — token validated on all state-changing requests; exempted for `/api/**` (REST, session-authenticated) and `/notifications/mark-all-read` |
| Logout / Session invalidation | `GET /logout` removes `currentUser` session attribute and redirects to `/login` |
| Real-time session revocation | `UserEnabledGuardFilter` re-queries `users.enabled` from the database on every request — account deactivation takes effect immediately without requiring session restart |
| Correlation ID | `CorrelationIdFilter` generates a UUID per request, placed into MDC for log tracing, returned as `X-Correlation-Id` response header |

QTracker does not use JWT tokens for session management. Session state is maintained server-side exclusively via the HTTP session, eliminating the risk of token theft combined with inability to revoke.

### 6.1 HTTP Security Headers

Configured in `SecurityConfig` for both `dev/stage` and `ssodev` profiles:

| Header | Value |
|---|---|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `SAMEORIGIN` |
| `X-XSS-Protection` | `1; mode=block` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | `geolocation=(), microphone=(), camera=()` |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self' 'unsafe-inline' https:; style-src 'self' 'unsafe-inline' https:; img-src 'self' data: blob: https:; font-src 'self' data: https:; connect-src 'self' https: ws: wss:; object-src 'none'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'` |

---

## 7. File Upload Security

File uploads are handled by `FileAttachmentController` (REST) and stored and retrieved via `FileStorageService`.

### 7.1 Path Traversal Prevention

`FileStorageService` applies two sanitization functions before constructing any file system path:

```java
// Filename sanitization — retains only alphanumeric, dot, underscore, dash
private String sanitizeFilename(String filename) {
    return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
}

// Folder name sanitization — same character whitelist applied to control subdirectory name
private String sanitizeFolderName(String folder) {
    return folder.replaceAll("[^a-zA-Z0-9._-]", "_");
}
```

Files are stored in isolated, control-specific subdirectories under the path configured by `file.upload.dir`. The subdirectory name is derived from the control's business ID and sanitized prior to use, preventing directory traversal via crafted input.

### 7.2 File Count Limit

`FileAttachmentController` enforces a maximum of **50 files per attachment type** (Details / Documents) per control:

```java
if (existingCount + incomingCount > 50) {
    return ResponseEntity.badRequest().body("Maximum 50 files allowed...");
}
```

### 7.3 MIME Type Handling

`FileStorageService.getMimeType()` uses extension-based MIME type detection (whitelist). Supported extensions: `.pdf`, `.doc`, `.docx`, `.xls`, `.xlsx`, `.png`, `.jpg`, `.jpeg`, `.gif`, `.txt`, `.zip`. Files with unrecognized extensions receive `application/octet-stream`, forcing browser download rather than inline rendering.

### 7.4 Access Control for Downloads

Download and view endpoints (`GET /api/attachments/download/{filename}`, `GET /api/attachments/view/{filename}`) require an active authenticated session. `AuthorizationPolicy.checkAttachmentAccess()` resolves the control that owns the requested file and verifies the requesting user is assigned to that control via `ControlPermission` before serving content.

### 7.5 Audit Logging for Attachments

All file upload and deletion events are persisted to the `admin_audit_log` table via `AdminAuditService.logActionWithChanges()` with action types `ATTACHMENT_ADDED` and `ATTACHMENT_REMOVED`, capturing user email, display name, control ID, and file name.

### 7.6 Known Limitations (STAGE)

- **File size limit:** No application-layer file size cap is currently configured. Noted for remediation prior to Production go-live. Currently mitigated by network isolation and the 50-file count limit.
- **File content validation:** MIME type is determined by file extension only; magic-byte inspection is not performed. Noted for remediation prior to Production go-live. Currently mitigated by network isolation and the extension whitelist enforced at the MIME-type resolution layer.
