# QTracker - DEV Docker Setup Guide (Windows)

## Предварительные требования

1. **Docker Desktop** - установлен и запущен на DEV сервере
   - Windows 10/11 Pro или Enterprise (для WSL 2)
   - Минимум 4GB RAM выделено Docker'у
   - Диск: 20GB свободно

2. **Git** - для клонирования проекта

3. **PowerShell** (встроен в Windows) или CMD

## Структура окружений

```
.env.dev      ← DEV конфигурация (port 8081)
.env.stage    ← STAGE конфигурация (port 8082)  
.env.prod     ← PROD конфигурация (port 8080)
```

## Установка и запуск для DEV

### Шаг 1: Клонирование проекта (если его ещё нет)

```powershell
git clone <repository-url> C:\Projects\QTracker
cd C:\Projects\QTracker
```

### Шаг 2: Подготовка переменных окружения

Убедитесь, что у вас есть файл `.env.dev`:

```powershell
Copy-Item .env.dev.example .env.dev -Force
# Или отредактируйте вручную нужные значения
```

**Важные переменные в `.env.dev`:**
- `SPRING_PROFILES_ACTIVE=dev` - профиль Spring Boot
- `APP_PORT=8081` - порт приложения (внешний)
- `POSTGRES_PASSWORD` - пароль БД (измените!)
- `APP_BASE_URL=http://localhost:8081` - URL приложения

### Шаг 3: Запуск контейнеров

**Первый запуск (с построением образа):**

```powershell
# Используем .env.dev для переменных окружения
$env:QTRACKER_ENV_FILE = ".env.dev"

# Запускаем сервис с rebuild'ом
docker compose up -d --build

# Или используем DEV override конфиг
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

**Последующие запуски:**

```powershell
docker compose up -d
```

### Шаг 4: Проверка статуса

```powershell
# Смотреть статус всех контейнеров
docker compose ps

# Проверить логи приложения
docker compose logs -f app

# Проверить логи БД
docker compose logs -f db

# Проверить логи backup сервиса
docker compose logs -f db-backup
```

### Шаг 5: Доступ к приложению

После запуска приложение доступно по адресу:
```
http://localhost:8081
```

**Проверка здоровья сервиса:**
```powershell
# Используйте curl если установлен
curl http://localhost:8081/actuator/health

# Или в PowerShell
Invoke-WebRequest http://localhost:8081/actuator/health
```

### Шаг 6: Остановка контейнеров

```powershell
# Остановить, но оставить данные
docker compose stop

# Остановить и удалить контейнеры (данные БД сохранены в volume)
docker compose down

# Полная очистка (удалит БД!)
docker compose down -v
```

## Управление данными

### Доступ к базе данных напрямую

```powershell
# Подключение к PostgreSQL контейнеру
docker compose exec db psql -U qtracker_dev -d qtracker_dev

# Или из PowerShell:
docker exec -it qtracker-db-1 psql -U qtracker_dev -d qtracker_dev
```

### Бекап базы данных

Backup автоматически создаётся каждый день в 3:00 UTC в папке `./backups/`:

```powershell
# Просмотр существующих бекапов
Get-ChildItem .\backups\

# Восстановление из бекапа
$backupFile = "backups\qtracker-backup-2024-01-15.sql.gz"
zcat $backupFile | docker exec -i qtracker-db-1 psql -U qtracker_dev -d qtracker_dev
```

## Свичинг между окружениями

### DEV → STAGE

```powershell
# Остановить DEV
docker compose down

# Запустить STAGE
$env:QTRACKER_ENV_FILE = ".env.stage"
docker compose up -d
```

### STAGE → DEV

```powershell
docker compose down
$env:QTRACKER_ENV_FILE = ".env.dev"
docker compose up -d
```

## Решение проблем

### 1. Порт уже занят

```powershell
# Найти процесс на порту 8081
netstat -ano | findstr :8081

# Убить процесс (замените PID)
taskkill /PID <PID> /F

# Или выбрать другой порт в .env.dev
# APP_PORT=8090
```

### 2. Docker Desktop не запущен

```powershell
# Проверить статус
docker ps

# Если ошибка, запустить Docker Desktop вручную
# или в PowerShell:
& 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
```

### 3. Контейнер постоянно перезагружается

```powershell
# Смотреть логи
docker compose logs app

# Проверить здоровье БД (должна быть healthy)
docker compose ps

# Если БД не healthy, пересоздать:
docker compose down -v
docker compose up -d
```

### 4. Памяти недостаточно

В Docker Desktop Settings:
1. Right-click Docker icon → Settings
2. Resources → Memory: установить 6-8GB (зависит от сервера)

### 5. БД не инициализируется

```powershell
# Очистить БД volume и пересоздать
docker volume rm qtracker-qtracker_pgdata
docker compose up -d db

# Дождаться пока БД будет healthy (20+ секунд)
docker compose ps
```

## Рекомендуемая структура для разработки

```
C:\Projects\
├── QTracker/
│   ├── .env.dev          ← используется для DEV
│   ├── .env.stage        ← используется для STAGE
│   ├── .env.prod         ← используется для PROD
│   ├── docker-compose.yml
│   ├── docker-compose.dev.yml  ← override для DEV (опционально)
│   ├── Dockerfile
│   ├── pom.xml
│   ├── src/
│   ├── backups/          ← автобекапы БД
│   └── uploads/          ← загруженные файлы
```

## Быстрые команды

```powershell
# Полный цикл: очистка → build → запуск
$env:QTRACKER_ENV_FILE = ".env.dev"; docker compose down -v; docker compose up -d --build

# Только логи приложения (следить в реальном времени)
docker compose logs -f app --tail=50

# Рестарт приложения
docker compose restart app

# Пересборка образа без перезапуска
docker compose build app
docker compose up -d app
```

## Переменные окружения в application-dev.yml

Spring Boot автоматически переопределяет переменные из `.env.dev`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/${POSTGRES_DB}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}

server:
  servlet:
    context-path: /
```

При `SPRING_PROFILES_ACTIVE=dev` загружается `application-dev.yml`.

## Полезные ссылки

- Docker Desktop: https://www.docker.com/products/docker-desktop
- Docker Compose docs: https://docs.docker.com/compose/
- PostgreSQL Docker: https://hub.docker.com/_/postgres
- Spring Boot Profiles: https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.profiles

---

**Автор:** GitHub Copilot
**Последнее обновление:** 2024
