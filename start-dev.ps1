param([switch]$SkipBuild, [switch]$Stop, [switch]$DatabaseOnly)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Test-PythonRuntime {
    param([string]$Interpreter, [string]$Code)
    # Windows PowerShell 5.1 strips embedded quotes from native -c arguments.
    # Send source over stdin, and let failed probes reach the repair path.
    $ErrorActionPreference = 'Continue'
    $Code | & $Interpreter - *> $null
    return ($LASTEXITCODE -eq 0)
}

if ($Stop) {
    $statePath = Join-Path $PSScriptRoot 'data/dev-processes.json'
    if (Test-Path -LiteralPath $statePath) {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        foreach ($processId in @($state.backend, $state.frontend)) {
            $ownedProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
            if ($ownedProcess -and $ownedProcess.CommandLine.Contains($PSScriptRoot)) {
                Stop-Process -Id $processId -ErrorAction SilentlyContinue
            }
        }
    }
    Write-Host 'Development services stopped. Database and files are preserved.'
    exit 0
}

# Configuration is loaded into this process only. Secrets are never printed.
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
    Write-Host 'Created .env. Add your API key there when you are ready to run Agent tasks.' -ForegroundColor Yellow
}
Get-Content -LiteralPath '.env' | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
        $value = $Matches[2].Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($Matches[1], $value, 'Process')
    }
}
if (-not $DatabaseOnly) {
    foreach ($portToCheck in @(8080, 5173)) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort $portToCheck -ErrorAction SilentlyContinue
        if ($listener) { throw "Port $portToCheck is already in use. Stop the existing service before starting another development instance." }
    }

    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) {
        $jdk = Get-ChildItem -LiteralPath "$env:USERPROFILE/.jdks" -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '17|21' -and (Test-Path -LiteralPath "$($_.FullName)/bin/javac.exe") } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/javac.exe")) {
        throw 'Set JAVA_HOME to a JDK 17 or 21 installation in .env.'
    }
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $maven = $env:MAVEN_CMD
    if (-not $maven) {
        $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($mavenCommand) { $maven = $mavenCommand.Source }
    }
    if (-not $maven) {
        $maven = Get-ChildItem -LiteralPath "$env:USERPROFILE/.m2/wrapper/dists" -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $maven) { throw 'Install Maven 3.6.3+ or set MAVEN_CMD to mvn.cmd in .env.' }
    $npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $npm) { throw 'Install Node.js 22 LTS (including npm) first.' }
}

