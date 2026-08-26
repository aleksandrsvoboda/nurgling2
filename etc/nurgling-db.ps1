# Village database for Nurgling - Windows machine that hosts but does not play.
#
# The container's whole job is an empty PostgreSQL with one admin role. Tables, grants and
# every future schema change belong to the client, so nothing here has to be kept in step
# with a Nurgling version.
#
#   .\nurgling-db.ps1 up -HostAddr vault.example.com
#   .\nurgling-db.ps1 status | invite | backup | logs | down
param(
    [Parameter(Position = 0)][string]$Command = "help",
    [string]$HostAddr = "",
    [int]$Port = 5436,
    [string]$File = ""
)

$ErrorActionPreference = "Stop"
$Dir = if ($env:NURGLING_DB_DIR) { $env:NURGLING_DB_DIR } else { Join-Path $HOME "nurgling-db" }
$Image = "postgres:17-alpine"
$Admin = "nurgling_admin"
$DbName = "nurgling_db"

function Need-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "docker is not installed" }
    # The CLI answers happily while the engine is stopped, so ask the server.
    docker version --format '{{.Server.Version}}' *> $null
    if ($LASTEXITCODE -ne 0) { throw "docker is installed but the engine is not running" }
}

function New-Password {
    $alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 32
    $rng.GetBytes($bytes)
    -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function Get-EnvValue([string]$key) {
    (Get-Content (Join-Path $Dir ".env") | Where-Object { $_ -like "$key=*" }) -replace "^$key=", ""
}

function Write-Files {
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    $envPath = Join-Path $Dir ".env"
    if (-not (Test-Path $envPath)) {
        @(
            "POSTGRES_USER=$Admin",
            "POSTGRES_PASSWORD=$(New-Password)",
            "POSTGRES_DB=$DbName",
            "PGPORT=$Port"
        ) | Set-Content -Path $envPath -Encoding ASCII
        Write-Host "generated a new password in $envPath"
    } else {
        Write-Host "keeping the existing $envPath"
    }

    @"
services:
  postgres:
    image: $Image
    container_name: nurgling_db
    restart: unless-stopped
    env_file: .env
    ports:
      - "`${PGPORT:-$Port}:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U `$`$POSTGRES_USER -d `$`$POSTGRES_DB"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
"@ | Set-Content -Path (Join-Path $Dir "docker-compose.yml") -Encoding ASCII
}

function Show-Invite {
    if (-not (Test-Path (Join-Path $Dir ".env"))) { throw "no .env in $Dir - run 'up' first" }
    $addr = if ($HostAddr) { $HostAddr } else {
        (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.PrefixOrigin -ne "WellKnown" } |
            Select-Object -First 1 -ExpandProperty IPAddress)
    }
    if (-not $addr) { $addr = "YOUR-SERVER-ADDRESS" }
    $u = Get-EnvValue "POSTGRES_USER"
    $p = Get-EnvValue "POSTGRES_PASSWORD"
    $d = Get-EnvValue "POSTGRES_DB"
    $k = Get-EnvValue "PGPORT"
    Write-Host ""
    Write-Host "Paste this into the client: Settings -> Database -> My village -> Invite code"
    Write-Host ""
    Write-Host "  postgresql://${u}:${p}@${addr}:${k}/${d}"
    Write-Host ""
    Write-Host "This is a password. Send it privately, and only to yourself - add other players"
    Write-Host "from the client's Villagers panel, so each gets a revocable account."
}

switch ($Command) {
    "up" {
        Need-Docker
        Write-Files
        Push-Location $Dir
        try {
            docker compose up -d
            Write-Host -NoNewline "waiting for postgres"
            $ok = $false
            for ($i = 0; $i -lt 30 -and -not $ok; $i++) {
                $health = docker compose ps --format '{{.Health}}' 2>$null
                if ($health -match "healthy") { $ok = $true; break }
                Write-Host -NoNewline "."
                Start-Sleep -Seconds 2
            }
            Write-Host ""
            if (-not $ok) { throw "it started but never became healthy - try: .\nurgling-db.ps1 logs" }
        } finally { Pop-Location }
        Show-Invite
        Write-Host "If this machine is reachable from the internet, turn on encryption -"
        Write-Host "without it the connection is unencrypted, and this database holds hearth secrets."
    }
    "status" { Need-Docker; Push-Location $Dir; try { docker compose ps } finally { Pop-Location } }
    "invite" { Show-Invite }
    "backup" {
        Need-Docker
        $out = if ($File) { $File } else { Join-Path $Dir "backup-$(Get-Date -Format yyyyMMdd-HHmmss).sql" }
        Push-Location $Dir
        try {
            docker compose exec -T postgres pg_dump -U (Get-EnvValue "POSTGRES_USER") (Get-EnvValue "POSTGRES_DB") |
                Set-Content -Path $out -Encoding UTF8
        } finally { Pop-Location }
        Write-Host "wrote $out"
        Write-Host "This village's areas, routes and shared map exist here and nowhere else."
    }
    "logs" { Need-Docker; Push-Location $Dir; try { docker compose logs --tail=200 } finally { Pop-Location } }
    "down" {
        Need-Docker; Push-Location $Dir; try { docker compose down } finally { Pop-Location }
        Write-Host "stopped; the data volume is untouched"
    }
    default { Get-Content $PSCommandPath | Select-Object -First 9 | ForEach-Object { $_ -replace '^#\s?', '' } }
}
