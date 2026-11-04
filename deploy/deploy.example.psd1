# QTracker delivery settings. Copy this file OUTSIDE the repository and fill it in:
#   corporate laptop: %USERPROFILE%\.qtracker\deploy.psd1   (publish.ps1)
#   server:           C:\MyApps\QTracker\secrets\deploy.psd1 (server-update.ps1, server-deploy.ps1)
# Tokens are never written here: each setting below names the FILE that holds one.
@{
    # --- GitLab (both sides) ---
    GitLabUrl       = 'https://gitlab.example.corp'      # no slash at the end
    ProjectPath     = 'group/qtracker'                   # the project path in GitLab, or its numeric ID
    PackageName     = 'qtracker-app'

    # --- corporate laptop (publish.ps1) ---
    GitHubRemote    = 'github'                           # git remote of github.com/.../QTracker
    GitLabRemote    = 'gitlab'                           # git remote of the corporate GitLab project
    Branch          = 'main'
    # Personal or project access token with the scope "api" (upload of packages); a full path (no variables here)
    PublishTokenFile = 'C:\Users\<you>\.qtracker\gitlab-token.txt'

    # --- server (server-update.ps1, server-deploy.ps1) ---
    # Deploy token of the project with the scope "read_package_registry"; the file holds "username:token"
    DeployTokenFile = 'C:\MyApps\QTracker\secrets\gitlab-deploy-token.txt'
    Dir             = 'C:\MyApps\QTracker'
    WslDistro       = 'Ubuntu'
    WslDir          = '/mnt/c/MyApps/QTracker'
    AppPort         = 8081
    AppHost         = 'qtracker.example.corp'            # = APP_CANONICAL_HOST of the server .env
    DbHost          = 'localhost'
    DbName          = 'qtracker_prod'
    DbUser          = 'qtracker_prod_app'                # its password comes from pgpass.conf, never from here
    PgBin           = 'C:\Program Files\PostgreSQL\18\bin'
}
