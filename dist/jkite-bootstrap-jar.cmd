@echo off
rem ===========================================================================
rem Installs jkite.jar, the jkite a project asked for: the version, URL
rem and SHA-256 in the jkite.properties next to this script, downloaded
rem into %JKITE_CACHE_DIR%\jkite\<version>
rem (%userprofile%\.jkite\cache\jkite\<version>). It prints the jar's path
rem on stdout and says nothing else there; progress and errors go to stderr.
rem When the jar is already installed it only prints.
rem
rem The cache is per machine, not per project, so several projects that pin the
rem same version share one download. The launcher (jkite.cmd) runs this
rem when there is no jkite.jar next to it; it can just as well be run by
rem hand, or replaced by anything else that puts the jar there. It is
rem self-contained: no PowerShell, only what Windows ships with (curl,
rem certutil).
rem
rem   jkite-bootstrap-jar.cmd        install if needed, print the jar path
rem
rem Environment:
rem   JKITE_DIR, JKITE_CACHE_DIR       where jkite keeps things (~\.jkite)
rem   JKITE_DIST_URL               where to fetch the jar from, overriding
rem                                    distributionUrl (a corporate mirror)
rem   JKITE_DOWNLOAD_RETRY, JKITE_DOWNLOAD_RETRY_DELAY
rem   JKITE_LOCK_TIMEOUT           seconds to wait for another run's download
rem
rem Several jkite runs can be started at the same time (a build matrix, a
rem multi-module build). They share ~\.jkite, so the download takes a directory
rem lock (mkdir is atomic: :acquire_lock / :release_lock) and the jar is
rem written to a file of this run's own that is renamed into place.
rem
rem Two CMD rules shape the code below and are easy to trip over:
rem   - a variable set inside a parenthesized block cannot be read in that same
rem     block, so anything that reads what it just computed uses GOTO, not IF
rem     blocks;
rem   - CALL expands %% a second time, so URLs are handed to subroutines in
rem     variables, never as arguments.
rem ===========================================================================
setlocal

call :init_settings
call :read_properties || exit /b 1

set "jar_dir=%cache_dir%\jkite\%distribution_version%"
set "jar_path=%jar_dir%\jkite.jar"
call :cached_jar_is_ours
if errorlevel 1 (
  call :install_jar || exit /b 1
  rem install_jar returns as soon as another run has put a jar there, so what
  rem ends up being used is checked here rather than only where it is downloaded
  call :cached_jar_is_ours
  if errorlevel 1 (
    echo %jar_path% is not the jar %properties_file% pins, refusing to run it 1>&2
    exit /b 1
  )
)
echo %jar_path%
exit /b 0

rem ===========================================================================
rem Settings
rem ===========================================================================

:init_settings
rem How often a failed download is retried, and how long to wait in between
rem (0 means an exponential backoff of 1, 2, 4, ... seconds)
set "download_retry=5"
if not "%JKITE_DOWNLOAD_RETRY%"=="" set "download_retry=%JKITE_DOWNLOAD_RETRY%"
set "download_retry_delay=0"
if not "%JKITE_DOWNLOAD_RETRY_DELAY%"=="" set "download_retry_delay=%JKITE_DOWNLOAD_RETRY_DELAY%"

rem The directories jkite keeps its JDKs, jars and caches in.
set "jkite_dir=%userprofile%\.jkite"
if not "%JKITE_DIR%"=="" set "jkite_dir=%JKITE_DIR%"
set "cache_dir=%jkite_dir%\cache"
if not "%JKITE_CACHE_DIR%"=="" set "cache_dir=%JKITE_CACHE_DIR%"

rem %~dp0 in a subroutine is the label, not this file, so remember where we are
set "script_dir=%~dp0"

rem Tells this run's temporary files apart from those of a jkite running at
rem the same time
set "run_id=%RANDOM%%RANDOM%"

rem How long to wait (in seconds) for another run that is downloading the jar
set "lock_timeout=600"
if not "%JKITE_LOCK_TIMEOUT%"=="" set "lock_timeout=%JKITE_LOCK_TIMEOUT%"
exit /b 0

