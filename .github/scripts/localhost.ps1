#requires -Version 7.4
param(
    [Parameter(Mandatory)][ValidateSet('Prepare','Build','Start','Stop','Status','Outbox','RemoveTrust')][string]$Mode,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NodeHome = $env:NKW_LOCAL_NODE_HOME,
    [switch]$TrustCertificate
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$stateRoot = Join-Path $repo '.runtime/owner-localhost'
$settingsFile = Join-Path $stateRoot 'settings.json'
$jar = Join-Path $repo 'target/notes-knowledge-workspace-0.0.1-SNAPSHOT.jar'
$pg = 'nkw-owner-localhost-postgres'
$redis = 'nkw-owner-localhost-redis'
$volume = 'nkw-owner-localhost-postgres-data'
$network = 'nkw-owner-localhost'
$label = 'nkw.localhost=owner-persistent-1'
$origin = 'https://localhost:9443'

function Require([bool]$Value, [string]$Message) { if (!$Value) { throw $Message } }
function RandomValue { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
function WritePrivate([string]$Name,[string]$Value) { [IO.File]::WriteAllText((Join-Path $stateRoot $Name),$Value,[Text.UTF8Encoding]::new($false)) }
function Docker([string[]]$Arguments,[string]$InputText = '') {
    $result = if ($InputText) { $InputText | & docker.exe @Arguments 2>$null } else { & docker.exe @Arguments 2>$null }
    $category = switch ($Arguments[0]) {
        'exec' { if ($Arguments -contains 'psql') { 'database_operation' } elseif ($Arguments -contains 'redis-cli') { 'redis_readiness' } else { 'dependency_probe' } }
        'start' { 'container_start' }
        'stop' { 'container_stop' }
        'inspect' { 'ownership_check' }
        default { 'local_infrastructure' }
    }
    Require ($LASTEXITCODE -eq 0) "Local Docker operation failed: $category. Persistent state was retained."
    return $result
}
function OwnedContainer([string]$Name) {
    $names = @(Docker @('ps','-a','--format','{{.Names}}'))
    if ($names -notcontains $Name) { return $false }
    $owner = Docker @('inspect','--format','{{index .Config.Labels "nkw.localhost"}}',$Name)
    Require ($owner -eq 'owner-persistent-1') 'Container name belongs to unrelated state; nothing was changed.'
    return $true
}
function FreePort([int]$Port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
    try { $listener.Start() } catch { throw "Required localhost port $Port is occupied; do not stop an unrelated process." }
    finally { $listener.Stop() }
}
function RequireTools([bool]$Frontend) {
    Require ($JavaHome -and (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) 'Supply the approved Java 25 home with -JavaHome.'
    $script:java = Join-Path $JavaHome 'bin/java.exe'
    $version = & $script:java -version 2>&1 | Out-String
    Require ($LASTEXITCODE -eq 0 -and $version -match 'version "25\.') 'The approved working Java 25 runtime is required.'
    if ($Frontend) {
        Require ($NodeHome -and (Test-Path -LiteralPath (Join-Path $NodeHome 'node.exe'))) 'Supply Node 24.21.0 home with -NodeHome.'
        $script:node = Join-Path $NodeHome 'node.exe'
        Require ((& $script:node --version) -eq 'v24.21.0') 'Node 24.21.0 is required.'
        $script:npm = Join-Path $NodeHome 'node_modules/npm/bin/npm-cli.js'
        Require ((& $script:node $script:npm --version) -eq '11.19.0') 'npm 11.19.0 is required.'
    }
}
function ReadSettings {
    Require ((Test-Path -LiteralPath $settingsFile) -and (Test-Path -LiteralPath (Join-Path $stateRoot 'LOCALHOST_INSTANCE'))) 'Run Prepare first. Existing database state must never be paired with replacement keys.'
    Require ((Get-Content -LiteralPath (Join-Path $stateRoot 'LOCALHOST_INSTANCE') -Raw) -ceq "nkw-localhost-1`n") 'Unrecognized local instance; nothing was changed.'
    try {
        $value=Get-Content -LiteralPath $settingsFile -Raw | ConvertFrom-Json
        Require ($value.format -eq 'nkw-owner-localhost-1') 'Unsupported local state format.'
        foreach ($secret in @($value.migratorPassword,$value.runtimePassword,$value.pfxPassword)+@($value.keys.PSObject.Properties.Value)) {
            Require ($secret -cmatch '^[A-Za-z0-9+/]{43}=$') 'Invalid protected local key configuration.'
        }
        return $value
    } catch { throw 'Protected localhost settings are invalid; do not print or replace existing keys.' }
}
function LiveProcess {
    $file = Join-Path $stateRoot 'process.json'
    if (!(Test-Path -LiteralPath $file)) { return $null }
    $record = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
    $process = Get-Process -Id $record.processId -ErrorAction SilentlyContinue
    if (!$process) { return $null }
    Require ($process.StartTime.ToUniversalTime().Ticks -eq ([DateTimeOffset]$record.startedAt).UtcTicks -and $process.Path -eq $record.executable) 'PID ownership changed; no process was stopped.'
    return $process
}
function DatabaseSql([string]$Sql) {
    return Docker @('exec','-i',$pg,'psql','-X','-q','-t','-A','-v','ON_ERROR_STOP=1','-U','nkw_local_migrator','-d','nkw_owner_local') $Sql
}
function StartDependencies {
    Require (OwnedContainer $pg) 'Persistent PostgreSQL container is missing. Do not recreate it against unknown data.'
    Require (OwnedContainer $redis) 'Owned Redis container is missing; review setup before recreating it.'
    foreach ($name in @($pg,$redis)) { Docker @('start',$name) | Out-Null }
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        & docker.exe exec $pg pg_isready -U nkw_local_migrator -d nkw_owner_local *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    Require $ready 'PostgreSQL did not become ready; persistent state was retained.'
    $redisReady = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        $reply = & docker.exe exec $redis redis-cli ping 2>$null
        if ($LASTEXITCODE -eq 0 -and $reply -eq 'PONG') { $redisReady=$true; break }
        Start-Sleep -Milliseconds 500
    }
    Require $redisReady 'Redis did not become ready; persistent state was retained.'
}
function AppConfiguration($Settings,[string]$StopName) {
    $lines = @(
        'spring.profiles.active=operations,localhost', 'spring.config.import=',
        'server.address=127.0.0.1','server.port=9443','server.ssl.enabled=true',
        "server.ssl.key-store=file:$($stateRoot.Replace('\','/'))/localhost.p12",
        'server.ssl.key-store-type=PKCS12',"server.ssl.key-store-password=$($Settings.pfxPassword)",
        'management.server.port=19081','management.server.address=127.0.0.1',
        'spring.datasource.url=jdbc:postgresql://127.0.0.1:55438/nkw_owner_local',
        'spring.datasource.username=nkw_local_runtime',"spring.datasource.password=$($Settings.runtimePassword)",
        'spring.datasource.hikari.maximum-pool-size=5','spring.flyway.enabled=false',
        'spring.data.redis.host=127.0.0.1','spring.data.redis.port=56385',
        'spring.data.redis.connect-timeout=2s','spring.data.redis.timeout=2s',
        'identity.delivery.public-origin=https://localhost:9443','identity.delivery.polling-enabled=true',
        'identity.delivery.smtp-enabled=false','identity.oidc.google.enabled=false',
        'knowledge.derivation.enabled=false','knowledge.derivation.scheduling-enabled=false',
        'knowledge.public.scheduling-enabled=true',
        'notes.media-runtime.pdf.enabled=false','notes.media-runtime.video.enabled=false',
        'notes.private-storage.enabled=false','profile.avatar-storage.enabled=false',
        'profile.public-avatar-storage.enabled=false','publishing.media-storage.enabled=false',
        'notes.cursor.active.version=local1','knowledge.operations.handle.key-version=local1',
        'knowledge.operations.envelope.key-version=local1',
        "nkw.localhost.directory=$($stateRoot.Replace('\','/'))", "nkw.localhost.stop-name=$StopName"
    )
    foreach ($entry in $Settings.keys.PSObject.Properties) { $lines += "$($entry.Name)=$($entry.Value)" }
    WritePrivate 'application.properties' (($lines -join "`n")+"`n")
}
function StopApplication {
    $process = LiveProcess
    if (!$process) { return }
    $record = Get-Content -LiteralPath (Join-Path $stateRoot 'process.json') -Raw | ConvertFrom-Json
    Require ($record.stopName -match '^stop-[0-9a-f]{32}$') 'Invalid owned stop signal.'
    WritePrivate $record.stopName ''
    Require ($process.WaitForExit(120000)) 'Application did not stop gracefully. No force kill was attempted; retain diagnostics and report.'
}

Set-Location $repo
& git check-ignore -q -- '.runtime/owner-localhost/settings.json'
Require ($LASTEXITCODE -eq 0) 'Protected local state must be excluded from Git before setup.'
Require ($IsWindows) 'This owner helper is for the qualified Windows development environment only.'

if ($Mode -eq 'Build') {
    RequireTools $true
    $env:JAVA_HOME=$JavaHome; $env:PATH="$JavaHome\bin;$NodeHome;$env:PATH"
    foreach ($name in @(Get-ChildItem Env: | Select-Object -ExpandProperty Name | Where-Object {
        $_ -match '^(SPRING_|GOOGLE_|GEMINI_|GROQ_|AWS_|OCI_|IDENTITY_|KNOWLEDGE_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)'
    })) { Remove-Item -LiteralPath "Env:$name" }
    $env:TEMP=Join-Path $repo '.runtime/localhost-finalization/temp'; $env:TMP=$env:TEMP
    if (!$env:NPM_CONFIG_CACHE) { $env:NPM_CONFIG_CACHE=Join-Path $repo '.runtime/tool-cache/npm' }
    if (!$env:MAVEN_OPTS) { $env:MAVEN_OPTS='-Xmx512m "-Dmaven.repo.local='+(Join-Path $repo '.runtime/tool-cache/maven-repository')+'"' }
    New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
    & $script:node (Join-Path $PSScriptRoot 'package-application.mjs')
    exit $LASTEXITCODE
}
if ($Mode -eq 'Prepare') {
    RequireTools $false
    if (Test-Path -LiteralPath $stateRoot) {
        $settings=ReadSettings
        Write-Output 'Existing protected setup retained. Use Start; no database, keys or certificate was replaced.'
        exit 0
    }
    Require $TrustCertificate 'Prepare requires explicit -TrustCertificate approval for Current User localhost trust.'
    Require (!(OwnedContainer $pg) -and !(OwnedContainer $redis)) 'Existing containers require their original keys; setup refused.'
    $volumes=@(Docker @('volume','ls','--format','{{.Name}}'))
    Require ($volumes -notcontains $volume) 'An existing database volume requires its original keys; setup refused.'
    $networks=@(Docker @('network','ls','--format','{{.Name}}'))
    Require ($networks -notcontains $network) 'An existing local network requires review before reuse.'
    foreach ($port in @(9443,19081,55438,56385)) { FreePort $port }
    New-Item -ItemType Directory -Path $stateRoot | Out-Null
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $stateRoot /inheritance:r /grant:r "*${sid}:(OI)(CI)F" *> $null
    Require ($LASTEXITCODE -eq 0) 'Could not restrict local state access; no secret was generated.'
    WritePrivate 'LOCALHOST_INSTANCE' "nkw-localhost-1`n"
    foreach ($name in @('outbox','private-attachment','private-avatar','public-profile-avatar','public-publication-media','temp','logs')) {
        New-Item -ItemType Directory -Path (Join-Path $stateRoot $name) | Out-Null
    }
    $keys=[ordered]@{}
    foreach ($name in @('identity.delivery.key-base64','identity.mfa.key-base64','identity.mfa.handle-key-base64',
        'identity.session-handle.key-base64','identity.rate.key-base64','notes.cursor.active.key-base64',
        'publishing.preview.key-base64','knowledge.operations.handle.key-base64','knowledge.operations.envelope.key-base64')) { $keys[$name]=RandomValue }
    $settings=[ordered]@{ format='nkw-owner-localhost-1'; migratorPassword=(RandomValue); runtimePassword=(RandomValue); pfxPassword=(RandomValue); keys=$keys; certificateThumbprint='' }
    # Persist generated keys before creating any database. Partial setup fails safe,
    # never inventing replacement keys for an already-created persistent volume.
    WritePrivate 'settings.json' ($settings | ConvertTo-Json -Depth 4)
    WritePrivate 'postgres-password' $settings.migratorPassword
    $certificate=New-SelfSignedCertificate -Subject 'CN=NKW owner localhost' -DnsName localhost -CertStoreLocation Cert:\CurrentUser\My -KeyAlgorithm RSA -KeyLength 2048 -KeyExportPolicy Exportable -NotAfter (Get-Date).AddDays(90)
    $settings.certificateThumbprint=$certificate.Thumbprint
    WritePrivate 'settings.json' ($settings | ConvertTo-Json -Depth 4)
    Export-Certificate -Cert $certificate -FilePath (Join-Path $stateRoot 'localhost.cer') | Out-Null
    Export-PfxCertificate -Cert $certificate -FilePath (Join-Path $stateRoot 'localhost.p12') -Password (ConvertTo-SecureString $settings.pfxPassword -AsPlainText -Force) | Out-Null
    Import-Certificate -FilePath (Join-Path $stateRoot 'localhost.cer') -CertStoreLocation Cert:\CurrentUser\Root | Out-Null
    # Docker Desktop does not publish ports for an internal-only bridge. These
    # services instead publish exclusively on host loopback; no provider lives here.
    Docker @('network','create','--label',$label,$network) | Out-Null
    Docker @('volume','create','--label',$label,$volume) | Out-Null
    Docker @('run','-d','--pull=never','--name',$pg,'--label',$label,'--network',$network,'--memory','768m','--cpus','1','--pids-limit','128',
        '-p','127.0.0.1:55438:5432','--mount',"type=volume,source=$volume,target=/var/lib/postgresql",'--mount',"type=bind,source=$stateRoot/postgres-password,target=/run/secrets/postgres-password,readonly",
        '-e','POSTGRES_USER=nkw_local_migrator','-e','POSTGRES_DB=nkw_owner_local','-e','POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password','pgvector/pgvector:0.8.6-pg18-trixie') | Out-Null
    Docker @('run','-d','--pull=never','--name',$redis,'--label',$label,'--network',$network,'--memory','128m','--cpus','1','--pids-limit','64',
        '-p','127.0.0.1:56385:6379','redis:8.10.2','redis-server','--appendonly','no','--save','') | Out-Null
    StartDependencies
    Write-Output 'Protected persistent localhost dependencies prepared. No account was seeded. Build, then Start.'
    exit 0
}
$settings=ReadSettings
if ($Mode -eq 'RemoveTrust') {
    $thumbprint=$settings.certificateThumbprint
    Require ($thumbprint -match '^[A-F0-9]{40}$') 'Stored certificate identity unavailable.'
    foreach ($store in @('Root','My')) {
        $entry="Cert:\CurrentUser\$store\$thumbprint"
        if (Test-Path -LiteralPath $entry) { Remove-Item -LiteralPath $entry }
    }
    Write-Output 'Only this localhost certificate was removed from Current User stores. HTTPS startup now requires an explicitly prepared trusted certificate.'
    exit 0
}
if ($Mode -eq 'Outbox') {
    Invoke-Item -LiteralPath (Join-Path $stateRoot 'outbox')
    Write-Output 'Owner-only controlled outbox opened. Links contain local capabilities: never share or archive them.'
    exit 0
}
if ($Mode -eq 'Status') {
    $running=$null -ne (LiveProcess)
    $ready=$false
    if ($running) { try { $ready=(Invoke-WebRequest 'http://127.0.0.1:19081/actuator/health/readiness' -TimeoutSec 5).StatusCode -eq 200 } catch { } }
    Write-Output "Owned application running=$running; readiness=$ready. Data and keys retained."
    exit 0
}
if ($Mode -eq 'Stop') {
    StopApplication
    foreach ($name in @($redis,$pg)) { if (OwnedContainer $name) { Docker @('stop','--time','30',$name) | Out-Null } }
    Write-Output 'Owned application and containers stopped. Persistent database, objects, keys and certificate retained.'
    exit 0
}
if ($Mode -eq 'Start') {
    RequireTools $false
    Require (!(LiveProcess)) 'The owned application is already running; use Status.'
    Require (Test-Path -LiteralPath $jar) 'Build the packaged application first.'
    foreach ($port in @(9443,19081)) { FreePort $port }
    Require (Test-Path -LiteralPath "Cert:\CurrentUser\Root\$($settings.certificateThumbprint)") 'Localhost certificate is not trusted. Do not bypass browser warnings.'
    $certificate=Get-Item -LiteralPath "Cert:\CurrentUser\Root\$($settings.certificateThumbprint)"
    Require ($certificate.NotAfter -gt (Get-Date).AddDays(1)) 'Localhost certificate expires soon; review explicit replacement before startup.'
    $started=$false
    try {
        StartDependencies
        WritePrivate 'migration.properties' ("spring.flyway.url=jdbc:postgresql://127.0.0.1:55438/nkw_owner_local`nspring.flyway.user=nkw_local_migrator`nspring.flyway.password=$($settings.migratorPassword)`n")
        $migration=Join-Path $stateRoot 'migration.properties'
        & $script:java '-Duser.timezone=UTC' '-Dloader.main=org.notesknowledge.LocalhostMigrationCommand' '-cp' $jar org.springframework.boot.loader.launch.PropertiesLauncher $migration *> (Join-Path $stateRoot 'logs/migration.log')
        Require ($LASTEXITCODE -eq 0) 'Local migration failed; no runtime started. Preserve the private migration log; do not share raw output.'
        $roleExists=DatabaseSql "select count(*) from pg_roles where rolname='nkw_local_runtime'"
        if ($roleExists.Trim() -eq '0') { DatabaseSql "create role nkw_local_runtime login password '$($settings.runtimePassword)' nosuperuser nocreatedb nocreaterole noreplication nobypassrls" | Out-Null }
        $grants=Get-Content -LiteralPath (Join-Path $repo 'src/main/resources/operations/runtime-role.sql') -Raw
        DatabaseSql ($grants.Replace(':"runtime_role"','nkw_local_runtime')) | Out-Null
        $stopName='stop-'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16)).ToLowerInvariant()
        AppConfiguration $settings $stopName
        $config=Join-Path $stateRoot 'application.properties'
        $start=[Diagnostics.ProcessStartInfo]::new($script:java)
        $start.UseShellExecute=$false; $start.CreateNoWindow=$true; $start.WorkingDirectory=$repo
        $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
        foreach ($key in @($start.Environment.Keys)) {
            if ($key -match '^(SPRING_|GOOGLE_|GEMINI_|GROQ_|AWS_|OCI_|IDENTITY_|KNOWLEDGE_|NOTES_|PROFILE_|PUBLISHING_|MANAGEMENT_|SERVER_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)') { $start.Environment.Remove($key) | Out-Null }
        }
        $start.Environment['TEMP']=Join-Path $stateRoot 'temp'; $start.Environment['TMP']=$start.Environment['TEMP']
        foreach ($argument in @('-Xmx768m','-XX:MaxDirectMemorySize=128m','-XX:ActiveProcessorCount=2','-Duser.timezone=UTC',"-Djava.io.tmpdir=$stateRoot/temp",'-jar',$jar,
            "--spring.config.location=classpath:/application.properties,classpath:/application-operations.properties,file:$($config.Replace('\','/'))")) { $start.ArgumentList.Add($argument) }
        $process=[Diagnostics.Process]::Start($start)
        # Drain pipes in managed threads, not PowerShell event handlers; those die
        # when this one-shot command returns. Logs are deliberately private.
        $log=Join-Path $stateRoot ("logs/app-"+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log')
        $errorLog=$log+'.error'
        $outputFile=[IO.File]::Open($log,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $errorFile=[IO.File]::Open($errorLog,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $outputTask=$process.StandardOutput.BaseStream.CopyToAsync($outputFile)
        $errorTask=$process.StandardError.BaseStream.CopyToAsync($errorFile)
        WritePrivate 'process.json' (@{processId=$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('o');executable=$script:java;stopName=$stopName} | ConvertTo-Json)
        $ready=$false
        for ($attempt=0; $attempt -lt 180 -and !$process.HasExited; $attempt++) {
            try { if ((Invoke-WebRequest 'http://127.0.0.1:19081/actuator/health/readiness' -TimeoutSec 2).StatusCode -eq 200) { $ready=$true;break } } catch { }
            Start-Sleep -Milliseconds 500
        }
        Require $ready 'Application did not become ready; inspect only sanitized diagnostics in the troubleshooting guide.'
        $started=$true
        Write-Output "Ready: $origin — persistent local PostgreSQL; controlled local outbox/objects; no live AI/OIDC/SMTP/cloud."
        # Keep the supervising command alive. Ctrl+C is not the stop contract:
        # use the explicit Stop command in another shell for graceful shutdown.
        $process.WaitForExit()
        $outputTask.GetAwaiter().GetResult() | Out-Null; $errorTask.GetAwaiter().GetResult() | Out-Null
        $outputFile.Dispose();$errorFile.Dispose()
        exit $process.ExitCode
    } finally {
        if (!$started) {
            StopApplication
            foreach ($name in @($redis,$pg)) { if (OwnedContainer $name) { Docker @('stop','--time','30',$name) | Out-Null } }
        }
    }
}