$pgBin = $env:POSTGRES_BIN
if (-not $pgBin) { $pgBin = Join-Path $PSScriptRoot '.tools/postgresql14/pgsql/bin' }
if (-not (Test-Path -LiteralPath (Join-Path $pgBin 'pg_ctl.exe'))) {
    throw 'Set POSTGRES_BIN to a PostgreSQL 14.10 bin directory in .env, or use ./start.ps1 for Docker.'
}
$pgVersion = & (Join-Path $pgBin 'postgres.exe') --version
if ($pgVersion -notmatch '\b14\.10\b') { throw "Expected PostgreSQL 14.10. Found: $pgVersion" }
$pgPort = if ($env:POSTGRES_PORT) { $env:POSTGRES_PORT } else { '55432' }
if ($pgPort -notmatch '^\d+$') { throw 'POSTGRES_PORT must be a port number.' }
$pgData = Join-Path $PSScriptRoot 'data/pgdata'
if ($DatabaseOnly -and -not (Test-Path -LiteralPath (Join-Path $pgData 'PG_VERSION'))) {
    throw 'The existing database directory is missing. Recovery mode will not create an empty replacement database.'
}
$passwordFile = Join-Path $PSScriptRoot '.tools/pg-password.txt'
# Reuse the password belonging to this isolated development database.
$env:PGPASSWORD = if ((Test-Path -LiteralPath (Join-Path $pgData 'PG_VERSION')) -and (Test-Path -LiteralPath $passwordFile)) {
    [System.IO.File]::ReadAllText($passwordFile).Trim()
} elseif ($env:POSTGRES_PASSWORD) { $env:POSTGRES_PASSWORD } else { 'minty-local-development' }
New-Item -ItemType Directory -Force -Path data, .tools | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $pgData 'PG_VERSION'))) {
    $passwordFile = Join-Path $PSScriptRoot '.tools/pg-password.txt'
    [System.IO.File]::WriteAllText($passwordFile, $env:PGPASSWORD)
    & (Join-Path $pgBin 'initdb.exe') -D $pgData -U minty -A scram-sha-256 "--pwfile=$passwordFile" --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL initialization failed.' }
}
& (Join-Path $pgBin 'pg_ctl.exe') -D $pgData status *> $null
if ($LASTEXITCODE -ne 0) {
    & (Join-Path $pgBin 'pg_ctl.exe') -D $pgData -l (Join-Path $PSScriptRoot 'data/postgres.log') -o "-h 127.0.0.1 -p $pgPort" start
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL could not start. See data/postgres.log.' }
}
$hasDb = & (Join-Path $pgBin 'psql.exe') -h 127.0.0.1 -p $pgPort -U minty -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='minty'"
if ($LASTEXITCODE -ne 0) { throw 'Database connection failed. Check POSTGRES_PASSWORD in .env.' }
if (([string]$hasDb).Trim() -ne '1') {
    if ($DatabaseOnly) { throw 'The existing minty database was not found. Recovery mode will not create an empty replacement database.' }
    & (Join-Path $pgBin 'createdb.exe') -h 127.0.0.1 -p $pgPort -U minty minty
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the minty database.' }
}
if ($DatabaseOnly) {
    Write-Host "Existing development database is ready on 127.0.0.1:$pgPort. Frontend and backend were not restarted." -ForegroundColor Green
    exit 0
}
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$pgPort/minty"
$env:SPRING_DATASOURCE_USERNAME = 'minty'
$env:SPRING_DATASOURCE_PASSWORD = $env:PGPASSWORD
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8080'
$env:AGENT_WORKER = Join-Path $PSScriptRoot 'worker.py'
$env:AGENT_WORKSPACES = Join-Path $PSScriptRoot 'data/workspaces'
if (-not $env:AGENT_PYTHON) {
    $projectPython = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
    $env:AGENT_PYTHON = 'python'
    if (Test-Path -LiteralPath $projectPython) {
        if (Test-PythonRuntime -Interpreter $projectPython -Code 'import encodings') {
            $env:AGENT_PYTHON = $projectPython
        }
    }
} elseif (-not [System.IO.Path]::IsPathRooted($env:AGENT_PYTHON) -and $env:AGENT_PYTHON -match '[/\\]') {
    $env:AGENT_PYTHON = Join-Path $PSScriptRoot $env:AGENT_PYTHON
}
$pythonPackages = Join-Path $PSScriptRoot '.tools/python-packages'
$env:PYTHONPATH = $pythonPackages
# Check the optional response decoder too; Anaconda may expose an older system copy.
$pythonDependencyCheck = 'import anthropic, dotenv, importlib, platform; codec = importlib.import_module("brotli" if platform.python_implementation() == "CPython" else "brotlicffi"); decoder = codec.Decompressor(); decode = getattr(decoder, "process", None) or decoder.decompress; decode(b"", output_buffer_limit=1024)'
if (-not (Test-PythonRuntime -Interpreter $env:AGENT_PYTHON -Code $pythonDependencyCheck)) {
    & $env:AGENT_PYTHON -m pip install --target $pythonPackages --upgrade -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
    if (-not (Test-PythonRuntime -Interpreter $env:AGENT_PYTHON -Code $pythonDependencyCheck)) {
        throw 'Python response decoder dependency check failed.'
    }
}
if (-not $SkipBuild) {
    & $maven -f backend/pom.xml package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    Push-Location frontend
    try {
        & $npm.Source ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    } finally { Pop-Location }
}
$jar = Get-ChildItem -LiteralPath backend/target -Filter '*.jar' | Select-Object -First 1
if (-not $jar) { throw 'Build the backend first, or rerun without -SkipBuild.' }
$backend = Start-Process -FilePath $java -ArgumentList '-jar', ('"' + $jar.FullName + '"') -WorkingDirectory (Join-Path $PSScriptRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $PSScriptRoot 'data/backend.log') -RedirectStandardError (Join-Path $PSScriptRoot 'data/backend-error.log')
$node = (Get-Command node.exe).Source
$vite = Join-Path $PSScriptRoot 'frontend/node_modules/vite/bin/vite.js'
$frontend = Start-Process -FilePath $node -ArgumentList ('"' + $vite + '"'),'--host','127.0.0.1','--port','5173','--strictPort' -WorkingDirectory (Join-Path $PSScriptRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $PSScriptRoot 'data/frontend.log') -RedirectStandardError (Join-Path $PSScriptRoot 'data/frontend-error.log')
@{backend=$backend.Id;frontend=$frontend.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'data/dev-processes.json')
Write-Host "Started backend PID $($backend.Id), frontend PID $($frontend.Id). Logs: data/" -ForegroundColor Cyan
Write-Host 'Open http://127.0.0.1:5173 once the logs show that both services are ready.' -ForegroundColor Green