rem ===========================================================================
rem The properties
rem ===========================================================================

rem Reads jkite.properties next to this script into distribution_version,
rem distribution_url and distribution_sha256.
rem
rem The values are never handed to CALL: CALL expands %% a second time, and a
rem URL that contains one (a Temurin one contains %%2B) would turn into CALL's
rem second argument. A FOR variable is safe, so the whole file is read in one
rem loop here. The file is written by misc/update-dist.sh and is not meant to
rem be edited by hand, so no whitespace around the values is trimmed.
:read_properties
set "properties_file=%script_dir%jkite.properties"
if not exist "%properties_file%" (
  echo %properties_file% not found. Re-run %script_dir%install.cmd to restore it. 1>&2
  exit /b 1
)
setlocal enabledelayedexpansion
set "found_version="
set "found_url="
set "found_sha="
for /f "usebackq eol=# tokens=1,* delims==" %%K in ("%properties_file%") do (
  if /i "%%K"=="distributionVersion" set "found_version=%%L"
  if /i "%%K"=="distributionUrl" set "found_url=%%L"
  if /i "%%K"=="distributionSha256Sum" set "found_sha=%%L"
)
endlocal & set "distribution_version=%found_version%" & set "distribution_url=%found_url%" & set "distribution_sha256=%found_sha%"
if not "%JKITE_DIST_URL%"=="" set "distribution_url=%JKITE_DIST_URL%"
if "%distribution_version%"=="" goto :properties_incomplete
if "%distribution_url%"=="" goto :properties_incomplete
rem Only https, so that neither a tampered properties file nor
rem JKITE_DIST_URL can point the download at a plaintext host. A loopback
rem address is allowed so that the tests can serve the jar locally.
if "%distribution_url:~0,8%"=="https://" exit /b 0
if "%distribution_url:~0,17%"=="http://127.0.0.1:" exit /b 0
if "%distribution_url:~0,17%"=="http://localhost:" exit /b 0
echo Refusing to download the jar over anything but https: %distribution_url% 1>&2
exit /b 1

:properties_incomplete
echo %properties_file% needs a distributionVersion and a distributionUrl 1>&2
exit /b 1

rem ===========================================================================
rem Installing
rem ===========================================================================

rem Downloads the jar into %jar_dir%, one run at a time; the others wait and
rem then use what it installed.
:install_jar
set "lock_dir=%jar_dir%.lock"
set "lock_done=%jar_path%"
call :acquire_lock
if errorlevel 2 exit /b 0
if errorlevel 1 exit /b 1
call :install_jar_locked
set "jar_result=%ERRORLEVEL%"
call :release_lock
exit /b %jar_result%

rem True (errorlevel 0) when %jar_path% is there and is the jar this project
rem pins. The hash is checked every run, not only right after the download: the
rem cache is shared by every project on this machine, so the jar sitting under
rem this version may have been put there by another project, pinning another
rem hash. A jar is this project's jar when this project's checksum says so.
:cached_jar_is_ours
if not exist "%jar_path%" exit /b 1
rem nothing to check against; install_jar_locked says so when it downloads
if "%distribution_sha256%"=="" exit /b 0
call :sha256 "%jar_path%"
if /i not "%distribution_sha256%"=="%sha256_result%" exit /b 1
exit /b 0

:install_jar_locked
rem another run may have installed it while we waited for the lock
call :cached_jar_is_ours
if not errorlevel 1 exit /b 0
if exist "%jar_path%" echo The cached %jar_path% is not what %properties_file% pins, downloading it again 1>&2
if not exist "%jar_dir%" mkdir "%jar_dir%" 2>nul
rem this run's own file, so the jar only appears under its real name once it
rem is complete
set "jar_tmp=%jar_dir%\jkite-%run_id%.tmp"

echo Downloading jkite %distribution_version%... 1>&2
set "dl_url=%distribution_url%"
set "dl_out=%jar_tmp%"
call :download
if errorlevel 1 (
  del /f /q "%jar_tmp%" 2>nul
  echo Error downloading jkite from %distribution_url% 1>&2
  exit /b 1
)

