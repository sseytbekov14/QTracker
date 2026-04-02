# API Documentation

## Overview
QTracker exposes internal REST and MVC endpoints for control lifecycle management, assignments, workflow transitions, dashboard metrics, file attachments, notifications, and security/permission checks.

Scope:
- Internal enterprise usage
- Session-based web application APIs
- Role- and control-level authorization

## API Stability & Versioning
- The API is internal and currently unversioned (no /v1, /v2 path strategy).
- Backward compatibility is not guaranteed across releases.
- API changes follow product lifecycle priorities and internal release cadence, not public API deprecation/versioning rules.
- Consumers are expected to validate integration behavior on each release.

## Authentication & Authorization

### Authentication modes
- dev profile: form login at /login
- ssodev profile: OAuth2 login via Spring Security

### Authorization model
- Role-based access: FACILITATOR, CONTROL_OPERATOR, SOQM_LEAD, PROCESS_OWNER
- Control-level permissions resolved by ControlPermissionService
- Unauthorized: HTTP 401
- Forbidden: HTTP 403

### Authentication Requirements Matrix
| Endpoint Group | Auth Required | CSRF Required | Required Roles |
|---|---|---|---|
| Controls (/api/controls, /api/control-*) | Yes | No for /api/** endpoints (ignored in security config) | Any authenticated user; edit actions additionally depend on control-level permission |
| Workflow (/api/workflow) | Yes | No for /api/** endpoints (ignored in security config) | Any authenticated user; actions depend on workflow state + control-level permission |
| Dashboard (/api/dashboard, /api/dashboard/admin) | Yes | No for /api/** endpoints (ignored in security config) | /api/dashboard/admin/* requires SOQM_LEAD check; other dashboard endpoints require authenticated user |
| Attachments (/api/attachments) | Yes | No for /api/** endpoints (ignored in security config) | Any authenticated user; upload/delete operations require edit capability on target control |
| Notifications (/api/notifications and /notifications/mark-all-read) | Yes | /api/notifications: No (under /api/**). /notifications/mark-all-read: No (explicitly ignored) | Any authenticated user |
| Security (/api/users, /api/roles, /api/permissions) | Yes | No for /api/** endpoints (ignored in security config) | Any authenticated user; permission result depends on user role/assignment context |

## API Endpoint Groups

### Controls
Base path: /api/controls
Main endpoints:
- GET /api/controls
- GET /api/controls/generate-id
- GET /api/controls/check-id-unique
- POST /api/controls/{id}/rename-id
- GET /api/controls/export

Purpose:
- Manage control catalog and identifiers
- Export control data

Typical patterns:
- GET responses return DTO lists or maps
- Mutation endpoints return ResponseEntity with success/error message

Auth:
- Authenticated session required
- Edit operations require control-level edit permission

### Control Tabs (details/assignment/documents)
Main endpoints:
- POST /api/control-details
- POST /api/control-assignment
- POST /api/control-documents
- GET /api/control-details/{controlId}
- GET /api/control-assignment/{controlId}
- GET /api/control-documents/{controlId}

Purpose:
- Save and read tabbed control data sections

Typical patterns:
- Request body: ControlDetailsDTO / ControlAssignmentDTO / ControlDocumentsDTO
- Response: 200 on success, 400 for validation, 403 for permission restrictions

Auth:
- Authenticated session required
- Permissions validated per control

### Workflow
Base path: /api/workflow
Main endpoints:
- POST /api/workflow/perform-action
- GET /api/workflow/{controlId}/available-buttons
- POST /api/workflow/initiate
- POST /api/workflow/submit-to-control-operator

Purpose:
- Drive workflow status transitions
- Retrieve role-aware available actions

Typical patterns:
- Request body for action endpoint includes controlId, action, optional comment
- Responses include status/result details

Auth:
- Authenticated session required
- Role and control permission checks enforced

### Dashboard
Main endpoints:
- GET /api/dashboard/admin/status
- GET /api/dashboard/admin/component-breakdown
- GET /api/dashboard/admin/frequency
- GET /api/dashboard/admin/overdue-trend
- GET /api/dashboard/deadline-countdown
- GET /api/dashboard/deadline-calendar

Purpose:
- Provide operational and management metrics

Auth:
- Authenticated session required
- /api/dashboard/admin/* requires SOQM_LEAD-level access

### Performance
Base path: /api/performance
Main endpoints:
- POST /api/performance/auto-save
- GET /api/performance/performance-cycle/{controlId}

Purpose:
- Persist and render control performance-cycle data

Auth:
- Authenticated session required
- Control-level permission checks apply to edits

### Attachments
Base path: /api/attachments
Main endpoints:
- POST /api/attachments/upload/{controlId}
- GET /api/attachments/download/{controlId}
- DELETE /api/attachments/delete/{controlId}

Purpose:
- Upload/download/delete control attachments

Typical patterns:
- Multipart upload
- File metadata and success/error map responses

Auth:
- Authenticated session required
- Upload/delete require edit rights

### Notifications
Base path: /api/notifications
Main endpoints:
- GET /api/notifications?userId={id}

Related non-/api endpoints used by UI flow:
- POST /notifications/mark-all-read

Purpose:
- Retrieve and update notification state

Auth:
- Authenticated session required

### Security (Users/Roles/Permissions)
Users:
- GET /api/users
- GET /api/users/{email}

Roles:
- GET /api/roles/control-operators
- GET /api/roles/soqm-leads
- GET /api/roles/process-owners
- GET /api/roles/facilitators

Permissions:
- GET /api/permissions/{controlId}
- GET /api/permissions/{controlId}/can-edit

Purpose:
- Populate assignment pickers and resolve UI/action permissions

Auth:
- Authenticated session required

## Common Request/Response Patterns
- Transport: JSON for REST endpoints; some MVC endpoints return views
- Session-based authentication via JSESSIONID
- CSRF protection enabled; selected endpoints excluded by config
- Validation/permission failures return 4xx with message payload
- Not found resources return 404 where implemented

### Example: GET /api/controls
Request:
```http
GET /api/controls HTTP/1.1
Host: localhost:8080
Cookie: JSESSIONID=<session-id>
```

Typical response:
```json
[
	{
		"id": 1,
		"controlId": "HR-CTRL-MF-1",
		"component": "HR",
		"frequency": "MONTHLY",
		"performanceStatus": "IN_PROGRESS"
	}
]
```

### Example: POST /api/workflow/perform-action
Request:
```http
POST /api/workflow/perform-action HTTP/1.1
Host: localhost:8080
Cookie: JSESSIONID=<session-id>
Content-Type: application/json

{
	"controlId": 1,
	"action": "SUBMIT_FOR_REVIEW",
	"comment": "Ready for review"
}
```

Typical response:
```json
{
	"success": true,
	"message": "Workflow action completed"
}
```

## File Upload Constraints
- Upload endpoint: POST /api/attachments/upload/{controlId}
- Multipart parts accepted:
	- attachmentDetails[]
	- attachmentDocuments[]
- Count limits enforced in controller:
	- Max 50 files in Details bucket per control
	- Max 50 files in Documents bucket per control
- Max upload size:
	- No explicit multipart max-size override found in repository YAML configs.
	- Effective file/request size limits are therefore the active Spring Boot multipart defaults/runtime settings.
- Sanitization rules (FileStorageService):
	- File name sanitized with regex [^a-zA-Z0-9._-] -> _
	- Folder name sanitized with regex [^a-zA-Z0-9._-] -> _
	- This prevents path traversal and unsafe path characters.
- Storage path structure:
	- Base directory from file.upload.dir (dev default: uploads)
	- Per-control folder: uploads/<controlCode>/
	- Fallback folder naming: control database id when controlCode is blank
	- Duplicate names are auto-resolved with numeric suffix: filename (1).ext

## Error Handling
Common status codes:
- 200 OK: success
- 400 Bad Request: invalid input or business validation failure
- 401 Unauthorized: not authenticated
- 403 Forbidden: lacks permission
- 404 Not Found: entity missing
- 429 Too Many Requests: rate limiter triggered
- 500 Internal Server Error: unexpected failure

Observed error styles:
- Plain message body (string)
- Map-like JSON with success/message fields

### Error Response Format
Common JSON shapes used in current controllers:

Success/message style:
```json
{
	"success": false,
	"message": "Maximum 50 files allowed for Details attachments."
}
```

Error/message style:
```json
{
	"error": "Control not found: 123",
	"message": "Request failed"
}
```

Endpoint style notes:
- Attachments and workflow transition endpoints frequently return map-based JSON (success/message/error).
- Several control/workflow endpoints may return plain string bodies for validation or permission failures.
- Some endpoints return empty-body statuses (401/403/404) via ResponseEntity build().

## Security Considerations
- Session-based auth with Spring Security filter chains
- RBAC + control-specific permission checks
- CSRF enabled with CookieCsrfTokenRepository
- Login hardening includes failed-attempt lockout behavior
- Rate limiting filter active for login and selected API traffic
- Secure headers configured (CSP, HSTS, XSS, frame policy, etc.)

## Non-goals
This internal API is not intended to provide:
- Public/anonymous API access
- Third-party public developer contract (Swagger/OpenAPI publication)
- External multi-tenant API platform behavior
- Backward-compatible public versioning guarantees

## Notes
This document is high-level and intentionally concise. It reflects controller-level endpoints and behavior in the current repository state.
