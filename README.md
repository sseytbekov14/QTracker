# QTracker

QTracker is a Java / Spring Boot web application designed for managing operational and compliance controls through a structured role-based workflow.

The system supports the full lifecycle of a control — from creation to final approval — including validation rules, reminders, and notification handling.

---

## 🚀 Tech Stack

### Backend
- Java 25  
- Spring Boot  
- Spring MVC  
- Spring Data JPA  
- Hibernate  

### Database
- PostgreSQL  

### Frontend
- HTML  
- CSS  
- JavaScript  
- Thymeleaf  

### Build Tool
- Maven  

---

## 📌 Core Features

- Full control lifecycle management (create, review, approve, return)
- Role-based access:
  - Facilitator  
  - Control Operator  
  - SoQM Lead  
  - Process Owner  
- Automatic deadline and next operation date calculation
- Multiple control frequencies:
  - Monthly  
  - Quarterly  
  - Semi-Annual  
  - Annual  
  - Ad-hoc / Recurring  
- Scheduled reminder logic (Day 0, Day 3, Day 6)
- Email notifications for workflow transitions
- Shared control access (view / edit)
- Workflow validation rules
- Overdue tracking

---

## 🏗 Architecture

Layered architecture:
- Controller layer  
- Service layer  
- Repository layer  
- Entity / DTO separation  

The application is structured for maintainability and enterprise usage.

---

## 🛠 Running Locally

Run application:

```bash
mvn spring-boot:run
