@echo off
rem ===========================================================================
rem Installs the JDK that runs jkite.jar when the machine has none: the
rem Temurin that jkite.properties pins for this platform, into
rem %JKITE_CACHE_DIR%\jdks\bootstrap
rem (%userprofile%\.jkite\cache\jdks\bootstrap). It prints that directory
rem on stdout and says nothing else there; progress and errors go to stderr.
rem When the JDK is already installed it only prints.
rem
rem The version, URL and SHA-256 were resolved when jkite was released and
rem are committed with the project, so there is no index to read and nothing to
rem decide here: download, verify, unpack.
rem
rem The launcher (jkite.cmd) runs it when it finds no usable JDK; it can
rem just as well be run by hand, or replaced by anything else that puts a JDK
rem there. It is self-contained: no PowerShell, only what Windows ships with
rem (curl, tar, certutil).
rem
rem   jkite-bootstrap-jdk.cmd        install if needed, print the JDK home
rem
rem Environment:
rem   JKITE_DIR, JKITE_CACHE_DIR   where jkite keeps things
rem   JKITE_DOWNLOAD_RETRY, JKITE_DOWNLOAD_RETRY_DELAY
rem   JKITE_LOCK_TIMEOUT               seconds to wait for another run's download
rem
rem Several jkite runs can be started at the same time (a build matrix, a
rem multi-module build). They share ~\.jkite, so the download takes a
rem directory lock (mkdir is atomic: :acquire_lock / :release_lock) and the JDK
rem is unpacked into a directory of this run's own that is renamed into place.
rem
rem Two CMD rules shape the code below and are easy to trip over:
rem   - a variable set inside a parenthesized block cannot be read in that same
rem     block, so anything that reads what it just computed uses GOTO, not IF
rem     blocks;
rem   - CALL expands %% a second time, so URLs (the Temurin ones contain %%2B)
rem     are handed to subroutines in variables, never as arguments.
rem ===========================================================================
setlocal

call :init_settings
if not exist "%jdk_dir%\bin\java.exe" (
  call :read_properties || exit /b 1
  call :install_jdk || exit /b 1
)
echo %jdk_dir%
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
set "jdk_dir=%cache_dir%\jdks\bootstrap"

rem %~dp0 in a subroutine is the label, not this file, so remember where we are
set "script_dir=%~dp0"
set "properties_file=%~dp0jkite.properties"

rem The name this platform has in jkite.properties
set "platform=windows-amd64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "platform=windows-arm64"

rem Tells this run's temporary files apart from those of a jkite running at
rem the same time
set "run_id=%RANDOM%%RANDOM%"

rem How long to wait (in seconds) for another run that is downloading the JDK
set "lock_timeout=600"
if not "%JKITE_LOCK_TIMEOUT%"=="" set "lock_timeout=%JKITE_LOCK_TIMEOUT%"
exit /b 0

rem ===========================================================================
rem The properties
rem ===========================================================================

rem Reads jkite.properties next to this script into jdk_version, jdk_url
rem and jdk_sha256.
rem
rem The values are never handed to CALL: CALL expands %% a second time and the
rem Temurin URLs contain %%2B, which would turn into CALL's second argument. A
rem FOR variable is safe, so the whole file is read in one loop here. The file
rem is written by misc/update-dist.sh and is not meant to be edited by hand, so
rem no whitespace around the values is trimmed.
:read_properties
if not exist "%properties_file%" (
  echo %properties_file% not found. Re-run %script_dir%install.cmd to restore it. 1>&2
  exit /b 1
)
setlocal enabledelayedexpansion
set "found_version="
set "found_url="
set "found_sha="
for /f "usebackq eol=# tokens=1,* delims==" %%K in ("%properties_file%") do (
  if /i "%%K"=="bootstrapJdkVersion" set "found_version=%%L"
  if /i "%%K"=="bootstrapJdkUrl.%platform%" set "found_url=%%L"
  if /i "%%K"=="bootstrapJdkSha256Sum.%platform%" set "found_sha=%%L"
)
endlocal & set "jdk_version=%found_version%" & set "jdk_url=%found_url%" & set "jdk_sha256=%found_sha%"
if "%jdk_url%"=="" (
  echo %properties_file% pins no JDK for %platform% 1>&2
  exit /b 1
)
rem Only https, so that a tampered properties file cannot point the download
rem at a plaintext host
if "%jdk_url:~0,8%"=="https://" exit /b 0
echo Refusing to download a JDK over anything but https: %jdk_url% 1>&2
exit /b 1

