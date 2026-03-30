# Technical Design Document (TDD) for QTracker

## 1. System Overview: Purpose of QTracker
**QTracker** is a monolithic enterprise web application for operational & compliance control lifecycle management.

**Features:**
* Full control workflow: create → review → approve → complete/return
* Role-based access: `FACILITATOR`, `CONTROL_OPERATOR`, `SOQM_LEAD`, `PROCESS_OWNER`, `ADMIN`
* Control data quality and audit trail
* Notifications and reminder scheduling
* File attachments per control
* Dashboard: status, component breakdown, frequency view, overdue trend

**Primary users:** Auditors, internal control owners, risk managers, operations team.

**Major functional modules:**
* Persistence (JPA + Postgres + Flyway)
* Business service layer (ControlService, authorization policy, workflow)
* API layer (REST & MVC endpoints)
* Security (dev login + SSO prototype)
* Notification (email + in-app)
* File storage (uploads path in container)
* Scheduled reminders (day-based workflows)

---

## 2. Tech Stack
* **Java:** 25 (explicit value in `pom.xml` and Docker base image `eclipse-temurin:25`)
* **Spring Boot:** 3.5.7 (`spring-boot-starter-parent` in `pom.xml`)
* **Database:** PostgreSQL (postgres:17 in Docker Compose), Flyway migration configured
* **ORM:** `spring-boot-starter-data-jpa`, Hibernate
* **Web:** `spring-boot-starter-web`, Thymeleaf
* **Security:** `spring-boot-starter-security`, `spring-security-crypto`
* **Email:** `spring-boot-starter-mail`
* **Documentation / Monitoring:** `spring-boot-starter-actuator`
* **Build:** Maven with static code scanning plugins (PMD, dependency-check, cyclonedx)

---

## 3. Architecture: Backend-Database Interaction
**Pattern:** Spring Boot MVC + REST, layered (Controllers, Services, Repositories, Entities).

**Persistence:**
* Entities mapped with JPA `@Entity`, mostly `GenerationType.IDENTITY`
* Repositories use Spring Data JPA and custom queries.

**Workflow and Status:**
* `Control` holds lifecycle values (`controlStatus`, `performanceStatus`).
* `WorkflowStep`, `WorkflowHistory`, `ControlNotificationLog` manage audit, approvals, and transitions.

**Security Profile:**
* `dev`: local form login with DB userstore via `DevAuthenticationProvider`, session-managed.
* `ssodev`: `oauth2Login` configured in `SecurityConfig`.

---

## 4. Data Model 
*(Key @Entity classes mapping to PostgreSQL tables)*

* **AdminAuditLog** (`admin_audit_log`): Tracks admin actions, previous/new values, IP address, user agent.
* **Control** (`controls`): Main entity tracking frequency, category, components, deadlines, and operational status.
* **ControlAssignment**: Tracks roles (`facilitator`, `controlOperator`, `soqmLead`, etc.) stored as JSON arrays.
* **ControlNotificationLog** (`control_notification_log`): Manages scheduled dates for notifications.
* **Notification** (`notifications`): In-app user notifications (read/unread status).
* **User** (`users`): System users with roles, emails, and authentication details.
* **WorkflowHistory** (`workflow_history`): Audit trail of all workflow transitions and comments.
* **WorkflowStep** (`workflow_steps`): Current assignments and step statuses.

---

## 5. API Specification (REST Endpoints Summary)
* **Auth:** `GET /login`, `POST /login`, `GET /logout`
* **Controls:** `/api/controls` (CRUD operations, export to Excel, changelogs)
* **Dashboards:** `/api/dashboard/admin`, `/api/dashboard/my` (Status, component breakdown, overdue trends)
* **File Attachments:** `/api/attachments` (Upload, download, view, delete)
* **Notifications:** `/api/notifications`
* **Performance & Permissions:** `/api/performance`, `/api/permissions`
* **Workflow:** `/api/workflow` (Approve, return, submit to roles, status checks)

---

## 6. Infrastructure: Dockerization

**Dockerfile (Multi-stage build):**
* Build stage: `eclipse-temurin:25-jdk`
* Runtime stage: `eclipse-temurin:25-jre`
* Healthcheck: `curl -fsS http://127.0.0.1:8080/actuator/health`
* Security: Non-root runtime (user `app`)

**docker-compose.yml:**
* **db service:** `postgres:17` with volume `qtracker_pgdata` and `pg_isready` healthcheck.
* **app service:** Builds from local Dockerfile, depends on healthy DB, uses read-only root FS and `cap_drop: ALL` for enhanced security. Port 8080 mapped to host.

---

## 7. External Integrations

**SMTP (Email Notifications):**
* Configured in `application.yml` (defaulting to Outlook SMTP, port 587, STARTTLS).
* Override via environment variables (`SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, etc.).
* Email usage exists in notification services and scheduled workflow state changes.

**SSO / OAuth2 (Corporate Authentication):**
* Profile `ssodev` enables OAuth2 login in `SecurityConfig`.
* Requires corporate IdP integration via `issuer-uri` and mapping of token claims to user sessions/roles.
* Server is required to securely host the callback URIs and manage OAuth2 handshakes.