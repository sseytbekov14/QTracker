# QTracker

## Configuration & Secrets
No real credentials are stored in the repository. Configure secrets and feature flags via environment variables.

Required environment variables (names only):
- `SPRING_DATASOURCE_URL` (or `DB_URL`)
- `SPRING_DATASOURCE_USERNAME` (or `DB_USERNAME`)
- `SPRING_DATASOURCE_PASSWORD` (or `DB_PASSWORD`)
- `SPRING_MAIL_HOST`
- `SPRING_MAIL_PORT`
- `SPRING_MAIL_USERNAME`
- `SPRING_MAIL_PASSWORD`
- `APP_BASE_URL`
- `FILE_UPLOAD_DIR`

Feature flags (defaults shown in properties files):
- `REMINDERS_ENABLED` (default: false)
- `NOTIFICATIONS_EMAIL_ENABLED` (default: false)
- `CONTROLS_AUTO_CREATE_ENABLED` (default: false)
- `OVERDUE_USE_WORKING_DAYS` (default: true)

Profiles:
- `application.properties`: safe defaults, no secrets
- `application-dev.properties`: development defaults (placeholders only)
- `application-prod.properties`: production-safe defaults (flags off unless enabled)

To activate a profile, set `SPRING_PROFILES_ACTIVE=dev` or `SPRING_PROFILES_ACTIVE=prod`.
