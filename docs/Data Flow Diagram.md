# Data Flow Diagram

## Overview
This document describes the actual data flows implemented in QTracker.

Scope is based on repository code:
- Controllers in `com.kpmg.qtracker.controller`
- Services in `com.kpmg.qtracker.service`
- Repositories in `com.kpmg.qtracker.repository`
- Security chains in `SecurityConfig`
- Schedulers in `com.kpmg.qtracker.scheduler`
- Database schema in Flyway migrations (`V1__init_schema.sql`, `V2__seed_initial_admin.sql`)

## DFD Level 0
```mermaid
flowchart LR
    U[User Browser]
    Q[QTracker Web App]
    DB[(PostgreSQL)]
    FS[(Uploads File Storage)]
    SMTP[SMTP Server]
    IDP[OAuth2 Corporate IdP]

    U -->|UI pages + REST calls| Q
    Q -->|CRUD + workflow + notifications| DB
    Q -->|upload/download attachments| FS
    Q -->|email notifications| SMTP
    Q -. ssodev oauth2Login .-> IDP
    Q -->|HTML/JSON responses| U
```

## DFD Level 1
```mermaid
flowchart LR
    subgraph Client
      UI[Thymeleaf UI + JS]
    end

    subgraph Security
      SC[SecurityConfig]
      RL[RateLimitingFilter]
      CID[CorrelationIdFilter]
      DEVAP[DevAuthenticationProvider]
      O2[oauth2Login]
      AUTHZ[AuthorizationPolicy]
    end

    subgraph Controllers
      VC[ViewController]
      CC[ControlController]
      CTC[ControlTabsController]
      WC[WorkflowController]
      WTC[WorkflowTransitionController]
      FAC[FileAttachmentController]
      NAC[NotificationApiController]
      PC[PermissionController]
      RC[RoleController]
      UC[UserController]
    end

    subgraph Services
      CS[ControlService]
      CAS[ControlAssignmentService]
      CDS[ControlDetailsService]
      CDOS[ControlDocumentsService]
      WFS[WorkflowServiceImpl]
      WRFS[WorkflowRequiredFieldService]
      NS[NotificationService]
      RN[ReminderNotificationService]
      AC[ControlAutoCreationService]
      FSS[FileStorageService]
      AAS[AdminAuditService]
      CPS[ControlPermissionService]
    end

    subgraph Repositories
      CR[ControlRepository]
      CAR[ControlAssignmentRepository]
      CDR[ControlDetailsRepository]
      CDoR[ControlDocumentsRepository]
      WHR[WorkflowHistoryRepository]
      WSR[WorkflowStepRepository]
      NR[NotificationRepository]
      CNR[ControlNotificationLogRepository]
      UR[UserRepository]
      AAR[AdminAuditLogRepository]
    end

    subgraph DataStores
      T1[(controls)]
      T2[(workflow_steps)]
      T3[(workflow_history)]
      T4[(notifications)]
      T5[(control_notification_log)]
      T6[(users)]
      T7[(admin_audit_log)]
      FILES[(uploads/)]
    end

    subgraph Schedulers
      CRS[ControlReminderScheduler]
      CACS[ControlAutoCreationScheduler]
    end

    subgraph External
      SMTP2[SMTP Server]
      IDP2[OAuth2 Corporate IdP]
    end

    UI --> SC
    SC --> RL
    SC --> CID
    SC --> DEVAP
    SC -. ssodev .-> O2
    O2 -. auth .-> IDP2

    UI --> VC
    UI --> CC
    UI --> CTC
    UI --> WC
    UI --> WTC
    UI --> FAC
    UI --> NAC
    UI --> PC
    UI --> RC
    UI --> UC

    VC --> CS
    CC --> CS
    CC --> CAS
    CC --> CDS
    CC --> CDOS
    CTC --> CAS
    CTC --> CDS
    CTC --> CDOS
    WC --> WFS
    WC --> WRFS
    WC --> NS
    WTC --> CS
    WTC --> WRFS
    WTC --> NS
    FAC --> FSS
    FAC --> CS
    FAC --> AAS

    CC --> AUTHZ
    WC --> AUTHZ
    WTC --> AUTHZ
    FAC --> AUTHZ
    AUTHZ --> CPS

    CS --> CR
    CAS --> CAR
    CDS --> CDR
    CDOS --> CDoR
    WFS --> WSR
    WFS --> WHR
    NS --> NR
    NS --> UR
    RN --> CNR
    RN --> CR
    AC --> CAR
    AC --> CR
    AAS --> AAR

    CR --> T1
    CAR --> T1
    CDR --> T1
    CDoR --> T1
    WSR --> T2
    WHR --> T3
    NR --> T4
    CNR --> T5
    UR --> T6
    AAR --> T7

    FSS --> FILES

    CRS --> RN
    CACS --> AC

    NS --> SMTP2
```

