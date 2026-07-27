# QTracker — Security Assessment Report
## Document 03: Evidence Package & Security Questionnaire Responses
### Sections: 2 (CI/CD & Deployment), 3 (Infrastructure), 10 (Host Security), 12 (Backup & DR), 13 (Security Testing), 14 (Incident Management), 15 (Compliance)

**Document Version:** 1.0
**Prepared for Environment:** STAGE
**Date:** 2026-07-24
**Classification:** INTERNAL — RESTRICTED

---

## SECTION 2 — Software Development Lifecycle (SDLC) & CI/CD

### Q2.1 — Version control and source code hosting

The QTracker source code is managed using Git version control, hosted on an internal GitLab instance within the corporate network. The repository is not publicly accessible. All developer interactions with the repository occur over the internal GitLab server.

### Q2.2 — CI/CD pipeline stages

The pipeline is defined in `.gitlab-ci.yml` and executed on an internal GitLab Runner tagged `gitlab-runner-kubernetes`. It comprises two automated stages:

**Stage 1 — Build (`build_job`):**
- Base image: `eclipse-temurin:21-jdk`
- Command: `./mvnw clean compile`
- Artifact: compiled `target/` directory, retained for 1 hour

**Stage 2 — Test (`test_job`):**
- Command: `./mvnw test`
- JUnit XML reports published to GitLab as a test report (`target/surefire-reports/TEST-*.xml`)
- JaCoCo code coverage report (`target/site/jacoco/`) retained for 1 week

Maven dependencies are cached in `.m2/repository` to reduce pipeline duration. Deployment to STAGE is performed manually by an authorized administrator following a successful pipeline run.

### Q2.3 — STAGE deployment procedure

The STAGE environment is deployed using Docker Compose (`docker-compose.yml`) on a single server:

1. GitLab CI pipeline passes (build + test stages)
2. Authorized administrator clones or pulls the latest code to the STAGE server
3. Docker image is built: `docker compose build`
4. Services are started: `docker compose up -d`
5. Flyway database migrations execute automatically on application startup
6. Health check is verified: `GET /actuator/health` returns `{"status":"UP"}`

Environment-specific configuration is supplied via `.env` file. No credentials are committed to the repository.

### Q2.4 — Code review process

All feature branches are submitted as GitLab Merge Requests. At least one peer review approval is required before merging to the main branch. Branch protection rules on GitLab prevent direct commits to the main branch.

### Q2.5 — Static analysis and dependency scanning

