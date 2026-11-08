#requires -Version 7.4
$ErrorActionPreference='Stop'
$launcher=Join-Path $PSScriptRoot 'google-oidc-dev.ps1'
$parseErrors=$null;$null=[Management.Automation.Language.Parser]::ParseFile($launcher,[ref]$null,[ref]$parseErrors)
if($parseErrors.Count){throw 'Launcher parsing failed'}
. $launcher
if(!$IsWindows){Write-Output 'Windows DPAPI/ACL launcher tests require Windows; no live provider is used.';exit 0}
$realRepo=$script:repo
$testRoot=Join-Path $realRepo ('.runtime/oidc-launcher-tests-'+[guid]::NewGuid().ToString('N'))
$script:stateRoot=Join-Path $testRoot '.runtime/google-oidc-dev'
New-Item -ItemType Directory -Path $script:stateRoot -Force|Out-Null
$script:assertions=0
function Check([bool]$Value,[string]$Label){if(!$Value){throw "Launcher regression failed: $Label"};$script:assertions++}
function Rejected([scriptblock]$Action,[string]$Category){$failed=$false;try{& $Action|Out-Null}catch{$failed=$_.Exception.Message.StartsWith('NKW OIDC:')};Check $failed $Category}
try {
    Protect-NkwDirectory $script:stateRoot
    Write-NkwPrivate 'OIDC_DEV_INSTANCE' "nkw-google-oidc-dev-1`n"
    New-Item -ItemType Directory -Path (Join-Path $script:stateRoot 'temp')|Out-Null
    $keys=[pscustomobject][ordered]@{'identity.delivery.key-base64'=(New-NkwKey);'identity.mfa.key-base64'=(New-NkwKey)}
    $s=[pscustomobject]@{format='nkw-google-oidc-dev-1';clientId='synthetic-client.apps.googleusercontent.com';java=(Join-Path $PSHOME 'pwsh.exe');migratorPassword=(New-NkwKey);runtimePassword=(New-NkwKey);pfxPassword=(New-NkwKey);keys=$keys;certificateThumbprint=''}
    Write-NkwSettings $s;$loaded=Read-NkwSettings
    Check ($loaded.runtimePassword -ceq $s.runtimePassword) 'DPAPI settings round trip'
    Check (![IO.File]::ReadAllText((Join-Path $script:stateRoot 'settings.protected')).Contains($s.runtimePassword)) 'No plaintext database password in protected settings'
    $canary='synthetic-client-secret-not-a-real-credential'
    $secret=ConvertTo-SecureString $canary -AsPlainText -Force
    try{Save-NkwClientSecret $secret}finally{$secret.Dispose()}
    Check ((Read-NkwClientSecret) -ceq $canary) 'DPAPI secret round trip'
    Check (![IO.File]::ReadAllText((Join-Path $script:stateRoot 'client-secret.protected')).Contains($canary)) 'No plaintext OAuth secret persisted'
    $stop='stop-'+('a'*32)
    $config=Get-NkwConfiguration $loaded $stop
    Check (!$config.Contains($canary) -and !$config.Contains($loaded.runtimePassword) -and !$config.Contains($loaded.pfxPassword)) 'Configuration contains environment references only'
    Check ($config.Contains('spring.profiles.active=operations,google-oidc-dev') -and !$config.Contains('active=operations,localhost')) 'Separate profile'
    Check ($config.Contains('127.0.0.1:55448/nkw_oidc_dev') -and !$config.Contains('55438/nkw_owner_local')) 'Separate PostgreSQL'
    Check ($config.Contains('spring.data.redis.port=56395')) 'Separate Redis'
    $launcherText=[IO.File]::ReadAllText($launcher)
    Check ($launcherText.Contains("'127.0.0.1:56395:6379'") -and !$launcherText.Contains("'127.0.0.1:56385:6379'")) 'Redis provisioning matches the guarded application port'
    Check ($config.Contains('management.health.redis.enabled=true') -and $config.Contains('readinessState,db,coreSchema,redis')) 'Required auth Redis is included in startup readiness'
    Check ($config.Contains('identity.delivery.smtp-enabled=false') -and $config.Contains('knowledge.derivation.enabled=false')) 'AI and SMTP disabled'
    $env:GEMINI_CANARY_TEST='synthetic-environment-canary';$env:SPRING_CANARY_TEST='synthetic-environment-canary'
    $start=New-NkwStartInfo $loaded $canary $stop
    Check (!$start.Environment.ContainsKey('GEMINI_CANARY_TEST') -and !$start.Environment.ContainsKey('SPRING_CANARY_TEST')) 'Ambient provider/application settings stripped'
    Check ($start.Environment['GOOGLE_OIDC_ENABLED'] -eq 'true') 'Only explicit Google enabled'
    Check ($start.Environment['GOOGLE_OIDC_CLIENT_SECRET'] -ceq $canary) 'OAuth secret passed to child memory only'
    Check ($start.Environment['GOOGLE_OIDC_LOGIN_REDIRECT_URI'] -ceq 'https://localhost:9443/api/auth/oidc/google/callback') 'Exact login callback'
    Check ($start.Environment['GOOGLE_OIDC_LINK_REDIRECT_URI'] -ceq 'https://localhost:9443/api/auth/oidc/google/link-callback') 'Exact link callback'
    Check (!($start.ArgumentList -join ' ').Contains($canary) -and !($start.ArgumentList -join ' ').Contains($loaded.runtimePassword)) 'No credentials in process arguments'
    Check ($start.CreateNoWindow -and !$start.UseShellExecute) 'No shell interpolation or visible background process'
    $start.Environment.Clear()
    Remove-Item -LiteralPath (Join-Path $script:stateRoot 'client-secret.protected')
    Rejected {Start-NkwApplication $loaded} 'Missing secret rejected before certificate, Docker or synthetic stop'
    Write-NkwPrivate 'process.json' (@{processId=$PID;startedAt=[DateTimeOffset]::UtcNow.AddYears(-1).ToString('o');executable=(Get-Process -Id $PID).Path;stopName=$stop}|ConvertTo-Json)
    Rejected {Get-NkwProcess} 'Reused PID rejected without stopping process'
    $record=[IO.File]::ReadAllText((Join-Path $script:stateRoot 'process.json'))|ConvertFrom-Json
    Remove-NkwProcessRecord ($PID+1) $record.startedAt
    Check (Test-Path -LiteralPath (Join-Path $script:stateRoot 'process.json')) 'Cleanup cannot remove a different owner record'
    Remove-NkwProcessRecord $PID $record.startedAt
    Check (!(Test-Path -LiteralPath (Join-Path $script:stateRoot 'process.json'))) 'Only the matching exited-process record is removed'
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$listener.Start()
    try{Rejected {Assert-NkwFreePort $listener.LocalEndpoint.Port} 'Occupied port refused'}finally{$listener.Stop()}
    $outside=Join-Path $testRoot 'outside';New-Item -ItemType Directory -Path $outside|Out-Null
    Rejected {Write-NkwPrivate '../outside.txt' 'synthetic'} 'Path traversal refused'
    $link=Join-Path $script:stateRoot 'linked';New-Item -ItemType Junction -Path $link -Target $outside|Out-Null
    try {Rejected {Assert-NkwPath (Join-Path $link 'state')} 'Linked private path refused'} finally {Remove-Item -LiteralPath $link -Force}
    & icacls.exe (Join-Path $script:stateRoot 'settings.protected') /grant '*S-1-1-0:(R)' *> $null
    Check ($LASTEXITCODE -eq 0) 'Disposable file ACL setup'
    Rejected {Read-NkwSettings} 'Broad state ACL rejected before decryption'
    Rejected {Write-NkwSettings $s} 'Broad existing file ACL rejected before writing private data'
    Rejected {Assert-NkwTls $loaded} 'Missing certificate refused'
    $guard=Enter-NkwOperation
    try {Rejected {Enter-NkwOperation} 'Concurrent startup or setup refused'}finally{$guard.Dispose()}
    $now=[DateTimeOffset]::Parse('2026-11-08T00:00:00Z')
    Check ((Get-NkwProviderClockSkew $now 0 $now) -eq 0) 'Fresh provider time'
    Check ((Get-NkwProviderClockSkew ($now.AddSeconds(-380)) 380 $now) -eq 0) 'HTTP cache Age cannot produce false clock failure'
    Check ((Get-NkwProviderClockSkew $now 0 ($now.AddSeconds(-54))) -eq 54) 'Existing OIDC clock window retained'
    Rejected {Get-NkwProviderClockSkew $now 0 ($now.AddSeconds(-61))} 'Unsafe clock skew rejected'
    Rejected {Get-NkwProviderClockSkew $now -1 $now} 'Invalid HTTP age rejected'
    Write-Output ("Launcher contract: $script:assertions assertions passed; no provider calls, real credentials or persistent application data used.")
} finally {
    Remove-Item Env:GEMINI_CANARY_TEST,Env:SPRING_CANARY_TEST -ErrorAction SilentlyContinue
    $resolved=[IO.Path]::GetFullPath($testRoot)
    if($resolved.StartsWith((Join-Path $realRepo '.runtime')+[IO.Path]::DirectorySeparatorChar) -and [IO.Path]::GetFileName($resolved) -cmatch '^oidc-launcher-tests-[a-f0-9]{32}$'){
        if(!(Get-ChildItem -LiteralPath $resolved -Recurse -Force|Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint})){Remove-Item -LiteralPath $resolved -Recurse -Force}
    }
}