## Flow Descriptions

### 1. User Request Flow (UI/API)
1. User actions in Thymeleaf UI call MVC routes and REST endpoints.
2. Requests pass through security chain (`SecurityConfig`) and filters (`RateLimitingFilter`, `CorrelationIdFilter`).
3. Controllers delegate business logic to services.
4. Services use repositories for persistence.
5. Responses return as HTML (views) or JSON (APIs).

### 2. Control CRUD Flow
1. `ControlController` and `ControlTabsController` accept control-related requests.
2. `AuthorizationPolicy` + `ControlPermissionService` enforce access.
3. `ControlService`, `ControlAssignmentService`, `ControlDetailsService`, `ControlDocumentsService` process updates.
4. Repositories persist mainly into `controls` (single-table projections for control, assignment, details, documents).

### 3. Workflow Transition Flow
1. Workflow actions enter via `WorkflowController` and `WorkflowTransitionController`.
2. Required fields are checked by `WorkflowRequiredFieldService`.
3. Workflow state and status changes are handled by `WorkflowServiceImpl` and `ControlService`.
4. History is stored through `WorkflowHistoryRepository` into `workflow_history`.
5. Current workflow steps are managed through `WorkflowStepRepository` into `workflow_steps`.
6. `NotificationService` emits in-app notifications and optional email.

### 4. File Upload/Download Flow
1. `FileAttachmentController` handles upload/view/download/delete endpoints.
2. Access is validated by `AuthorizationPolicy`.
3. Binary files are read/written by `FileStorageService` to `uploads` storage.
4. Attachment metadata is saved in `controls` fields (`attachment_details_path`, `attachment_documents_path`).
5. Administrative file actions can be recorded by `AdminAuditService` into `admin_audit_log`.

### 5. Notification and Reminder Flow
1. Scheduler `ControlReminderScheduler` triggers `ReminderNotificationService` daily.
2. Candidate controls are selected through `ControlRepository` queries.
3. Reminder/overdue rules are evaluated using working-day logic.
4. `NotificationService` writes notifications to `notifications` and can send email via SMTP.
5. Deduplication is tracked in `control_notification_log` via `ControlNotificationLogRepository`.

### 6. Auto-Creation Scheduler Flow
1. `ControlAutoCreationScheduler` runs daily.
2. `ControlAutoCreationService` reads due assignments via `ControlAssignmentRepository`.
3. New control occurrence and assignment are created via `ControlRepository` and `ControlAssignmentRepository`.
4. Auto-created notification is sent through `NotificationService`.

### 7. OAuth2 Login Flow (ssodev)
1. In `ssodev` profile, `SecurityConfig` enables `oauth2Login()`.
2. Browser is redirected to corporate IdP for authentication.
3. After successful callback, request proceeds as authenticated in Spring Security context.
4. Protected routes (`/api/**` and other authenticated endpoints) become available.

## Data Stores

### Primary Database Tables
From Flyway `V1__init_schema.sql`:
- `users`
- `controls`
- `workflow_steps`
- `workflow_history`
- `notifications`
- `control_notification_log`
- `admin_audit_log`

### Non-DB Data Stores
- Filesystem attachment store: configured by `file.upload.dir` and managed by `FileStorageService`.
- HTTP session state: user context used by controllers and security logic.

### Notes on Table Usage
- `controls` is used by multiple entity mappings (`Control`, `ControlAssignment`, `ControlDetails`, `ControlDocuments`).
- Notification dedupe for scheduled jobs is persisted in `control_notification_log`.
- Workflow traceability is split between `workflow_steps` (state) and `workflow_history` (audit trail).