- **Compilation lint:** Applied during the Maven `compile` phase
- **Dependency versioning:** `spring-boot-starter-parent:3.5.7` BOM provides curated, security-patched dependency versions; the BOM version is the single version to monitor for CVE advisories
- **Code coverage:** JaCoCo 0.8.12 measures coverage on every pipeline run; reports are retained for review
- **OWASP Dependency-Check and SonarQube:** Integration planned for the next pipeline iteration (see Section 15, Item #5)

---

## SECTION 3 — Infrastructure & Network Security

### Q3.1 — STAGE environment infrastructure

| Component | Description |
|---|---|
| Server | Single physical or virtual machine on corporate infrastructure |
| Operating system | Linux (Docker host) |
| Containerization | Docker and Docker Compose |
| Application container | Spring Boot fat-JAR on `eclipse-temurin:21-jre` base image |
| Database container | PostgreSQL 17 (official Docker image) |
| Networking | Docker bridge network (internal). Application exposes port `8081` on the host; PostgreSQL port `5432` is not exposed |
| File storage | Docker named volume `qtracker_uploads` for attachments; `qtracker_pgdata` for PostgreSQL data |

### Q3.2 — Environment isolation

| Isolation Dimension | DEV | STAGE | Production |
|---|---|---|---|
| Server / VM | Separate | Separate | Separate (planned) |
| Docker Compose configuration | `docker-compose.yml` + `docker-compose.dev.yml` | `docker-compose.yml` only | `docker-compose.yml` + prod overrides |
| Spring profile | `dev` | `stage` | `ssodev` / `prod` |
| Database | Separate PostgreSQL instance | Separate PostgreSQL instance | Separate |
| Data | Synthetic test data | Synthetic test data | Production data |
| PostgreSQL port exposed on host | Yes (DEV only, `docker-compose.dev.yml`) | No | No |
| Authentication mechanism | Local form-based (BCrypt) | Local form-based (BCrypt) | SSO (OAuth2/OIDC — Entra ID) |

### Q3.3 — Internet exposure

The STAGE application is not accessible from the public internet. The server is deployed on the corporate intranet, reachable only from devices connected via corporate network or VPN. No public DNS record or firewall rule permits inbound internet access.

### Q3.4 — Network segmentation and access controls

- **Database isolation:** PostgreSQL listens only on the Docker internal bridge network. External database connections require host-level SSH access, which is governed by corporate IT access controls
- **Application access:** Only port `8081` (HTTP) is exposed on the host; all other ports are blocked at the host firewall
- **Administrative access:** SSH access to the server is managed by corporate IT with key-based authentication; root login is disabled
- **SMTP outbound:** Port 25 to the internal corporate SMTP relay only; no direct internet SMTP routing

---

## SECTION 10 — Host Security

### Q10.1 — Endpoint protection

**Microsoft Defender for Endpoint (MDE)** is deployed on the STAGE host per the corporate endpoint security standard.

| Attribute | Value |
|---|---|
| Endpoint protection platform | Microsoft Defender for Endpoint (MDE) |
| Agent version | 101.24032.0007 |
| Last scan date | 2026-07-20 |
| Real-time protection | Enabled |
| Host hardening baseline | Applied (CIS Linux Benchmark Level 1) |

### Q10.2 — Docker host hardening

- Docker daemon runs as a non-root user per the Docker security hardening guide
- The Spring Boot application process runs as a non-root OS user inside the container
- No privileged containers are used
- The Docker socket is not mounted into application containers
- The `.dockerignore` file ensures that `.env` files, source secrets, and development artifacts are excluded from production images

### Q10.3 — Container image vulnerability scanning

Container image scanning (e.g., Trivy or GitLab Container Scanning) is planned for integration into the CI/CD pipeline prior to Production go-live (see Section 15, Item #3). Current mitigations:

1. Base image `eclipse-temurin:21-jre` (Adoptium) — actively maintained with regular security updates
2. Images are rebuilt on each deployment, pulling the latest base image patches
3. All application dependencies are pinned to the Spring Boot 3.5.7 BOM — a vetted, security-reviewed version set

---

## SECTION 12 — Backup & Disaster Recovery

### Q12.1 — Database backup policy (STAGE)

PostgreSQL 17 runs in Docker on the STAGE server. The following backup configuration is in place:

```bash
# Daily logical dump via pg_dump — executed from the host via cron
0 2 * * * docker exec qtracker_db_1 pg_dump -U ${POSTGRES_USER} -d QTracker \
  | gzip > /backups/qtracker_$(date +\%Y\%m\%d).sql.gz

# Retention: 7 daily backups retained
find /backups -name "qtracker_*.sql.gz" -mtime +7 -delete
```

| Backup Attribute | Value |
|---|---|
| Backup type | PostgreSQL logical dump (`pg_dump`) |
| Backup frequency | Daily (02:00 server local time) |
| Retention period | 7 days |
| Backup location | Host-local volume (`/backups`) |
| Off-site backup | Infrastructure team responsibility — to be confirmed |
| Backup encryption | Planned (GPG encryption of dump files prior to Production go-live) |
| Last backup test | 2026-07-15 (Successful dry-run restore verified on DEV) |

### Q12.2 — RTO and RPO (STAGE)

| Metric | STAGE Target |
|---|---|
| RTO (Recovery Time Objective) | 4 hours — restart application and restore from most recent backup |
| RPO (Recovery Point Objective) | 24 hours — daily backup schedule; up to 24 hours of changes may be lost |

Production RTO and RPO will be formally defined and agreed during the Production go-live security review.

### Q12.3 — Disaster recovery procedure

1. Provision replacement server and install Docker and Docker Compose
2. Clone repository from internal GitLab
3. Copy `.env` file with database credentials and application configuration from secure storage
4. Start database container: `docker compose up -d db`
5. Restore database from backup:
   ```bash
   gunzip -c /backups/qtracker_YYYYMMDD.sql.gz | \
     docker exec -i qtracker_db_1 psql -U ${POSTGRES_USER} -d QTracker
   ```
6. Start application container: `docker compose up -d app`
7. Verify health endpoint: `curl http://localhost:8081/actuator/health`
8. Restore uploaded files from backup of `qtracker_uploads` Docker volume

---

## SECTION 13 — Security Testing

### Q13.1 — Security testing performed

| Activity | Status | Evidence |
|---|---|---|
| Unit tests with Spring Security test utilities | Implemented | CI pipeline JUnit reports (`target/surefire-reports/`) |
| Peer code review via GitLab Merge Request | Ongoing | GitLab MR history |
| Manual security review of authentication and authorization code | Completed | This SAR document |
| Rate limiting and brute-force protection logic review | Completed | `LoginAttemptService`, `RateLimitingFilter` source code review |
| Input sanitization review | Completed | `FileStorageService.sanitizeFilename()` source code review |
| Dependency CVE review | Ongoing | Spring Boot 3.5.7 BOM; OWASP Dependency-Check integration planned |
| External penetration test | Planned — required prior to Production go-live | — |
| OWASP Top 10 assessment | Planned — required prior to Production go-live | — |

### Q13.2 — Built-in security controls

| Control | Implementation | Source |
|---|---|---|
| CSRF protection | `CookieCsrfTokenRepository` applied on all non-API state-changing requests | `SecurityConfig.java` |
| Brute-force / Account lockout | 5 consecutive failures → 15-minute account lockout per username | `LoginAttemptService.java` |
| Rate limiting | Sliding window: 20 req/min login; 20 req/min attachments; 120 req/min API reads and writes | `RateLimitingFilter.java` |
| Path traversal prevention | Filename and folder name sanitized via regex `[^a-zA-Z0-9._-]` → `_` before any file system operation | `FileStorageService.java` |
| Session fixation protection | `migrateSession()` — new session ID on successful login | `SecurityConfig.java` |
| Real-time account revocation | `UserEnabledGuardFilter` re-reads `users.enabled` from database on every authenticated request | `UserEnabledGuardFilter.java` |
| HTTP security headers | HSTS, CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Permissions-Policy | `SecurityConfig.java` |
| SQL injection prevention | Spring Data JPA with parameterized queries throughout; no raw SQL string concatenation | Repository layer |
| Error information disclosure | Generic messages returned to client; full exception detail confined to server-side logs | `AuthController.java`, `GlobalExceptionHandler.java` |
| Structured audit trail | All sensitive actions persisted to `admin_audit_log` with field-level before/after diff | `AdminAuditService.java` |
| Attachment count limit | Maximum 50 files per attachment type per control, enforced before storage | `FileAttachmentController.java` |
| Password hashing | BCrypt with 10 rounds | `PasswordConfig.java` |
| Request correlation tracing | UUID correlation ID per request — propagated to MDC and returned in `X-Correlation-Id` header | `CorrelationIdFilter.java` |

---

## SECTION 14 — Incident Management

### Q14.1 — Incident response procedure

QTracker follows the corporate IT Security Incident Response Policy. The application-specific escalation path is as follows:

1. **Detection:** Security event identified via application log patterns (`auth status=LOCKED`, `auth status=BAD_CREDENTIALS`), HTTP 429 rate-limit responses, or user report
2. **First responder:** Application administrator or server administrator
3. **Triage:** Review `admin_audit_log` for suspicious activity; inspect Docker container logs: `docker compose logs --since=1h app`
4. **Containment:** Disable the affected user account via the Admin UI (`POST /api/users/{id}/access?enabled=false`); the account loses access on the next request without requiring a server restart
5. **Escalation:** Follow the corporate CERT/SOC escalation procedure
6. **Evidence preservation:** Export `admin_audit_log` and `workflow_history` tables before any remediation actions
7. **Recovery:** Restore from the most recent verified backup if data integrity is in question (see Section 12)

### Q14.2 — Account revocation speed

Account deactivation takes effect on the next HTTP request made by the affected user. `UserEnabledGuardFilter` re-reads the `users.enabled` flag from the database on every authenticated request. No session restart or application restart is required. The user receives HTTP 403 and is redirected to `/login?error`.

---

## SECTION 15 — Compliance & Risk Acceptance

### Q15.1 — Known risks and mitigating controls (STAGE)

| # | Risk | Likelihood | Impact | Mitigating Control | Residual Risk | Accepted |
|---|---|---|---|---|---|---|
| 1 | HTTP-only access in STAGE (no TLS) | Low | Medium | Closed corporate perimeter; no internet exposure; HTTPS provisioning in progress (#INFRA-10482) | Low | Accepted for STAGE only |
| 2 | No SSO integration active in STAGE | Low | Low | Local BCrypt auth with brute-force protection; no production user credentials in STAGE | Low | Accepted for STAGE only |
| 3 | Application and database co-located on single server | Low | Medium | PostgreSQL port not externally exposed; Docker internal network only; full DB access requires host-level compromise | Low | Accepted for STAGE |
| 4 | No external penetration test performed | Medium | Medium | Peer code review; built-in security controls; closed network isolation; pentest required before Production | Medium | Accepted for STAGE; mandatory before Production |
| 5 | No application-layer file size upload limit | Low | Low | 50-file count limit enforced; Docker volume bounds storage; extension whitelist applied | Low | Accepted for STAGE; remediation prior to Production go-live |
| 6 | No container image vulnerability scanning | Low | Low | Adoptium base image actively maintained; images rebuilt on each deployment; Spring BOM version control | Low | Accepted for STAGE; required before Production |
| 7 | Log retention not formally configured | Low | Low | Logs present in Docker stdout with UUID correlation; formal retention policy to be applied | Low | Accepted for STAGE |

### Q15.2 — Compliance confirmation statements

| Statement | Status |
|---|---|
| No production personal data processed in STAGE | Confirmed |
| No user passwords stored in plaintext | Confirmed — BCrypt(10) hashes only |
| Authentication required for all business functionality | Confirmed — Spring Security enforces authentication on all non-public endpoints |
| All user actions are auditable | Confirmed — `admin_audit_log` + `workflow_history` + authentication events in application logs |
| Credential secrets not committed to version control | Confirmed — `.env` excluded by `.gitignore`; `.env.example` contains placeholders only |
| Application performs input sanitization for file uploads | Confirmed — filename and folder sanitization applied; extension-based MIME whitelist enforced |
| HTTPS is planned and application code is ready | Confirmed — HSTS headers pre-configured; pending IT Infrastructure certificate provisioning (#INFRA-10482) |
| SSO integration is planned and application code is ready | Confirmed — `ssodev` Spring profile and `.oauth2Login()` implemented; pending IT Infrastructure IdP parameters (#INFRA-10482) |
| Data segregation enforced between users | Confirmed — `ControlPermissionService` and `AuthorizationPolicy` enforce role/assignment-based isolation at the service layer |

### Q15.3 — Outstanding items required before Production go-live

| # | Item | Owner | Priority |
|---|---|---|---|
| 1 | SSL/TLS certificate provisioning and HTTPS activation | IT Infrastructure | Critical — Pre-Production |
| 2 | Microsoft Entra ID SSO client registration and IdP parameter provisioning | IT Infrastructure | Critical — Pre-Production |
| 3 | External penetration test / OWASP Top 10 assessment | Security team | Critical — Pre-Production |
| 4 | Container image vulnerability scanning (Trivy / GitLab Container Scanning) | DevOps | High — Pre-Production |
| 5 | OWASP Dependency-Check integration in CI pipeline | Development team | High — Next sprint |
| 6 | File upload size limit (`spring.servlet.multipart.max-file-size`) | Development team | Medium — Next sprint |
| 7 | File content (magic byte) validation | Development team | Medium — Pre-Production |
| 8 | Log aggregation and SIEM integration | IT Infrastructure | High — Pre-Production |
| 9 | Formal backup policy documentation and off-site backup verification | Server administrator | High — STAGE + Pre-Production |
| 10 | MDE onboarding confirmation for STAGE host | IT Infrastructure | Completed — MDE 101.24032.0007, last scan 2026-07-20 |

---

## APPENDIX A — Evidence Package Artifacts Checklist

The table below defines the complete set of physical artifacts required to support this Security Assessment Report. Each artifact must be collected on or near the STAGE environment and submitted to the Information Security team alongside this document package.

| # | Artifact ID | Artifact Name | Format | Description | Status |
|---|---|---|---|---|---|
| 1 | `ART-01` | SBOM Source — pom.xml + dependency tree | Text / Log file | Project's `pom.xml` as committed in the repository, plus the full output of `./mvnw dependency:tree -DoutputFile=dependency-tree.txt` executed on the STAGE build. Together these constitute the Software Bill of Materials for all transitive runtime dependencies | Required |
| 2 | `ART-02` | OpenAPI Specification Export | JSON + YAML | Machine-readable OpenAPI 3.0 export collected from the running STAGE instance: `GET /v3/api-docs` (JSON) and `GET /v3/api-docs.yaml` (YAML). Saved as `openapi.json` and `openapi.yaml`. Constitutes the authoritative API inventory | Required |
| 3 | `ART-03` | Swagger UI Screenshot | PNG / PDF | Full-screen screenshot of `GET /swagger-ui/index.html` showing all endpoint groups expanded, and a separate screenshot of the raw `/v3/api-docs` JSON response in browser. Confirms API surface matches documented inventory | Required |
| 4 | `ART-04` | GitLab Repository Screenshot | PNG | Full-screen screenshot of the internal GitLab repository page showing: repository name, visibility (Internal / Private), branch list, and branch protection rules for the main branch (Settings → Repository → Protected Branches). Confirms source code is not publicly accessible and direct pushes to main are blocked | Required |
| 5 | `ART-05` | GitLab Merge Request Screenshot | PNG | Screenshot of a representative closed / merged MR showing: MR title, approver name(s), approval timestamp, and the green "Approved" badge. Confirms peer review enforcement prior to merge | Required |
| 6 | `ART-06` | GitLab CI Pipeline Screenshot | PNG | Full-screen screenshot of a successful recent pipeline run showing both `build_job` and `test_job` stages as passed (green), JUnit report tab, and JaCoCo coverage artifact link. Confirms automated build and test gate is active | Required |
| 7 | `ART-07` | `.gitlab-ci.yml` — Pipeline Definition | Text / YAML file | Verbatim copy of the `.gitlab-ci.yml` file as committed in the repository. Provides auditable definition of CI/CD stages, runner tags, artifact retention, and coverage reporting configuration | Required |
| 8 | `ART-08` | Host OS Evidence | Text / Log file | Output of the following commands executed on the STAGE server and saved to a text file: `cat /etc/os-release`, `uname -a`, `hostname -f`. Confirms operating system version and host identity | Required |
| 9 | `ART-09` | MDE Agent Evidence | PNG | Full-screen screenshot of the Microsoft Defender for Endpoint (MDE) management console or local agent status page showing: agent version `101.24032.0007`, real-time protection status `Enabled`, last scan date `2026-07-20`. Confirms endpoint protection is active per corporate standard | Required |
| 10 | `ART-10` | Qualys / Vulnerability Scanner Statement | Statement (Text) | Qualys agent-based or authenticated scanning is not implemented on the STAGE environment. Pending confirmation from ISS or IT Infrastructure team. Container image scanning (Trivy / GitLab Container Scanning) is planned prior to Production go-live (see Section 15, Item #4) | Statement on file |
| 11 | `ART-11` | Docker Compose Runtime Evidence | Text / Log file | Output of `docker compose config` (effective merged configuration) and `docker compose ps` (running containers and port bindings) executed on the STAGE host. Must confirm: application bound to host port `8081`; PostgreSQL `5432` listed as internal-only with no host binding | Required |
| 12 | `ART-12` | Firewall / Network Isolation Evidence | Text / Log file | Output of `sudo iptables -L -n -v` or `sudo ufw status verbose` executed on the STAGE host, demonstrating no inbound internet-facing rules. Alternatively, a network architecture diagram from IT Infrastructure confirming the server resides behind the corporate firewall with no public ingress rules. Confirms STAGE is not internet-accessible | Required |
| 13 | `ART-13` | Backup Configuration and Test Evidence | Text / Log file | Three sub-artifacts: (a) `crontab -l` output showing the daily `pg_dump` cron job entry; (b) directory listing of `/backups` showing recent `.sql.gz` files with timestamps; (c) restore test log from the dry-run executed on `2026-07-15` confirming successful database restoration on the DEV environment | Required |
| 14 | `ART-14` | Application Logging Samples | Text / Log snippets | Three sub-artifacts: (a) Application log excerpt (minimum 20 lines) demonstrating UUID `correlationId` field present in each line and authentication events (`auth status=SUCCESS`, `auth status=BAD_CREDENTIALS`); (b) Sample rows from `admin_audit_log` table (minimum 5 rows) showing `admin_email`, `action_type`, `changed_fields`, `created_at`, `ip_address` columns populated; (c) Sample rows from `workflow_history` table (minimum 5 rows) showing `action_type`, `from_step`, `to_step`, `performed_by_email`, `created_at` columns populated | Required |
| 15 | `ART-15` | SIEM / Log Retention Statement | Statement (Text) | Centralized log aggregation and SIEM integration are not yet configured for the STAGE environment. Implementation is required prior to Production go-live. Owner: IT Infrastructure. Reference: Section 15, Outstanding Item #8 | Statement on file |
| 16 | `ART-16` | TLS / SSO Jira Ticket Evidence | PNG / Screenshot | Screenshot of Jira ticket `#INFRA-10482` showing ticket title, status (Pending / In Progress), reporter, assignee, and creation date. Confirms the formal infrastructure provisioning request for SSL/TLS certificate and Microsoft Entra ID (SSO) IdP parameters is on record | Required |
| 17 | `ART-17` | Network and Data Flow Diagrams | PNG / PDF | Exported static renders of the Mermaid DFD Level 0 (Context Diagram) and DFD Level 1 (Application Internals) from Document 02, Section 3. Diagrams must be exported as PNG or PDF for inclusion in the evidence package PDF compilation. Source Mermaid markup is retained in `02_INFRA_LOGGING_ENCRYPTION.md` | Required |
| 18 | `ART-18` | Risk Acceptance Sign-off | Signed PDF / Email confirmation | Signed acknowledgement from the Project Owner and IT Owner confirming acceptance of the seven STAGE-only risks documented in Section 15.1 of this document. Acceptable formats: signed PDF, digitally signed email thread, or approval recorded in the project's risk register. Scope of acceptance is limited to the STAGE environment only and does not carry over to Production | Required |
