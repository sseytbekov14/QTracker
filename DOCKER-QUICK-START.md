# QTracker Docker DEV - Быстрый старт

## Установка (первый раз)

### 1. Убедитесь, что Docker Desktop запущен
- Откройте **Docker Desktop** приложение
- Дождитесь пока появится "Docker is running"

### 2. Перейдите в папку проекта
```powershell
cd C:\Sultan\Projects\QTracker
```

### 3. Запустите полную инициализацию
```powershell
# Первый запуск с построением всех контейнеров
$env:QTRACKER_ENV_FILE = ".env.dev"; docker compose up -d --build
```

**Это займет 5-15 минут в первый раз!**

---

## Ежедневное использование

### Запуск приложения
```powershell
cd C:\Sultan\Projects\QTracker
$env:QTRACKER_ENV_FILE = ".env.dev"
docker compose up -d
```

### Доступ к приложению
```
http://localhost:8081
```

### Проверка статуса
```powershell
docker compose ps
```

Все сервисы должны быть в статусе **Up** или **healthy**.

### Просмотр логов
```powershell
# Логи приложения (в реальном времени)
docker compose logs -f app

# Логи БД
docker compose logs -f db

# Последние 50 строк
docker compose logs --tail=50 app
```

### Остановка
```powershell
docker compose stop
```

Или полное удаление контейнеров (данные БД сохранены):
```powershell
docker compose down
```

---

## Проблемы и решения

### ❌ "Cannot connect to Docker daemon"
→ Запустите **Docker Desktop**

### ❌ "Port 8081 already in use"
```powershell
# Найдите процесс
netstat -ano | findstr :8081

# Завершите его
taskkill /PID <PID> /F
```

### ❌ "Failed to create container"
```powershell
# Полная очистка (⚠️ удалит БД!)
docker compose down -v
$env:QTRACKER_ENV_FILE = ".env.dev"
docker compose up -d --build
```

### ❌ Приложение не отвечает
```powershell
# Проверьте логи
docker compose logs app

# Рестартните приложение
docker compose restart app

# Если не помогло, пересоздайте
docker compose down
$env:QTRACKER_ENV_FILE = ".env.dev"
docker compose up -d
```

### ❌ "Insufficient memory"
1. Откройте **Docker Desktop** → **Settings** → **Resources**
2. Увеличьте **Memory** до 6-8 GB
3. Нажмите **Apply & Restart**

---

## Работа с БД

### Подключение к PostgreSQL
```powershell
docker exec -it qtracker-db-1 psql -U qtracker_app -d QTracker
```

Команды в psql:
```sql
-- Показать таблицы
\dt

-- Выход
\q
```

### Автоматические бекапы
Каждый день в 3:00 UTC создается бекап в `./backups/`

Просмотр:
```powershell
ls .\backups\
```

---

## Свичинг между окружениями

### DEV ↔ STAGE
```powershell
# Остановить текущее
docker compose down

# Переключиться
$env:QTRACKER_ENV_FILE = ".env.stage"

# Запустить
docker compose up -d
```

---

## Управление версиями

### Пересоздать образ приложения
```powershell
docker compose build app
docker compose up -d app
```

### Полный rebuild
```powershell
docker compose up -d --build
```

### Посмотреть версию Java в контейнере
```powershell
docker compose exec app java -version
```

---

## Перфоманс

### Мониторинг ресурсов
```powershell
docker stats
```

### Очистка неиспользуемых образов
```powershell
docker image prune
```

---

## Переменные окружения

Находятся в `.env.dev`:
- `APP_PORT=443` - внешний порт
- `SPRING_PROFILES_ACTIVE=dev` - Spring профиль
- `APP_BASE_URL=https://kzastapp01` - базовый URL
- `SPRING_DATASOURCE_PASSWORD` - пароль БД

При изменении `.env.dev` нужно перезапустить контейнеры:
```powershell
docker compose down
docker compose up -d
```

---

## PowerShell скрипт (удобнее)

В папке проекта есть скрипт `start-dev.ps1`:

```powershell
# Запуск
.\start-dev.ps1

# Просмотр логов
.\start-dev.ps1 -Logs

# Остановка
.\start-dev.ps1 -Stop

# Полная очистка (удалит БД)
.\start-dev.ps1 -Clean

# Пересборка
.\start-dev.ps1 -Build

# Статус
.\start-dev.ps1 -Status
```

---

## Docker Desktop настройки (рекомендуемо)

1. **Settings** → **General**
   - ✅ Start Docker Desktop when you log in

2. **Settings** → **Resources**
   - CPU: 4-6 cores (или половина от доступных)
   - Memory: 6-8 GB

3. **Settings** → **Advanced**
   - Swap: 2 GB (если мало ОЗУ)

---

## Полезные ссылки

- Приложение: http://localhost:8081
- PostgreSQL клиент: http://localhost:5432
- Docker Docs: https://docs.docker.com/compose/
- Spring Boot: https://spring.io/projects/spring-boot

---

📝 **Последнее обновление:** апрель 2024
