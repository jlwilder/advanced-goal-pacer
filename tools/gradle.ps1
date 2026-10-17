param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkPathFile = Join-Path $projectRoot '.local\java-home.txt'
$javaHomeForProject = $env:JAVA_HOME
if (-not $javaHomeForProject -and (Test-Path -LiteralPath $jdkPathFile)) {
    $javaHomeForProject = (Get-Content -LiteralPath $jdkPathFile -Raw).Trim()
}
if ($javaHomeForProject) {
    $env:JAVA_HOME = $javaHomeForProject
    $env:PATH = (Join-Path $javaHomeForProject 'bin') + [IO.Path]::PathSeparator + $env:PATH
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Set JAVA_HOME to a JDK, or place its path in .local/java-home.txt.'
}
Push-Location -LiteralPath $projectRoot
try {
    # Separate checkouts get separate build/cache directories, outside synced folders.
    $pathHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $rootKey = ([BitConverter]::ToString($pathHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectRoot.ToLowerInvariant())))).Replace('-', '').Substring(0, 12).ToLowerInvariant()
    } finally {
        $pathHasher.Dispose()
    }
    $generatedRoot = Join-Path $env:LOCALAPPDATA "MyGoals\development\$rootKey"
    $generatedBuild = Join-Path $generatedRoot 'build'
    $projectCache = Join-Path $generatedRoot 'gradle-project'
    $developerHome = Join-Path $generatedRoot 'dev-home'
    Write-Output "Generated files: $generatedBuild"
    & (Join-Path $projectRoot 'gradlew.bat') "-PmyGoalsBuildDir=$generatedBuild" "-PmyGoalsDevHome=$developerHome" '--project-cache-dir' $projectCache @GradleArguments
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $gradleExitCode
