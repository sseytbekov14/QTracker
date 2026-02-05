# QTracker

- Java / Spring Boot application for managing operational and compliance controls using a role-based workflow.
- Supports the full control lifecycle (creation, review, approval, completion), including returns for rework, workflow notifications, scheduled reminders, and overdue handling.
- Provides date-driven logic and automation with multiple control frequencies: Monthly, Quarterly, Ad-hoc, Recurring, and Annual/Semi-annual.
- Designed with safety and maintainability in mind: critical business logic is covered by tests, time-based and automated features are guarded by feature flags, and no secrets are stored in the repository.
- Suitable for enterprise environments and extensible for custom workflows or control-tracking use cases.
