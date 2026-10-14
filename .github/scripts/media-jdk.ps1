$ErrorActionPreference = 'Stop'
$archive = Join-Path $env:RUNNER_TEMP 'microsoft-jdk.zip'
$destination = Join-Path $env:RUNNER_TEMP 'media-jdk'
if (Test-Path -LiteralPath $destination) { throw 'Qualification toolchain directory already exists' }
Invoke-WebRequest -Uri 'https://aka.ms/download-jdk/microsoft-jdk-25.0.4.1-windows-x64.zip' -OutFile $archive
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne '3c9099e60a82e17f5847052f50f3b107bfc1d32b366e1a20adbd1d0f303760e7') {
    throw 'Qualification JDK integrity failed'
}
Expand-Archive -LiteralPath $archive -DestinationPath $destination
$directories = @(Get-ChildItem -LiteralPath $destination -Directory)
if ($directories.Count -ne 1) { throw 'Unexpected qualification JDK layout' }
$jdk = $directories[0].FullName
"JAVA_HOME=$jdk" | Out-File -LiteralPath $env:GITHUB_ENV -Append -Encoding utf8
"$jdk\bin" | Out-File -LiteralPath $env:GITHUB_PATH -Append -Encoding utf8
