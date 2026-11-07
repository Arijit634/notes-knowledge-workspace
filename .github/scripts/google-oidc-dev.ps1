#requires -Version 7.4
<#
.SYNOPSIS
Owner-only real Google sign-in on Windows. Never enables live AI or SMTP.
.EXAMPLE
pwsh -NoProfile -File .github/scripts/google-oidc-dev.ps1 -Mode Prepare -ClientId '<web-client-id>.apps.googleusercontent.com'
.EXAMPLE
pwsh -NoProfile -File .github/scripts/google-oidc-dev.ps1 -Mode SetSecret
.EXAMPLE
pwsh -NoProfile -File .github/scripts/google-oidc-dev.ps1 -Mode Start
.NOTES
Prepare generates independent keys/data/TLS and installs only its localhost
certificate in Current User trust. SetSecret prompts privately and uses Windows
Current User DPAPI. Never paste the secret into command arguments or transcripts.
Build uses a public-source snapshot so it cannot replace a running synthetic JAR.
Start checks credentials and TLS before stopping the owned synthetic application.
Use Stop in another shell; RestoreSynthetic starts the original data with AI off.
Outbox opens private local security messages; it sends no internet email.
#>
param(
    [ValidateSet('Prepare','SetSecret','Build','Start','Stop','Status','RestoreSynthetic','Outbox')][string]$Mode='Status',
    [string]$ClientId,
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$NodeHome=$env:NKW_LOCAL_NODE_HOME
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$script:repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$script:stateRoot=Join-Path $script:repo '.runtime/google-oidc-dev'
$script:pg='nkw-google-oidc-dev-postgres'
$script:redis='nkw-google-oidc-dev-redis'
$script:volume='nkw-google-oidc-dev-postgres-data'
$script:network='nkw-google-oidc-dev'
$script:owner='google-oidc-dev-1'
$script:origin='https://localhost:9443'
$script:operation='preflight'

function Assert-Nkw([bool]$Condition,[string]$Message) { if(!$Condition){throw [InvalidOperationException]::new("NKW OIDC: $Message")} }
function New-NkwKey { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
function Assert-NkwPath([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path)
    Assert-Nkw ($full -eq $script:stateRoot -or $full.StartsWith($script:stateRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) 'Unexpected private-state path.'
    $current=$full
    while($current){
        if(Test-Path -LiteralPath $current){Assert-Nkw (!((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked private-state path refused.'}
        $parent=[IO.Path]::GetDirectoryName($current);if($parent -eq $current){break};$current=$parent
    }
}
function Protect-NkwDirectory([string]$Directory) {
    Assert-NkwPath $Directory
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl=[Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
    $rule=[Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
    $acl.AddAccessRule($rule);Set-Acl -LiteralPath $Directory -AclObject $acl
}
function Assert-NkwPrivate([string]$Path) {
    Assert-NkwPath $Path
    $acl=Get-Acl -LiteralPath $Path
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    Assert-Nkw ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -eq $sid) 'Private state is not owned by this Windows user.'
    foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
        Assert-Nkw ($rule.AccessControlType -ne 'Allow' -or $rule.IdentityReference.Value -eq $sid) 'Private-state permissions are too broad; no secret was read.'
    }
}
function Write-NkwPrivate([string]$Name,[string]$Text) {
    $path=Join-Path $script:stateRoot $Name;Assert-NkwPath $path
    Assert-NkwPrivate $script:stateRoot
    if(Test-Path -LiteralPath $path){Assert-NkwPrivate $path}
    [IO.File]::WriteAllText($path,$Text,[Text.UTF8Encoding]::new($false));Assert-NkwPrivate $path
}
function Write-NkwBootstrap {
    $engine=(Join-Path $PSHOME 'pwsh.exe').Replace("'","''")
    $launcher=(Join-Path $script:repo '.github/scripts/google-oidc-dev.ps1').Replace("'","''")
    $template=@'
#requires -Version 5.1
param([ValidateSet('Prepare','SetSecret','Build','Start','Stop','Status','RestoreSynthetic','Outbox')][string]$Mode='Status')
& '__ENGINE__' -NoProfile -File '__LAUNCHER__' -Mode $Mode
exit $LASTEXITCODE
'@
    Write-NkwPrivate 'run.ps1' ($template.Replace('__ENGINE__',$engine).Replace('__LAUNCHER__',$launcher))
}
function Convert-NkwPlain([Security.SecureString]$Value) {
    $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)} finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
function Write-NkwSettings($Value) {
    $secure=ConvertTo-SecureString ($Value|ConvertTo-Json -Depth 6 -Compress) -AsPlainText -Force
    try {Write-NkwPrivate 'settings.protected' (ConvertFrom-SecureString $secure)} finally {$secure.Dispose()}
}
function Read-NkwSettings {
    Assert-NkwPrivate $script:stateRoot
    $marker=Join-Path $script:stateRoot 'OIDC_DEV_INSTANCE';Assert-NkwPrivate $marker
    Assert-Nkw ([IO.File]::ReadAllText($marker) -ceq "nkw-google-oidc-dev-1`n") 'Unrecognized state. Existing keys/data were retained.'
    $path=Join-Path $script:stateRoot 'settings.protected';Assert-NkwPrivate $path
    try {
        $secure=ConvertTo-SecureString ([IO.File]::ReadAllText($path))
        try {$s=(Convert-NkwPlain $secure)|ConvertFrom-Json} finally {$secure.Dispose()}
    } catch {throw [InvalidOperationException]::new('NKW OIDC: Cannot decrypt protected settings for this Windows user. Do not replace keys or data.')}
    Assert-Nkw ($s.format -eq 'nkw-google-oidc-dev-1' -and $s.clientId -cmatch '^[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$') 'Invalid isolated settings.'
    foreach($key in @($s.migratorPassword,$s.runtimePassword,$s.pfxPassword)+@($s.keys.PSObject.Properties.Value)) {
        Assert-Nkw ($key -cmatch '^[A-Za-z0-9+/]{43}=$') 'Invalid isolated key configuration.'
    }
    return $s
}
function Save-NkwClientSecret([Security.SecureString]$Secret) {
    Assert-NkwPrivate $script:stateRoot
    $plain=Convert-NkwPlain $Secret
    Assert-Nkw ($plain -cmatch '^[A-Za-z0-9_-]{12,256}$') 'Client Secret format is invalid. Enter the Google Web client secret, not a client ID.'
    $plain=$null
    Write-NkwPrivate 'client-secret.protected' (ConvertFrom-SecureString $Secret)
}
function Read-NkwClientSecret {
    $path=Join-Path $script:stateRoot 'client-secret.protected'
    Assert-Nkw (Test-Path -LiteralPath $path -PathType Leaf) 'Client Secret is missing. Run -Mode SetSecret locally. The synthetic app has not been stopped.'
    Assert-NkwPrivate $path
    try {$secure=ConvertTo-SecureString ([IO.File]::ReadAllText($path));try {$value=Convert-NkwPlain $secure}finally{$secure.Dispose()}}
    catch {throw [InvalidOperationException]::new('NKW OIDC: Client Secret cannot be decrypted by this Windows user. Re-enter it locally with SetSecret.')}
    Assert-Nkw ($value -cmatch '^[A-Za-z0-9_-]{12,256}$') 'Protected Client Secret is invalid.'
    return $value
}
function Invoke-NkwDocker([string[]]$Arguments,[string]$InputText='') {
    $result=if($InputText){$InputText|& docker.exe @Arguments 2>$null}else{& docker.exe @Arguments 2>$null}
    Assert-Nkw ($LASTEXITCODE -eq 0) 'Docker operation failed. Check Docker Desktop; existing state was retained.'
    return $result
}
function Test-NkwContainer([string]$Name) {
    $names=@(Invoke-NkwDocker @('ps','-a','--format','{{.Names}}'))
    if($names -notcontains $Name){return $false}
    $label=Invoke-NkwDocker @('inspect','--format','{{index .Config.Labels "nkw.oidc-dev"}}',$Name)
    Assert-Nkw ($label -eq $script:owner) 'Container name is owned by unrelated state. No changes made.'
    return $true
}
function Assert-NkwFreePort([int]$Port) {
    $socket=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
    try {$socket.Start()}catch{throw [InvalidOperationException]::new("NKW OIDC: Port $Port is occupied. An unrelated process will not be stopped.")}
    finally{$socket.Stop()}
}
function Get-NkwProcess {
    $path=Join-Path $script:stateRoot 'process.json'
    if(!(Test-Path -LiteralPath $path)){return $null}
    Assert-NkwPrivate $path
    $record=[IO.File]::ReadAllText($path)|ConvertFrom-Json
    $p=Get-Process -Id $record.processId -ErrorAction SilentlyContinue
    if(!$p){return $null}
    Assert-Nkw ($p.StartTime.ToUniversalTime().Ticks -eq ([DateTimeOffset]$record.startedAt).UtcTicks -and $p.Path -eq $record.executable) 'PID ownership changed; no process was stopped.'
    return $p
}
function Enter-NkwOperation {
    $path=Join-Path $script:stateRoot 'operation.lock';Assert-NkwPath $path
    try {return [IO.File]::Open($path,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
    catch {throw [InvalidOperationException]::new('NKW OIDC: Another setup/start/stop operation is running. Wait for it to finish; no process was stopped.')}
}
function Remove-NkwProcessRecord([int]$Id,[DateTimeOffset]$StartedAt) {
    $path=Join-Path $script:stateRoot 'process.json';Assert-NkwPath $path
    try{$current=[IO.File]::ReadAllText($path)|ConvertFrom-Json}catch [IO.FileNotFoundException]{return}
    if($current.processId -eq $Id -and ([DateTimeOffset]$current.startedAt).UtcTicks -eq $StartedAt.UtcTicks){[IO.File]::Delete($path)}
}
function Stop-NkwApplication {
    $p=Get-NkwProcess;if(!$p){return}
    $record=[IO.File]::ReadAllText((Join-Path $script:stateRoot 'process.json'))|ConvertFrom-Json
    Assert-Nkw ($record.stopName -cmatch '^stop-[0-9a-f]{32}$') 'Invalid owned stop signal.'
    Write-NkwPrivate $record.stopName ''
    Assert-Nkw ($p.WaitForExit(120000)) 'Graceful shutdown timed out. No process was killed; retain private diagnostics.'
    Remove-NkwProcessRecord $record.processId $record.startedAt
}
function Find-NkwJava([string]$Requested) {
    $candidates=@()
    if($Requested){$candidates+=Join-Path $Requested 'bin/java.exe'}
    else {
        $command=Get-Command java.exe -ErrorAction SilentlyContinue;if($command){$candidates+=$command.Source}
        $vendor=Join-Path $env:ProgramFiles 'Eclipse Adoptium'
        if(Test-Path -LiteralPath $vendor){$candidates+=@(Get-ChildItem -LiteralPath $vendor -Directory -Filter 'jdk-25*'|ForEach-Object{Join-Path $_.FullName 'bin/java.exe'})}
    }
    foreach($candidate in $candidates){if(Test-Path -LiteralPath $candidate){$version=& $candidate -version 2>&1|Out-String;if($LASTEXITCODE -eq 0 -and $version -match 'version "25\.'){return [IO.Path]::GetFullPath($candidate)}}}
    throw [InvalidOperationException]::new('NKW OIDC: Approved Java 25 unavailable. Supply -JavaHome; no toolchain was installed.')
}
function Assert-NkwTls($Settings) {
    Assert-Nkw ($Settings.certificateThumbprint -cmatch '^[A-F0-9]{40}$') 'Isolated localhost certificate is not prepared.'
    foreach($store in @('My','Root')) {
        $path="Cert:\CurrentUser\$store\$($Settings.certificateThumbprint)"
        Assert-Nkw (Test-Path -LiteralPath $path) 'Isolated localhost certificate is missing or untrusted; no TLS bypass is allowed.'
        $cert=Get-Item -LiteralPath $path
        Assert-Nkw ($cert.NotBefore -le (Get-Date) -and $cert.NotAfter -gt (Get-Date).AddDays(1) -and @($cert.DnsNameList|Where-Object {$_.Unicode -eq 'localhost'}).Count -eq 1) 'Localhost certificate is invalid or expires soon.'
    }
    Assert-NkwPrivate (Join-Path $script:stateRoot 'localhost.p12')
    try {
        $pfx=[Security.Cryptography.X509Certificates.X509Certificate2]::new((Join-Path $script:stateRoot 'localhost.p12'),$Settings.pfxPassword,[Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
        try {Assert-Nkw ($pfx.Thumbprint -eq $Settings.certificateThumbprint -and $pfx.HasPrivateKey) 'Isolated TLS key/certificate mismatch.'}finally{$pfx.Dispose()}
    }catch {throw [InvalidOperationException]::new('NKW OIDC: Isolated TLS key/certificate is invalid. Existing synthetic TLS was not changed.')}
}
function Get-NkwProviderClockSkew([DateTimeOffset]$Date,[long]$Age,[DateTimeOffset]$Observed) {
    Assert-Nkw ($Age -ge 0 -and $Age -le 86400) 'Invalid provider time metadata.'
    $skew=[Math]::Abs(($Observed-$Date.AddSeconds($Age)).TotalSeconds)
    # Match the existing Spring OIDC validator's 60-second window; never change it.
    Assert-Nkw ($skew -le 60) 'Windows clock differs from Google. Synchronize Date & time before signing in.'
    return $skew
}
function Test-NkwProvider {
    try {
        $response=Invoke-WebRequest ('https://accounts.google.com/.well-known/openid-configuration?nkw_preflight='+[guid]::NewGuid().ToString('N')) -Headers @{'Cache-Control'='no-cache';'Pragma'='no-cache'} -TimeoutSec 15
        $m=$response.Content|ConvertFrom-Json
        Assert-Nkw ($m.issuer -eq 'https://accounts.google.com' -and ([uri]$m.authorization_endpoint).Host -eq 'accounts.google.com' -and ([uri]$m.token_endpoint).Scheme -eq 'https') 'Google discovery metadata is not as expected.'
        $date=[DateTimeOffset]::Parse([string](@($response.Headers['Date'])[0]),[Globalization.CultureInfo]::InvariantCulture)
        $age=0L;if($response.Headers.ContainsKey('Age')){Assert-Nkw ([long]::TryParse([string](@($response.Headers['Age'])[0]),[ref]$age)) 'Invalid provider cache metadata.'}
        $skew=Get-NkwProviderClockSkew $date $age ([DateTimeOffset]::UtcNow)
        if($skew -gt 30){Write-Warning 'Windows time is close to the existing OIDC clock-skew limit. Sync Date & time before extended testing.'}
    } catch {
        if($_.Exception.Message.StartsWith('NKW OIDC:')){throw}
        throw [InvalidOperationException]::new('NKW OIDC: Google discovery/TLS preflight failed. Check network and Windows time; no secret or authorization request was sent.')
    }
}
function Get-NkwConfiguration($Settings,[string]$StopName) {
    Assert-Nkw ($Settings.clientId -cmatch '^[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$' -and $StopName -cmatch '^stop-[0-9a-f]{32}$') 'Invalid isolated configuration.'
    $root=$script:stateRoot.Replace('\','/')
    $lines=@(
        'spring.profiles.active=operations,google-oidc-dev','spring.config.import=',
        'server.address=127.0.0.1','server.port=9443','server.ssl.enabled=true','server.forward-headers-strategy=none',
        "server.ssl.key-store=file:$root/localhost.p12",'server.ssl.key-store-type=PKCS12','server.ssl.key-store-password=${NKW_OIDC_TLS_PASSWORD}',
        'server.tomcat.accesslog.enabled=false','spring.mvc.log-request-details=false','spring.http.log-request-details=false','debug=false',
        'management.server.address=127.0.0.1','management.server.port=19082','management.server.ssl.enabled=false',
        'management.endpoints.web.exposure.include=health','management.endpoint.health.show-details=never',
        'management.health.redis.enabled=true','management.endpoint.health.group.readiness.include=readinessState,db,coreSchema,redis',
        'spring.datasource.url=jdbc:postgresql://127.0.0.1:55448/nkw_oidc_dev','spring.datasource.username=nkw_oidc_runtime',
        'spring.datasource.password=${NKW_OIDC_DB_PASSWORD}','spring.datasource.hikari.maximum-pool-size=5','spring.flyway.enabled=false',
        'spring.data.redis.host=127.0.0.1','spring.data.redis.port=56395','spring.data.redis.connect-timeout=2s','spring.data.redis.timeout=2s',
        'identity.oidc.google.enabled=${GOOGLE_OIDC_ENABLED:false}','identity.oidc.google.client-id=${GOOGLE_OIDC_CLIENT_ID:}',
        'identity.oidc.google.client-secret=${GOOGLE_OIDC_CLIENT_SECRET:}','identity.oidc.google.issuer=https://accounts.google.com',
        'identity.oidc.google.login-redirect-uri=${GOOGLE_OIDC_LOGIN_REDIRECT_URI:}',
        'identity.oidc.google.link-redirect-uri=${GOOGLE_OIDC_LINK_REDIRECT_URI:}',
        'identity.delivery.public-origin=https://localhost:9443','identity.delivery.polling-enabled=true','identity.delivery.smtp-enabled=false',
        'knowledge.derivation.enabled=false','knowledge.derivation.scheduling-enabled=false','knowledge.public.scheduling-enabled=false',
        'notes.media-runtime.pdf.enabled=false','notes.media-runtime.video.enabled=false','notes.private-storage.enabled=false',
        'profile.avatar-storage.enabled=false','profile.public-avatar-storage.enabled=false','publishing.media-storage.enabled=false',
        'notes.cursor.active.version=local1','knowledge.operations.handle.key-version=local1','knowledge.operations.envelope.key-version=local1',
        "nkw.localhost.directory=$root","nkw.localhost.stop-name=$StopName")
    $index=0
    foreach($key in $Settings.keys.PSObject.Properties.Name){$lines+=($key+'=${NKW_OIDC_KEY_'+$index+'}');$index++}
    return ($lines -join "`n")+"`n"
}
function New-NkwStartInfo($Settings,[string]$Secret,[string]$StopName) {
    $start=[Diagnostics.ProcessStartInfo]::new($Settings.java)
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.WorkingDirectory=$script:repo
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    foreach($key in @($start.Environment.Keys)) {
        if($key -match '^(SPRING_|GOOGLE_|GEMINI_|GROQ_|AWS_|OCI_|IDENTITY_|KNOWLEDGE_|NOTES_|PROFILE_|PUBLISHING_|MANAGEMENT_|SERVER_|LOGGING_|NKW_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)'){$start.Environment.Remove($key)|Out-Null}
    }
    $start.Environment['GOOGLE_OIDC_ENABLED']='true';$start.Environment['GOOGLE_OIDC_CLIENT_ID']=$Settings.clientId
    $start.Environment['GOOGLE_OIDC_CLIENT_SECRET']=$Secret
    $start.Environment['GOOGLE_OIDC_LOGIN_REDIRECT_URI']=$script:origin+'/api/auth/oidc/google/callback'
    $start.Environment['GOOGLE_OIDC_LINK_REDIRECT_URI']=$script:origin+'/api/auth/oidc/google/link-callback'
    $start.Environment['NKW_OIDC_DB_PASSWORD']=$Settings.runtimePassword;$start.Environment['NKW_OIDC_TLS_PASSWORD']=$Settings.pfxPassword
    $index=0;foreach($key in $Settings.keys.PSObject.Properties){$start.Environment['NKW_OIDC_KEY_'+$index]=$key.Value;$index++}
    $start.Environment['TEMP']=Join-Path $script:stateRoot 'temp';$start.Environment['TMP']=$start.Environment['TEMP']
    foreach($arg in @('-Xmx768m','-XX:MaxDirectMemorySize=128m','-XX:ActiveProcessorCount=2','-XX:-HeapDumpOnOutOfMemoryError',
        "-XX:ErrorFile=$script:stateRoot/logs/hs_err_pid%p.log",'-Duser.timezone=UTC',"-Djava.io.tmpdir=$script:stateRoot/temp",'-jar',
        (Join-Path $script:stateRoot 'application.jar'),
        "--spring.config.location=classpath:/application.properties,classpath:/application-operations.properties,file:$($script:stateRoot.Replace('\','/'))/application.properties")){$start.ArgumentList.Add($arg)}
    return $start
}
function Start-NkwDependencies {
    $bindings=Invoke-NkwDocker @('inspect','--format','{{json .HostConfig.PortBindings}}',$script:redis)|ConvertFrom-Json
    Assert-Nkw (@($bindings.'6379/tcp').Count -eq 1 -and $bindings.'6379/tcp'[0].HostIp -ceq '127.0.0.1' -and $bindings.'6379/tcp'[0].HostPort -ceq '56395') 'Isolated Redis must be bound only to 127.0.0.1:56395.'
    foreach($name in @($script:pg,$script:redis)){Assert-Nkw (Test-NkwContainer $name) 'Isolated dependency is missing. Existing database/key state must not be replaced.';Invoke-NkwDocker @('start',$name)|Out-Null}
    $ready=$false
    for($i=0;$i -lt 60;$i++) {& docker.exe exec $script:pg pg_isready -U nkw_oidc_migrator -d nkw_oidc_dev *> $null;if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Milliseconds 500}
    Assert-Nkw $ready 'Isolated PostgreSQL did not become ready.'
    Assert-Nkw ((Invoke-NkwDocker @('exec',$script:redis,'redis-cli','ping')) -eq 'PONG') 'Isolated Redis is unavailable.'
}
function Invoke-NkwSql([string]$Sql) {Invoke-NkwDocker @('exec','-i',$script:pg,'psql','-X','-q','-t','-A','-v','ON_ERROR_STOP=1','-U','nkw_oidc_migrator','-d','nkw_oidc_dev') $Sql}
function Invoke-NkwMigration($Settings) {
    $jar=Join-Path $script:stateRoot 'application.jar';Assert-Nkw (Test-Path -LiteralPath $jar) 'Build the isolated application before preparing its database.'
    Start-NkwDependencies
    $path=Join-Path $script:stateRoot 'migration.properties'
    try {
        Write-NkwPrivate 'migration.properties' ("spring.flyway.url=jdbc:postgresql://127.0.0.1:55448/nkw_oidc_dev`nspring.flyway.user=nkw_oidc_migrator`nspring.flyway.password=$($Settings.migratorPassword)`n")
        & $Settings.java '-Duser.timezone=UTC' '-Dloader.main=org.notesknowledge.LocalhostMigrationCommand' '-cp' $jar org.springframework.boot.loader.launch.PropertiesLauncher $path *> (Join-Path $script:stateRoot 'logs/migration.log')
        Assert-Nkw ($LASTEXITCODE -eq 0) 'Flyway validation failed. The isolated database and keys were retained; do not share its private log.'
        $exists=Invoke-NkwSql "select count(*) from pg_roles where rolname='nkw_oidc_runtime'"
        if($exists.Trim() -eq '0'){Invoke-NkwSql "create role nkw_oidc_runtime login password '$($Settings.runtimePassword)' nosuperuser nocreatedb nocreaterole noreplication nobypassrls"|Out-Null}
        $grants=[IO.File]::ReadAllText((Join-Path $script:repo 'src/main/resources/operations/runtime-role.sql'))
        Invoke-NkwSql ($grants.Replace(':"runtime_role"','nkw_oidc_runtime'))|Out-Null
    } finally {if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}}
}
function Assert-NkwListenerOwnership([string]$Kind) {
    $listeners=@(Get-NetTCPConnection -LocalPort 9443 -State Listen -ErrorAction SilentlyContinue)
    if(!$listeners.Count){return}
    if($Kind -eq 'synthetic') {
        $file=Join-Path $script:repo '.runtime/owner-localhost/process.json'
        Assert-Nkw (Test-Path -LiteralPath $file) 'Port 9443 belongs to an unrecognized process.'
        $record=[IO.File]::ReadAllText($file)|ConvertFrom-Json
        $p=Get-Process -Id $record.processId -ErrorAction SilentlyContinue
        Assert-Nkw ($null -ne $p -and $p.StartTime.ToUniversalTime().Ticks -eq ([DateTimeOffset]$record.startedAt).UtcTicks -and $p.Path -eq $record.executable) 'Original application PID ownership changed.'
    } else {$p=Get-NkwProcess;Assert-Nkw ($null -ne $p) 'Port 9443 belongs to another process.'}
    foreach($listener in $listeners){Assert-Nkw ($listener.OwningProcess -eq $p.Id -and $listener.LocalAddress -in @('127.0.0.1','::1')) 'Unexpected HTTPS port ownership. Nothing was stopped.'}
}
function Start-NkwApplication($Settings) {
    $guard=Enter-NkwOperation
    try {
    Assert-Nkw (!(Get-NkwProcess)) 'The isolated Google instance is already running.'
    # All credential/configuration checks happen before touching the other instance.
    $secret=Read-NkwClientSecret;Assert-NkwTls $Settings;Test-NkwProvider
    Assert-Nkw (Test-Path -LiteralPath (Join-Path $script:stateRoot 'application.jar')) 'Run Build first.'
    $buildPath=Join-Path $script:stateRoot 'build.json';Assert-NkwPrivate $buildPath
    $binding=[IO.File]::ReadAllText($buildPath)|ConvertFrom-Json
    Assert-Nkw ((Get-FileHash -LiteralPath (Join-Path $script:stateRoot 'application.jar')).Hash.ToLowerInvariant() -eq $binding.jarSha256) 'Packaged application differs from its build receipt. Run Build.'
    foreach($name in @($script:pg,$script:redis)){Assert-Nkw (Test-NkwContainer $name) 'Isolated dependencies are missing.'}
    Invoke-NkwMigration $Settings
    Assert-NkwFreePort 19082
    Assert-NkwListenerOwnership 'synthetic'
    & (Join-Path $script:repo '.github/scripts/localhost.ps1') -Mode Stop
    Assert-Nkw ($LASTEXITCODE -eq 0) 'Original synthetic instance did not stop; it was not force-killed.'
    Assert-NkwFreePort 9443
    $stopName='stop-'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16)).ToLowerInvariant()
    Write-NkwPrivate 'application.properties' (Get-NkwConfiguration $Settings $stopName)
    $start=New-NkwStartInfo $Settings $secret $stopName;$secret=$null
    $process=[Diagnostics.Process]::Start($start);$start.Environment.Clear()
    $tag=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $out=[IO.File]::Open((Join-Path $script:stateRoot "logs/app-$tag.log"),'CreateNew','Write','Read')
    $err=[IO.File]::Open((Join-Path $script:stateRoot "logs/app-$tag.error"),'CreateNew','Write','Read')
    $outTask=$process.StandardOutput.BaseStream.CopyToAsync($out);$errTask=$process.StandardError.BaseStream.CopyToAsync($err)
    $registeredAt=$process.StartTime.ToUniversalTime().ToString('o')
    Write-NkwPrivate 'process.json' (@{processId=$process.Id;startedAt=$registeredAt;executable=$Settings.java;stopName=$stopName}|ConvertTo-Json)
    } finally {$guard.Dispose()}
    try {
        $ready=$false
        for($i=0;$i -lt 180 -and !$process.HasExited;$i++) {
            try{if((Invoke-WebRequest 'http://127.0.0.1:19082/actuator/health/readiness' -TimeoutSec 2).StatusCode -eq 200){$ready=$true;break}}catch{}
            Start-Sleep -Milliseconds 500
        }
        Assert-Nkw $ready 'Isolated application did not become ready. Use Status; preserve private logs. RestoreSynthetic remains available.'
        Write-Output 'Ready: https://localhost:9443/login — real Google sign-in; isolated data; AI/SMTP off. Use Stop in another shell.'
        $process.WaitForExit()
        $outTask.GetAwaiter().GetResult()|Out-Null;$errTask.GetAwaiter().GetResult()|Out-Null
    } finally {
        if(!$process.HasExited){Stop-NkwApplication}
        Remove-NkwProcessRecord $process.Id $registeredAt
        $out.Dispose();$err.Dispose()
    }
}
function Build-NkwApplication($Settings,[string]$RequestedNode) {
    Assert-Nkw (!(Get-NkwProcess)) 'Stop the Google instance before building.'
    $nodeHome=if($RequestedNode){$RequestedNode}else{Join-Path $script:repo '.runtime/frontend-package-1/node-v24.21.0-win-x64'}
    $node=Join-Path $nodeHome 'node.exe';$npm=Join-Path $nodeHome 'node_modules/npm/bin/npm-cli.js'
    Assert-Nkw ((Test-Path -LiteralPath $node) -and (& $node --version) -eq 'v24.21.0' -and (& $node $npm --version) -eq '11.19.0') 'Approved Node 24.21.0/npm 11.19.0 required; supply -NodeHome.'
    $snapshot=Join-Path $script:stateRoot ('build-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $snapshot|Out-Null
    $names=@(git -C $script:repo ls-files;git -C $script:repo ls-files --others --exclude-standard)|Sort-Object -Unique
    $allowed=@('.github','.mvn','src','frontend','.editorconfig','.gitattributes','.gitignore','.gitleaks.toml','.node-version','pom.xml','mvnw','mvnw.cmd','README.md','LICENSE')
    $hashes=@()
    foreach($name in $names){
        Assert-Nkw (($name -split '/')[0] -in $allowed) 'Unexpected public-source path; build snapshot refused.'
        $source=Join-Path $script:repo $name;Assert-Nkw (!((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked source file refused.'
        $target=Join-Path $snapshot $name;New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($target))|Out-Null
        Copy-Item -LiteralPath $source -Destination $target;$hashes+=@{path=$name;sha256=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant()}
    }
    $run=[Diagnostics.ProcessStartInfo]::new($node);$run.WorkingDirectory=$snapshot;$run.UseShellExecute=$false;$run.CreateNoWindow=$true
    foreach($key in @($run.Environment.Keys)){if($key -match '^(SPRING_|GOOGLE_|GEMINI_|GROQ_|AWS_|OCI_|IDENTITY_|KNOWLEDGE_|NOTES_|PROFILE_|PUBLISHING_|MANAGEMENT_|SERVER_|NKW_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)'){$run.Environment.Remove($key)|Out-Null}}
    $run.Environment['JAVA_HOME']=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($Settings.java))
    $run.Environment['PATH']=$nodeHome+';'+$run.Environment['JAVA_HOME']+'\bin;'+$run.Environment['PATH']
    $run.Environment['TEMP']=Join-Path $script:stateRoot 'temp';$run.Environment['TMP']=$run.Environment['TEMP']
    $run.Environment['NPM_CONFIG_CACHE']=Join-Path $script:repo '.runtime/tool-cache/npm'
    $run.Environment['MAVEN_USER_HOME']=Join-Path $script:repo '.runtime/tool-cache/maven-home'
    $run.Environment['MAVEN_OPTS']='-Xmx512m "-Dmaven.repo.local='+(Join-Path $script:repo '.runtime/integration-package-3/maven-repository')+'"'
    foreach($arguments in @(@($npm,'ci','--ignore-scripts','--prefix','frontend'),@($npm,'run','build','--prefix','frontend'))){
        $run.ArgumentList.Clear();foreach($arg in $arguments){$run.ArgumentList.Add($arg)};$p=[Diagnostics.Process]::Start($run);$p.WaitForExit();Assert-Nkw ($p.ExitCode -eq 0) 'Isolated frontend build failed.'
    }
    $run.FileName='cmd.exe';$run.ArgumentList.Clear()
    foreach($arg in @('/d','/s','/c','.\mvnw.cmd --batch-mode --no-transfer-progress -Pwith-frontend -DskipTests package -q')){$run.ArgumentList.Add($arg)}
    $p=[Diagnostics.Process]::Start($run);$p.WaitForExit();Assert-Nkw ($p.ExitCode -eq 0) 'Isolated backend package failed.'
    foreach($entry in $hashes){Assert-Nkw ((Get-FileHash -LiteralPath (Join-Path $script:repo $entry.path)).Hash.ToLowerInvariant() -eq $entry.sha256) 'Source changed during the build; keep the prior artifact.'}
    Copy-Item -LiteralPath (Join-Path $snapshot 'target/notes-knowledge-workspace-0.0.1-SNAPSHOT.jar') -Destination (Join-Path $script:stateRoot 'application.jar')
    Write-NkwPrivate 'build.json' (@{head=(git -C $script:repo rev-parse HEAD);sourceFiles=$hashes;jarSha256=(Get-FileHash -LiteralPath (Join-Path $script:stateRoot 'application.jar')).Hash.ToLowerInvariant();testsSkippedForPackaging=$true}|ConvertTo-Json -Depth 5)
    Write-Output 'Isolated production assets and backend packaged. Packaging skipped tests; qualification receipts are separate. Existing synthetic JAR was not replaced.'
}
function Prepare-NkwInstance([string]$Id,[string]$RequestedJava) {
    if(Test-Path -LiteralPath $script:stateRoot){$null=Read-NkwSettings;Write-NkwBootstrap;Write-Output 'Existing isolated state retained; no keys, accounts, database or certificate replaced.';return}
    Assert-Nkw ($Id -cmatch '^[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$') 'Supply the Google Web client ID with Prepare.'
    $java=Find-NkwJava $RequestedJava
    foreach($name in @($script:pg,$script:redis)){Assert-Nkw (!(Test-NkwContainer $name)) 'Existing isolated container requires its original keys; Prepare refused.'}
    Assert-Nkw (@(Invoke-NkwDocker @('volume','ls','--format','{{.Name}}')) -notcontains $script:volume) 'Existing isolated volume must not be paired with replacement keys.'
    Assert-Nkw (@(Invoke-NkwDocker @('network','ls','--format','{{.Name}}')) -notcontains $script:network) 'Existing isolated network requires review.'
    foreach($port in @(55448,56395,19082)){Assert-NkwFreePort $port}
    foreach($image in @('pgvector/pgvector:0.8.6-pg18-trixie','redis:8.10.2')){Invoke-NkwDocker @('image','inspect','--format','{{.Id}}',$image)|Out-Null}
    New-Item -ItemType Directory -Path $script:stateRoot|Out-Null;Protect-NkwDirectory $script:stateRoot
    Write-NkwPrivate 'OIDC_DEV_INSTANCE' "nkw-google-oidc-dev-1`n"
    foreach($name in @('outbox','private-attachment','private-avatar','public-profile-avatar','public-publication-media','temp','logs')){New-Item -ItemType Directory -Path (Join-Path $script:stateRoot $name)|Out-Null}
    $keys=[ordered]@{}
    foreach($key in @('identity.delivery.key-base64','identity.mfa.key-base64','identity.mfa.handle-key-base64','identity.session-handle.key-base64',
        'identity.rate.key-base64','notes.cursor.active.key-base64','publishing.preview.key-base64','knowledge.operations.handle.key-base64','knowledge.operations.envelope.key-base64')){$keys[$key]=New-NkwKey}
    $settings=[pscustomobject]@{format='nkw-google-oidc-dev-1';clientId=$Id;java=$java;migratorPassword=(New-NkwKey);runtimePassword=(New-NkwKey);pfxPassword=(New-NkwKey);keys=[pscustomobject]$keys;certificateThumbprint=''}
    Write-NkwSettings $settings
    Write-NkwBootstrap
    $cert=New-SelfSignedCertificate -Subject 'CN=NKW isolated Google localhost' -DnsName localhost -CertStoreLocation Cert:\CurrentUser\My -KeyAlgorithm RSA -KeyLength 2048 -KeyExportPolicy Exportable -NotAfter (Get-Date).AddDays(90)
    $settings.certificateThumbprint=$cert.Thumbprint;Write-NkwSettings $settings
    Export-Certificate -Cert $cert -FilePath (Join-Path $script:stateRoot 'localhost.cer')|Out-Null
    Export-PfxCertificate -Cert $cert -FilePath (Join-Path $script:stateRoot 'localhost.p12') -Password (ConvertTo-SecureString $settings.pfxPassword -AsPlainText -Force)|Out-Null
    Import-Certificate -FilePath (Join-Path $script:stateRoot 'localhost.cer') -CertStoreLocation Cert:\CurrentUser\Root|Out-Null
    Write-NkwPrivate 'postgres-password' $settings.migratorPassword
    Invoke-NkwDocker @('network','create','--label',"nkw.oidc-dev=$script:owner",$script:network)|Out-Null
    Invoke-NkwDocker @('volume','create','--label',"nkw.oidc-dev=$script:owner",$script:volume)|Out-Null
    Invoke-NkwDocker @('run','-d','--pull=never','--name',$script:pg,'--label',"nkw.oidc-dev=$script:owner",'--network',$script:network,
        '--memory','768m','--cpus','1','--pids-limit','128','-p','127.0.0.1:55448:5432','--mount',"type=volume,source=$script:volume,target=/var/lib/postgresql",
        '--mount',"type=bind,source=$script:stateRoot/postgres-password,target=/run/secrets/postgres-password,readonly",
        '-e','POSTGRES_USER=nkw_oidc_migrator','-e','POSTGRES_DB=nkw_oidc_dev','-e','POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password','pgvector/pgvector:0.8.6-pg18-trixie')|Out-Null
    Invoke-NkwDocker @('run','-d','--pull=never','--name',$script:redis,'--label',"nkw.oidc-dev=$script:owner",'--network',$script:network,
        '--memory','128m','--cpus','1','--pids-limit','64','-p','127.0.0.1:56395:6379','redis:8.10.2','redis-server','--appendonly','no','--save','')|Out-Null
    Write-Output 'Independent Google-OIDC database, keys, files and trusted localhost TLS prepared. Existing synthetic state retained. Build, then enter the Client Secret with SetSecret.'
}

# Tests dot-source functions against disposable state; ordinary entry always checks
# Windows, Git exclusion and the owned marker before any runtime operation.
if($MyInvocation.InvocationName -eq '.'){return}
try {
    Assert-Nkw $IsWindows 'This owner launcher requires Windows and Current User DPAPI.'
    Set-Location $script:repo
    & git check-ignore -q -- '.runtime/google-oidc-dev/settings.protected'
    Assert-Nkw ($LASTEXITCODE -eq 0) 'Private runtime state must be excluded from Git.'
    if($Mode -eq 'Prepare'){$script:operation='prepare';Prepare-NkwInstance $ClientId $JavaHome;exit 0}
    $settings=Read-NkwSettings;$script:operation=$Mode.ToLowerInvariant()
    switch($Mode) {
        'SetSecret' {
            $guard=Enter-NkwOperation
            try {
            Assert-Nkw (!(Get-NkwProcess)) 'Stop the Google instance before replacing its local Client Secret.'
            $secret=Read-Host 'Google Web Client Secret (hidden; never paste it in chat)' -AsSecureString
            try{Save-NkwClientSecret $secret}finally{$secret.Dispose()}
            Write-Output 'Client Secret saved with Windows Current User DPAPI. It was not displayed or added to environment settings.'
            } finally {$guard.Dispose()}
        }
        'Build' {$guard=Enter-NkwOperation;try{Build-NkwApplication $settings $NodeHome;Invoke-NkwMigration $settings;foreach($name in @($script:redis,$script:pg)){Invoke-NkwDocker @('stop','--time','30',$name)|Out-Null}}finally{$guard.Dispose()}}
        'Start' {Start-NkwApplication $settings}
        'Stop' {$guard=Enter-NkwOperation;try{Assert-NkwListenerOwnership 'google';Stop-NkwApplication;foreach($name in @($script:redis,$script:pg)){if(Test-NkwContainer $name){Invoke-NkwDocker @('stop','--time','30',$name)|Out-Null}};Write-Output 'Google instance stopped. Its data/keys and the original synthetic data were retained.'}finally{$guard.Dispose()}}
        'RestoreSynthetic' {
            $guard=Enter-NkwOperation
            try {
            Assert-NkwListenerOwnership 'google';Stop-NkwApplication
            foreach($name in @($script:redis,$script:pg)){if(Test-NkwContainer $name){Invoke-NkwDocker @('stop','--time','30',$name)|Out-Null}}
            Assert-NkwFreePort 9443
            } finally {$guard.Dispose()}
            & (Join-Path $script:repo '.github/scripts/localhost.ps1') -Mode Start -JavaHome ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($settings.java)))
        }
        'Outbox' {Assert-NkwPrivate (Join-Path $script:stateRoot 'outbox');Invoke-Item -LiteralPath (Join-Path $script:stateRoot 'outbox');Write-Output 'Private local outbox opened. These messages contain one-time links; never share or archive them. No SMTP was used.'}
        'Status' {
            $running=$null -ne (Get-NkwProcess);$ready=$false
            if($running){try{$ready=(Invoke-WebRequest 'http://127.0.0.1:19082/actuator/health/readiness' -TimeoutSec 3).StatusCode -eq 200}catch{}}
            [pscustomobject]@{instance='isolated-google-oidc';running=$running;ready=$ready;secretStored=(Test-Path -LiteralPath (Join-Path $script:stateRoot 'client-secret.protected'));artifactPrepared=(Test-Path -LiteralPath (Join-Path $script:stateRoot 'application.jar'));liveAi=$false;smtp=$false;origin=$script:origin}
        }
    }
} catch {
    if($_.Exception.Message.StartsWith('NKW OIDC:')){[Console]::Error.WriteLine($_.Exception.Message)}
    else{[Console]::Error.WriteLine("NKW OIDC: $script:operation failed safely. Preserve private state; use Status. No raw diagnostics or credentials are printed.")}
    exit 1
}