rem ===========================================================================
rem Installing
rem ===========================================================================

rem Downloads the JDK into %jdk_dir%, one run at a time; the others wait and
rem then use what it installed.
:install_jdk
if not exist "%cache_dir%\jdks" mkdir "%cache_dir%\jdks" 2>nul
set "lock_dir=%jdk_dir%.lock"
set "lock_done=%jdk_dir%\bin\java.exe"
call :acquire_lock
if errorlevel 2 exit /b 0
if errorlevel 1 exit /b 1
call :install_jdk_locked
set "jdk_result=%ERRORLEVEL%"
call :release_lock
exit /b %jdk_result%

:install_jdk_locked
rem another run may have installed it while we waited for the lock
if exist "%jdk_dir%\bin\java.exe" exit /b 0
rem this run's own names, so the JDK only appears under its real name once it
rem is complete
set "jdk_archive=%cache_dir%\bootstrap-jdk-%run_id%.zip"
set "jdk_unpack_dir=%cache_dir%\jdks\bootstrap-%run_id%.tmp"

echo No Java found. Downloading Temurin %jdk_version%. Be patient, this can take several minutes... 1>&2
set "dl_url=%jdk_url%"
set "dl_out=%jdk_archive%"
call :download
if errorlevel 1 (
  del /f /q "%jdk_archive%" 2>nul
  echo Error downloading the JDK from %jdk_url% 1>&2
  exit /b 1
)

call :sha256 "%jdk_archive%"
if /i not "%jdk_sha256%"=="%sha256_result%" goto :jdk_sha_mismatch

echo Installing Temurin %jdk_version%... 1>&2
if exist "%jdk_unpack_dir%" rmdir /s /q "%jdk_unpack_dir%"
mkdir "%jdk_unpack_dir%"
rem the archive is a .zip on Windows, which the tar Windows ships with reads
tar -xf "%jdk_archive%" -C "%jdk_unpack_dir%"
if errorlevel 1 goto :bootstrap_jdk_broken
rem the archive holds a single root folder, which becomes the JDK directory
set "jdk_root="
for /d %%D in ("%jdk_unpack_dir%\*") do if exist "%%D\bin\java.exe" set "jdk_root=%%D"
if not defined jdk_root goto :bootstrap_jdk_broken
if exist "%jdk_dir%" rmdir /s /q "%jdk_dir%"
rem The move is the install, so whether it worked decides whether there is a
rem JDK. It fails here more often than anywhere else: a virus scanner still has
rem the freshly unpacked executables open, or the cache is on another volume,
rem which turns the move into a copy that can run out of room. Reporting that
rem as a successful install left the launcher to fail later, looking for a
rem java.exe that had never arrived.
move "%jdk_root%" "%jdk_dir%" >nul
if errorlevel 1 goto :bootstrap_jdk_move_failed
if not exist "%jdk_dir%\bin\java.exe" goto :bootstrap_jdk_move_failed
if exist "%jdk_unpack_dir%" rmdir /s /q "%jdk_unpack_dir%"
del /f /q "%jdk_archive%" 2>nul
exit /b 0

:bootstrap_jdk_move_failed
if exist "%jdk_unpack_dir%" rmdir /s /q "%jdk_unpack_dir%"
del /f /q "%jdk_archive%" 2>nul
echo Error installing the JDK: could not move it into %jdk_dir% 1>&2
echo Another program may be holding the unpacked files open, or the drive may be full. 1>&2
exit /b 1

:jdk_sha_mismatch
del /f /q "%jdk_archive%" 2>nul
echo SHA-256 mismatch for %jdk_url%: expected %jdk_sha256% but got %sha256_result% 1>&2
exit /b 1

:bootstrap_jdk_broken
if exist "%jdk_unpack_dir%" rmdir /s /q "%jdk_unpack_dir%"
del /f /q "%jdk_archive%" 2>nul
echo Error installing the JDK 1>&2
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
rem because CALL expands % a second time and the JDK URLs contain %2B.
:download
setlocal
set /a attempt=0
:download_attempt
set /a attempt+=1
curl -fsSL --proto "=https" --proto-redir "=https" "%dl_url%" -o "%dl_out%" 2>nul && (endlocal & exit /b 0)
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
