# Доставка QTracker на сервер

```
личный ноутбук ──git push──▶ GitHub (main)
                                  │
корпоративный ноутбук: publish.ps1
   git pull GitHub → git push GitLab → сборка образа → загрузка в GitLab (Package Registry)
                                  │
сервер: server-update.ps1  (видит только GitLab)
   версия в GitLab новее? → скачать → проверить SHA-256 → server-deploy.ps1
   (бэкап базы → docker load → docker-compose up -d → ждать /login → PortProxy 443)
```

Разворачивается ветка `main`. Пароли, токены и внутренние адреса в репозиторий не попадают: они лежат
в файлах настроек вне репозитория (образец — `deploy.example.psd1`).

## Каждый релиз

1. **Личный ноутбук:** нужная работа влита в `main`, `git push`.
2. **Корпоративный ноутбук:** `powershell -ExecutionPolicy Bypass -File .\deploy\publish.ps1`
   (с `-RunTests` — сначала все тесты).
3. **Сервер** (PowerShell от администратора):
   `powershell -ExecutionPolicy Bypass -File C:\MyApps\QTracker\deploy\server-update.ps1`,
   или ничего — если настроен запуск по расписанию (ниже).

Откат — как и раньше: `docker tag qtracker-app:previous qtracker-app:offline`, `docker-compose up -d`
и, если новая версия применила миграции, восстановление базы из последнего `.dump` в `C:\MyApps\QTracker\backups`.

## Что чистится само

| Что | Сколько хранится |
|---|---|
| Бэкапы базы (`backups\*.dump`) | последние 10 |
| Скачанные версии (`downloads`) | последние 3 |
| Логи скриптов (`logs\*.log`) | 90 дней (`server-update.ps1`) |
| Docker-образы | `:offline` и `:previous`; образ без метки, который остаётся после каждого обновления, удаляется (`server-deploy.ps1`) |
| Лог контейнера | только если в `docker-compose.server.yml` у сервиса `app` есть ограничение (ниже) |
| Данные в базе | 7 лет от создания, тексты писем — 30 дней после отправки (само приложение каждый день в 03:00, `DataRetention`) |

Ограничение лога контейнера (около 100 МБ), в `docker-compose.server.yml` у сервиса `app`:

```yaml
    logging:
      driver: json-file
      options:
        max-size: "20m"
        max-file: "5"
```

## Настройка один раз

### GitLab

1. Проект QTracker в корпоративном GitLab.
2. Settings → General → Visibility → **Package registry** включён (нужен Generic Package Registry).
3. Токен для корпоративного ноутбука: Access Token со scope **api** (загрузка пакетов).
4. Токен для сервера: Settings → Repository → **Deploy tokens**, scope **read_package_registry**
   (только чтение пакетов: с ним сервер не может ничего изменить в GitLab).

### Корпоративный ноутбук

```powershell
git clone -o github https://github.com/sseytbekov14/QTracker.git
cd QTracker
git remote add gitlab https://<gitlab>/<group>/qtracker.git
mkdir $env:USERPROFILE\.qtracker
copy deploy\deploy.example.psd1 $env:USERPROFILE\.qtracker\deploy.psd1   # заполнить: GitLabUrl, ProjectPath, PublishTokenFile
Set-Content $env:USERPROFILE\.qtracker\gitlab-token.txt '<токен со scope api>'
```

Нужны Docker, JDK 21 и доступ в интернет (Maven Central, Docker Hub) — как для сборки сейчас.
Проверка без загрузки: `.\deploy\publish.ps1 -DryRun`.

### Сервер

1. Папка `deploy` из репозитория → `C:\MyApps\QTracker\deploy` (скопировать один раз; при изменении скриптов — снова).
2. `C:\MyApps\QTracker\secrets\deploy.psd1` — из `deploy.example.psd1`: GitLabUrl, ProjectPath, AppHost
   (= `APP_CANONICAL_HOST` из `.env`) и остальное, как у сервера сейчас.
3. `C:\MyApps\QTracker\secrets\gitlab-deploy-token.txt` — одна строка `username:token` deploy-токена.
   Права на папку `secrets` — только администраторам.
4. Пароль базы для бэкапа — в `pgpass.conf` того пользователя, от имени которого запускается скрипт:
   `%APPDATA%\postgresql\pgpass.conf`, строка `localhost:5432:qtracker_prod:qtracker_prod_app:<пароль>`.
   Строку `$env:PGPASSWORD = ...` из старого скрипта обновления нужно удалить.
5. Первый запуск: `server-update.ps1`. Он запишет установленную версию в `C:\MyApps\QTracker\deployed-version.txt`
   и дальше будет ставить только новые. Лог каждого запуска — `C:\MyApps\QTracker\logs\update-*.log`.

### Расписание (необязательно, тогда сервер обновляется сам)

Например, каждый день в 20:00, от имени того же администратора, что и при ручном запуске
(дистрибутив WSL и `pgpass.conf` принадлежат пользователю, у SYSTEM их нет):

```powershell
schtasks /Create /TN "QTracker update" /SC DAILY /ST 20:00 /RL HIGHEST /RU <домен\пользователь> /RP `
  /TR "powershell -NoProfile -ExecutionPolicy Bypass -File C:\MyApps\QTracker\deploy\server-update.ps1"
```

Если новой версии нет, скрипт ничего не делает. Обновление с миграциями перезапускает приложение —
поэтому время выбирается вне рабочих часов.
