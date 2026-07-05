<#
.SYNOPSIS
    Build the debug APK and publish it as a GitHub Release on the public
    releases repo, where the in-app updater looks for new versions.

.DESCRIPTION
    Steps:
      1. Reads versionName from app/build.gradle.kts.
      2. Builds the debug APK (drops apks/SmartDongV2-v<version>-debug.apk).
      3. Creates (first run) or reuses the public releases repo.
      4. Cuts a GitHub Release tagged v<version> with the APK attached.

    The in-app updater (com.handyvoice.v2.update.Updater) polls this repo's
    /releases/latest. Bump versionName + versionCode in app/build.gradle.kts
    before running so the new release is detected as newer.

    Signing note: if app/signing/handy.keystore exists, every build (any
    machine) signs with that one committed key, so in-app updates always
    install over the top. If it's absent the APK is debug-signed with this
    machine's ~/.android/debug.keystore, and Android only applies an update
    signed with the SAME key as the installed build (publish from one machine).
    See app/signing/README.md.

.PARAMETER Notes
    Release notes shown in the in-app "Update available" dialog. Defaults to a
    generic line if omitted.

.EXAMPLE
    .\publish-release.ps1 -Notes "Mic turns red while listening; depth fix."
#>
param(
    [string]$Notes = ""
)

$ErrorActionPreference = "Stop"

# Public repo that holds the APKs. Must match Updater.RELEASES_REPO.
$Repo = "owenpkent/handy-releases"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleFile = Join-Path $scriptDir "app\build.gradle.kts"
$repoRoot = Split-Path -Parent $scriptDir   # ...\handy

# --- 1. Read versionName -------------------------------------------------
$gradleText = Get-Content $gradleFile -Raw
$m = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
if (-not $m.Success) { throw "Could not find versionName in $gradleFile" }
$version = $m.Groups[1].Value
$tag = "v$version"
Write-Host "Version: $version (tag $tag)" -ForegroundColor Cyan

# --- 2. Build the APK ----------------------------------------------------
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
}
Write-Host "Building debug APK..." -ForegroundColor Cyan
# -p sets the project dir so this works regardless of the caller's CWD.
& (Join-Path $scriptDir "gradlew.bat") -p $scriptDir ":app:assembleDebug"
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

$apk = Join-Path $repoRoot "apks\SmartDongV2-v$version-debug.apk"
if (-not (Test-Path $apk)) { throw "Expected APK not found: $apk" }
Write-Host "APK: $apk" -ForegroundColor Green

# --- 3. Ensure the public releases repo exists ---------------------------
$repoExists = $true
try { gh repo view $Repo *> $null } catch { $repoExists = $false }
if (-not $repoExists) {
    Write-Host "Creating public repo $Repo ..." -ForegroundColor Yellow
    # --add-readme gives the repo an initial commit, so a release tag has
    # something to point at (releases can't be created on an empty repo).
    gh repo create $Repo --public --add-readme --description "Built APKs for the Handy Voice v2 Android app (source is private)."
    if ($LASTEXITCODE -ne 0) { throw "Failed to create repo $Repo" }
}

# --- 4. Cut the release --------------------------------------------------
if ([string]::IsNullOrWhiteSpace($Notes)) {
    $Notes = "Smart Dong v2 $version"
}

# If the tag already exists, replace its asset; else create the release.
$releaseExists = $true
try { gh release view $tag --repo $Repo *> $null } catch { $releaseExists = $false }

if ($releaseExists) {
    Write-Host "Release $tag exists; updating asset..." -ForegroundColor Yellow
    gh release upload $tag $apk --repo $Repo --clobber
} else {
    Write-Host "Creating release $tag ..." -ForegroundColor Cyan
    gh release create $tag $apk --repo $Repo --title $tag --notes $Notes
}
if ($LASTEXITCODE -ne 0) { throw "Failed to publish release." }

Write-Host "Published $tag to $Repo." -ForegroundColor Green
Write-Host "Installed apps on v$version will see this as the latest." -ForegroundColor Green