if "%distribution_sha256%"=="" goto :jar_unverified
call :sha256 "%jar_tmp%"
if /i not "%distribution_sha256%"=="%sha256_result%" goto :jar_sha_mismatch
goto :jar_verified

:jar_unverified
echo No distributionSha256Sum in %properties_file%, skipping verification 1>&2

:jar_verified
move /y "%jar_tmp%" "%jar_path%" >nul || exit /b 1
exit /b 0

:jar_sha_mismatch
del /f /q "%jar_tmp%" 2>nul
echo SHA-256 mismatch for %distribution_url%: expected %distribution_sha256% but got %sha256_result% 1>&2
echo If you changed the version, update distributionSha256Sum in %properties_file%. 1>&2
exit /b 1

rem ===========================================================================
rem Locking
rem ===========================================================================

rem Takes the lock %lock_dir% for the calling run. MKDIR is atomic, so exactly
rem one run gets it; the others wait, and give up as soon as %lock_done% shows
rem that the work they were waiting for is done.
rem   exit 0 - the lock is ours, do the work and call :release_lock afterwards
rem   exit 1 - gave up (another run is stuck, or its lock directory is stale)
rem   exit 2 - no need to do anything, another run already did the work
:acquire_lock
rem the lock sits next to what it protects, which may not exist yet
for %%P in ("%lock_dir%\..") do if not exist "%%~fP" mkdir "%%~fP" 2>nul
set /a lock_waited=0
:acquire_lock_try
mkdir "%lock_dir%" 2>nul && exit /b 0
if exist "%lock_done%" exit /b 2
if %lock_waited% GEQ %lock_timeout% (
  echo Gave up after %lock_timeout% seconds waiting for another jkite to finish. 1>&2
  echo If no other jkite is running, remove %lock_dir% and try again. 1>&2
  exit /b 1
)
if %lock_waited% EQU 0 echo Waiting for another jkite to finish downloading... 1>&2
call :sleep 1
set /a lock_waited+=1
goto :acquire_lock_try

rem Gives up the lock %lock_dir% again
:release_lock
rmdir /s /q "%lock_dir%" 2>nul
exit /b 0

rem ===========================================================================
rem Downloading and checksums
rem ===========================================================================

rem Downloads %dl_url% to %dl_out%, retrying with a backoff. Fails when the
rem download does. The URL is passed in a variable rather than as an argument
rem because CALL expands % a second time.
:download
setlocal
set /a attempt=0
:download_attempt
set /a attempt+=1
curl -fsSL --proto "=https,http" --proto-redir "=https" "%dl_url%" -o "%dl_out%" 2>nul && (endlocal & exit /b 0)
if %attempt% GTR %download_retry% (endlocal & exit /b 1)
if %download_retry_delay% GTR 0 (
  set /a wait_seconds=%download_retry_delay%
) else (
  rem Exponential backoff: 1, 2, 4, 8, ...
  set /a wait_seconds=1
  for /l %%I in (2,1,%attempt%) do set /a wait_seconds*=2
)
set /a attempts_total=%download_retry%+1
call echo Download %attempt%/%attempts_total% failed. Retry in %%wait_seconds%% second(s)... 1>&2
if %attempt% EQU 1 echo (Set JKITE_DOWNLOAD_RETRY=0 to disable retries^) 1>&2
call :sleep %%wait_seconds%%
goto :download_attempt

rem Waits %1 seconds without needing a console (timeout /t fails when redirected)
:sleep
set /a ping_count=%~1+1
ping -n %ping_count% 127.0.0.1 >nul 2>&1
exit /b 0

rem Sets sha256_result to the lower-case SHA-256 of the file %1
:sha256
setlocal enabledelayedexpansion
set "hash="
for /f "usebackq skip=1 delims=" %%H in (`certutil -hashfile "%~1" SHA256 2^>nul`) do if not defined hash set "hash=%%H"
set "hash=!hash: =!"
endlocal & set "sha256_result=%hash%"
exit /b 0
