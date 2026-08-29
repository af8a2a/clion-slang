param(
    [Parameter(Mandatory = $true)]
    [string] $ClionHome,

    [string] $IdeSdkHome = "",

    [string[]] $Tasks = @("test", "buildPlugin")
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$resolvedClion = (Resolve-Path -LiteralPath $ClionHome).Path
$javaExecutable = Join-Path $resolvedClion "jbr\bin\java.exe"
$wrapperJar = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.jar"

if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "CLion's JetBrains Runtime was not found at: $javaExecutable"
}
if (-not (Test-Path -LiteralPath $wrapperJar -PathType Leaf)) {
    throw "Gradle Wrapper JAR was not found at: $wrapperJar"
}

$gradleArguments = @(
    "-Dorg.gradle.appname=gradlew",
    "-classpath",
    $wrapperJar,
    "org.gradle.wrapper.GradleWrapperMain"
)

# Reusing an IDE's JBR and choosing an IntelliJ Platform compile SDK are
# separate decisions. Keeping the latter opt-in preserves the 2026.1.3
# baseline declared in gradle.properties for ordinary/release builds.
if (-not [string]::IsNullOrWhiteSpace($IdeSdkHome)) {
    $resolvedIdeSdk = (Resolve-Path -LiteralPath $IdeSdkHome).Path
    $gradleArguments += "-PlocalIdePath=$resolvedIdeSdk"
}
$gradleArguments += $Tasks

Push-Location $projectRoot
try {
    & $javaExecutable @gradleArguments
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
