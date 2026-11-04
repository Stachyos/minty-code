param([switch]$Stop)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker Desktop is required. Install it, start the Linux engine, then run this script again.'
}
docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop is not ready. Start Docker Desktop and wait until the Linux engine is running.'
}
if ($Stop) {
    docker compose stop
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stop Minty Code.' }
    Write-Host 'Minty Code stopped. Containers, database and workspaces are preserved.'
    exit 0
}
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
    Write-Host 'Created .env. Fill in ANTHROPIC_API_KEY and MODEL_ID to enable real Agent tasks.' -ForegroundColor Yellow
}
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Startup failed. Check Docker Desktop and the output above.' }
Write-Host 'Waiting for Minty Code...' -ForegroundColor Cyan
$port = '5173'
Get-Content -LiteralPath '.env' | ForEach-Object {
    if ($_ -match '^\s*WEB_PORT\s*=\s*(\d+)\s*$') { $port = $Matches[1] }
}
$url = "http://127.0.0.1:$port"
$ready = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    try {
        $response = Invoke-RestMethod -Uri "$url/api/status" -TimeoutSec 3
        $ready = $true
        break
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $ready) {
    docker compose logs --tail 50 backend
    throw 'Services are starting, but the backend is not ready yet. Inspect the logs above.'
}
Write-Host "Minty Code is ready: $url" -ForegroundColor Green
if (-not $response.configured) {
    Write-Host 'The web app is ready. Configure your API key in .env, then rerun this script to enable Agent tasks.' -ForegroundColor Yellow
}
Start-Process $url
