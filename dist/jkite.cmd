@echo off
rem ===========================================================================
rem jkite launcher for Windows.
rem
rem It runs jkite.jar, so a checkout needs nothing installed, not even a
rem JDK. What it does, in order:
rem
rem   1. Its own options   - --version and --update, answered here without
rem                          running the jar or needing a JDK
rem   2. Which jar to run  - a jkite.jar next to this script when a project
rem                          vendors one, otherwise the one that
rem                          jkite-bootstrap-jar.cmd installs from the
rem                          version pinned in jkite.properties
rem   3. Which Java to use - the bootstrap JDK, JAVA_HOME, javac on the PATH,
rem                          or the JDK that jkite-bootstrap-jdk.cmd (next
rem                          to this script) downloads when there is none
rem   4. Launch            - run the jar; it builds the script and runs it
rem
rem That is all it does. Fetching the jar and fetching a JDK belong to the two
rem bootstrap scripts, so each can be run, tested and replaced on its own.
rem
rem One CMD rule shapes the code below: a variable set inside a parenthesized
rem block cannot be read in that same block, so anything that reads what it
rem just computed uses GOTO, not IF blocks.
rem ===========================================================================
setlocal

call :init_settings

rem --- 1. This script's own options ------------------------------------------
rem Both are about the installation rather than about a script, so neither runs
rem the jar and neither needs a JDK.
if /i "%~1"=="--version" goto :print_version
if /i "%~1"=="-V" goto :print_version
if /i "%~1"=="--update" goto :run_update

rem --yes and --offline are the jar's options, but the launcher fetches the jar
rem and a JDK before the jar ever runs, so it has to read them itself. Only what
rem comes before the script is jkite's: the scan stops at "--" and at the first
rem argument that is not an option, which is the script. A tool of its own with
rem an "-o output" of its own must not put this launcher offline.
set "assume_yes="
set "offline="
set "past_options="
for %%A in (%*) do call :note_arg "%%~A"

rem --- 2. Which jar to run --------------------------------------------------
rem A project may vendor the jar by dropping it next to this script, and then
rem nothing is downloaded. Otherwise the bootstrap script installs the version
rem jkite.properties pins into the cache, shared by every project on this
rem machine, and prints where it put it; everything else goes to stderr.
set "jar_path=%script_dir%jkite.jar"

rem Nothing is fetched without the operator agreeing to it. What the two
rem bootstrap scripts would have to fetch is asked about at once, so a first run
rem asks a single question rather than one per download.
set "net_items="
if not exist "%jar_path%" call :add_net_item_jar
rem This is the real search, not a dry run: it prints why a JAVA_HOME was turned
rem down and leaves java_exec set, and :find_java below reuses it. Searching twice
rem would hide those messages, because the first pass leaves JAVA_HOME pointing at
rem whatever it settled on.
call :find_existing_java
if errorlevel 1 call :add_net_item_jdk
if not defined net_items goto :net_done
call :confirm_downloads || exit /b 1
:net_done

if not exist "%jar_path%" (
  for /f "usebackq delims=" %%J in (`"%script_dir%jkite-bootstrap-jar.cmd"`) do set "jar_path=%%J"
)
if not exist "%jar_path%" exit /b 1

rem --- 3. Which Java to use -------------------------------------------------
call :find_java || exit /b 1
rem The jar goes to java by its 8.3 name, which is ASCII whatever the
rem directory is called. java.exe converts its own command line to the
rem machine's ANSI code page, so a path outside that page - a project under
rem a project under a Japanese profile name on an English Windows, or the
rem other way round - reaches it as question marks, and it says
rem "Unable to access jarfile".
rem
rem Measured rather than guessed: on a runner in code page 437, with the
rem installation under a Japanese directory name, cmd.exe could see the file
rem through a variable and could hand the name to a child process, and
rem java.exe could not open the jar at that path but could at the 8.3 one.
rem So this is java's limit and not the console's, and chcp does not touch it.
rem
rem jar_path itself is left alone: it is what the messages say, and an 8.3
rem name only reads well to a machine. Where short names are turned off for a
rem volume this gives the long one back unchanged, which is where we were.
call :to_short_path jar_arg "%jar_path%"
set launch_cmd="%java_exec%" %JKITE_JAVA_OPTIONS% -jar "%jar_arg%"

rem --- 4. Launch ------------------------------------------------------------
rem The jar does the rest: it builds the script and runs it as a child process
rem with our stdin, stdout and stderr, and exits with the script's status.
%launch_cmd% %*
exit /b %ERRORLEVEL%

rem ===========================================================================
rem This script's own options
rem ===========================================================================

rem Prints the version that will actually run, and under it where that was
rem decided: the version jkite.properties pins, and the jar that will be
rem used. Nothing is downloaded.
rem
rem The cached jar needs no asking to be named: jkite-bootstrap-jar.cmd
rem puts it under the version it pinned and only after its SHA-256 matched, so
rem the directory it sits in is its version. A jar a project vendored next to
rem the launcher is asked, because it is the one that will run and its version
rem is whatever the project put there.
:print_version
call :property distributionVersion
if "%property_value%"=="" (
  echo No distributionVersion in %properties_file% 1>&2
  exit /b 1
)
set "pinned=%property_value%"
set "vendored=%script_dir%jkite.jar"
set "cached=%cache_dir%\jkite\%pinned%\jkite.jar"
if exist "%vendored%" goto :print_vendored_jar
if exist "%cached%" goto :print_cached_jar
echo jkite %pinned%
echo   pinned %pinned% by %properties_file%
echo   jar not installed yet, it is downloaded on the first run
exit /b 0

:print_cached_jar
echo jkite %pinned%
echo   pinned %pinned% by %properties_file%
echo   jar %pinned% at %cached%
exit /b 0

:print_vendored_jar
call :jar_version "%vendored%"
if not defined jar_version_value goto :print_vendored_unknown
echo jkite %jar_version_value%
echo   pinned %pinned% by %properties_file%
echo   jar %jar_version_value% at %vendored% (vendored, so this jar runs and not the pinned version)
exit /b 0
:print_vendored_unknown
echo jkite %pinned%
echo   pinned %pinned% by %properties_file%
echo   jar of an unknown version at %vendored% (vendored, so this jar runs and not the pinned version)
exit /b 0

rem Sets jar_version_value to the version the jar %1 reports for itself, empty
rem when it cannot be asked. The jar is the authority on its own version, and
rem asking it needs no tool for reading a zip; it only needs a JDK that is
rem already here, so nothing is downloaded to answer --version.
rem
rem When it cannot be asked, why is said on stderr: "of an unknown version" on
rem its own leaves a reader with nowhere to go, and it is the only thing a
rem failure here produces.
:jar_version
setlocal
set "found="
rem The search's own complaints are not this command's business, so they go to a
rem file. What it decided is read from java_exec rather than from its exit code:
rem a "call :label" that carries a redirection does not hand that code back.
set "search_err=%TEMP%\jkite-%run_id%-javasearch.txt"
call :find_existing_java 2> "%search_err%"
if not defined java_exec goto :jar_version_nojava
del /f /q "%search_err%" 2>nul
set "probe=%TEMP%\jkite-%run_id%-jarversion.txt"
set "probe_err=%TEMP%\jkite-%run_id%-jarversion-err.txt"
rem by its 8.3 name, for the reason given where launch_cmd is built
call :to_short_path version_jar "%~1"
"%java_exec%" -jar "%version_jar%" --version > "%probe%" 2> "%probe_err%"
for /f "usebackq delims=" %%V in ("%probe%") do if not defined found set "found=%%V"
if not defined found call :say_jar_not_asked "%probe_err%"
del /f /q "%probe%" "%probe_err%" 2>nul
goto :jar_version_done

:jar_version_nojava
echo   (no Java on this machine to ask the jar its version) 1>&2
for /f "usebackq delims=" %%E in ("%search_err%") do echo   %%E 1>&2
del /f /q "%search_err%" 2>nul
goto :jar_version_done

:jar_version_done
endlocal & set "jar_version_value=%found%"
exit /b 0

rem Passes on what the JVM said when the jar could not be asked (%1 is the file
rem its stderr went to).
:say_jar_not_asked
echo   (could not ask the jar its version) 1>&2
for /f "usebackq delims=" %%E in ("%~1") do echo   %%E 1>&2
exit /b 0

rem Replaces this installation with the one from %2 (a branch, tag or commit of
rem the jkite repository; the default is whatever install.cmd defaults to)
rem by running the install.cmd that sits next to this script. Needs no Java and
rem no jar, so it works even when the pinned jar can no longer be downloaded.
:run_update
if not exist "%script_dir%install.cmd" (
  echo %script_dir%install.cmd not found, so this installation cannot update itself. 1>&2
  exit /b 1
)
set "net_items=  - a new jkite installation into %script_dir%"
call :confirm_downloads || exit /b 1
if not "%~2"=="" set "JKITE_REF=%~2"
call "%script_dir%install.cmd" "%script_dir%." || exit /b 1
if not exist "%script_dir%jkite.jar" exit /b 0
echo. 1>&2
echo Warning: %script_dir%jkite.jar was not touched, and a jar next to 1>&2
echo the launcher wins over jkite.properties, so that old jar still runs. 1>&2
echo Remove it, or replace it with the jar of the version just installed. 1>&2
exit /b 0

rem Sets property_value to the value of the key %1 in jkite.properties next
rem to this script, empty when it is not there.
:property
setlocal enabledelayedexpansion
set "found="
if exist "%properties_file%" (
  for /f "usebackq eol=# tokens=1,* delims==" %%K in ("%properties_file%") do (
    if /i "%%K"=="%~1" if not defined found set "found=%%L"
  )
)
:trim_found
if not defined found goto :property_done
if not "!found:~-1!"==" " if not "!found:~-1!"=="	" goto :property_done
set "found=!found:~0,-1!"
goto :trim_found
:property_done
endlocal & set "property_value=%found%"
exit /b 0

rem ===========================================================================
rem Settings
rem ===========================================================================

:init_settings
rem The oldest Java that can run jkite.jar; anything newer is fine, and the
rem JDK a script asks for with //JAVA is chosen by jkite.jar itself
set "min_java_version=11"

rem The directories jkite keeps its JDKs, jars and caches in.
set "jkite_dir=%userprofile%\.jkite"
if not "%JKITE_DIR%"=="" set "jkite_dir=%JKITE_DIR%"
set "cache_dir=%jkite_dir%\cache"
if not "%JKITE_CACHE_DIR%"=="" set "cache_dir=%JKITE_CACHE_DIR%"

rem %~dp0 in a subroutine is the label, not this file, so remember where we are
set "script_dir=%~dp0"
set "properties_file=%~dp0jkite.properties"

rem Tells this run's temporary files apart from those of a jkite running at
rem the same time
set "run_id=%RANDOM%%RANDOM%"
exit /b 0

rem ===========================================================================
rem 1. Which Java to use
rem ===========================================================================

rem Sets java_exec (and JAVA_HOME) to the Java to run the jar with, downloading
rem one when the machine has none. Any Java %min_java_version% or newer will do:
rem the JDK a script asks for with //JAVA is chosen by jkite.jar itself.
rem The java to run jkite.jar with, installing one when the machine has
rem none.
:find_java
rem The search already ran before the download was agreed to; reuse what it found
if defined java_exec exit /b 0
call :find_existing_java && exit /b 0
goto :install_bootstrap_jdk

rem Looks for a JDK that is already on this machine and sets java_exec to its
rem java, without installing anything; exits 1 when there is none.
:find_existing_java
set "java_exec="
rem The JDK jkite-bootstrap-jdk.cmd downloaded on an earlier run
call :usable_java "%cache_dir%\jdks\bootstrap" && (
  set "JAVA_HOME=%cache_dir%\jdks\bootstrap"
  set "java_exec=%cache_dir%\jdks\bootstrap\bin\java.exe"
  exit /b 0
)
rem Then JAVA_HOME, but only when it points to a Java that is recent enough
if "%JAVA_HOME%"=="" goto :find_path_javac
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo JAVA_HOME is set but does not seem to point to a Java runtime 1>&2
  goto :find_path_javac
)
call :java_major "%JAVA_HOME%"
if "%java_major%"=="" (
  echo JAVA_HOME is set but the Java version could not be determined, ignoring it 1>&2
  goto :find_path_javac
)
if %java_major% LSS %min_java_version% (
  echo JAVA_HOME points to Java %java_major% which is older than Java %min_java_version%, ignoring it 1>&2
  goto :find_path_javac
)
set "java_exec=%JAVA_HOME%\bin\java.exe"
exit /b 0

rem Then javac on the PATH (javac rather than java, because a JRE cannot
rem compile scripts). It is asked where its home is, because what is on the
rem PATH is usually a stub (the Oracle javapath one, the App Execution alias)
rem rather than the JDK's own bin directory. -J hands the option to javac's
rem own JVM; it exists since Java 7, and an older javac just prints an error
rem and no java.home, which leaves it ignored.
:find_path_javac
set "path_java="
for /f "delims=" %%J in ('where javac 2^>nul') do if not defined path_java set "path_java=%%J"
if not defined path_java exit /b 1
rem (through a file: a quoted path in front of a pipe is mangled by cmd /c)
rem CALL, because what is on the PATH may be a .cmd or .bat shim, and running
rem one of those from a batch file without CALL hands over for good: this script
rem would end there instead of carrying on with what javac said.
set "path_java_probe=%TEMP%\jkite-%run_id%-java.txt"
call "%path_java%" -J-XshowSettings:properties -version > "%path_java_probe%" 2>&1
set "path_java_home="
for /f "usebackq tokens=1,* delims== " %%A in (`findstr /r /c:"^ *java.home =" "%path_java_probe%"`) do set "path_java_home=%%B"
del /f /q "%path_java_probe%" 2>nul
if not defined path_java_home exit /b 1
call :usable_java "%path_java_home%" || exit /b 1
set "JAVA_HOME=%path_java_home%"
set "java_exec=%path_java_home%\bin\java.exe"
exit /b 0

rem Nothing usable found, so have a JDK of our own installed. The bootstrap
rem script prints where it put the JDK; everything else it says goes to stderr.
:install_bootstrap_jdk
call "%script_dir%jkite-bootstrap-jdk.cmd" >nul || exit /b 1
set "JAVA_HOME=%cache_dir%\jdks\bootstrap"
set "java_exec=%cache_dir%\jdks\bootstrap\bin\java.exe"
exit /b 0

rem Succeeds when %1 holds a JDK (scripts have to be compiled, so a JRE is no
rem use) new enough to run jkite.jar
:usable_java
if not exist "%~1\bin\java.exe" exit /b 1
if not exist "%~1\bin\javac.exe" exit /b 1
call :java_major "%~1"
if "%java_major%"=="" exit /b 1
if %java_major% LSS %min_java_version% exit /b 1
exit /b 0

rem Sets java_major to the major version of the JDK in %1 as read from its
rem 'release' file (e.g. 8, 11, 17); leaves it empty when it cannot be
rem determined. Only the JAVA_VERSION line is used, so a broken or fake
rem 'release' file just causes the JDK to be ignored.
:java_major
set "java_major="
if not exist "%~1\release" exit /b 0
for /f "usebackq tokens=1* delims==" %%A in ("%~1\release") do if "%%A"=="JAVA_VERSION" set "java_major=%%~B"
if "%java_major%"=="" exit /b 0
for /f "tokens=1,2 delims=." %%A in ("%java_major%") do (
  if "%%A"=="1" (set "java_major=%%B") else (set "java_major=%%A")
)
exit /b 0

rem --- Asking before going to the network ------------------------------------
rem
rem jkite downloads three kinds of thing: its own jar, a JDK to run that jar
rem with, and the dependencies a script declares. This script can see the first
rem two and asks about them; the jar asks about the third, which only it knows
rem about. Both use the same contract, JKITE_CONFIRM_DOWNLOADS:
rem
rem   auto    the default: ask when there is a terminal, otherwise say what is
rem           being fetched and go ahead
rem   always  ask, and fetch nothing when there is no terminal to ask on
rem   never   never ask. JKITE_ASSUME_YES=1|true|yes and --yes do the same

:note_arg
if defined past_options exit /b 0
if "%~1"=="--" (set "past_options=1" & exit /b 0)
if "%~1"=="-y" (set "assume_yes=1" & exit /b 0)
if "%~1"=="--yes" (set "assume_yes=1" & exit /b 0)
if "%~1"=="-o" (set "offline=1" & exit /b 0)
if "%~1"=="--offline" (set "offline=1" & exit /b 0)
set "arg=%~1"
if not "%arg:~0,1%"=="-" set "past_options=1"
exit /b 0

:add_net_item_jar
call :property distributionVersion
set "net_items=%net_items%  - jkite.jar %property_value%|"
exit /b 0

:add_net_item_jdk
call :property bootstrapJdkVersion
set "net_items=%net_items%  - a JDK to run it with (Temurin %property_value%); this machine has none|"
exit /b 0

rem Prints the things in net_items, one per line
:print_net_items
setlocal enabledelayedexpansion
set "rest=%net_items%"
:print_net_items_loop
if not defined rest goto :print_net_items_done
for /f "tokens=1* delims=|" %%A in ("!rest!") do (
  echo %%A
  set "rest=%%B"
)
goto :print_net_items_loop
:print_net_items_done
endlocal
exit /b 0

rem Asks whether the things in net_items may be downloaded. 0 to go ahead, 1 to stop.
:confirm_downloads
rem net_items is set. exit /b 0 to go ahead, exit /b 1 to stop.
rem --offline is a refusal, not a question, and it is the launcher's to honour:
rem the jar reads it too, but the jar is one of the things fetched to get there.
if defined offline (
  echo jkite has to download: 1>&2
  call :print_net_items 1>&2
  echo --offline was given, so nothing was downloaded. 1>&2
  echo Run once without it, or vendor jkite.jar next to the launcher. 1>&2
  exit /b 1
)
set "net_mode=%JKITE_CONFIRM_DOWNLOADS%"
if not defined net_mode set "net_mode=auto"
rem the same three values the jar accepts, and not "any value": a
rem JKITE_ASSUME_YES=0 that reads as yes is only noticed after a download
if /i "%JKITE_ASSUME_YES%"=="1" exit /b 0
if /i "%JKITE_ASSUME_YES%"=="true" exit /b 0
if /i "%JKITE_ASSUME_YES%"=="yes" exit /b 0
if "%assume_yes%"=="1" exit /b 0
if /i "%net_mode%"=="never" exit /b 0
if /i "%net_mode%"=="always" goto :net_ask
if /i "%net_mode%"=="auto" goto :net_ask
echo Ignoring invalid JKITE_CONFIRM_DOWNLOADS: %JKITE_CONFIRM_DOWNLOADS% 1>&2
set "net_mode=auto"

:net_ask
rem timeout fails when stdin is redirected, which is how this tells a terminal
rem from a pipe. Without one there is nobody to ask.
2>nul >nul timeout /t 0 || goto :net_no_terminal
rem To the console device and not to stdout: stdout belongs to the tool that is
rem about to run, so a question written there lands in the pipe that a
rem "jkite Tool.java | find" was meant to carry, and in the file that a
rem "> out.txt" was meant to fill - where it reads as a hang, because the
rem cursor waiting for an answer is the only thing not redirected. CON is what
rem /dev/tty is on the other side.
>CON echo.
>CON echo jkite has to download:
call :print_net_items >CON
>CON echo.
set "net_answer=y"
<CON >CON set /p "net_answer=Continue? [Y/n]: "
if /i "%net_answer%"=="y" exit /b 0
if /i "%net_answer%"=="yes" exit /b 0
if "%net_answer%"=="" exit /b 0
echo Stopped. Nothing was downloaded. 1>&2
exit /b 1

:net_no_terminal
echo jkite has to download: 1>&2
call :print_net_items 1>&2
if /i not "%net_mode%"=="always" exit /b 0
echo There is no terminal to ask on and JKITE_CONFIRM_DOWNLOADS=always. 1>&2
echo Set it to auto or never, or pass --yes, to allow the download. 1>&2
exit /b 1

rem ===========================================================================
rem Paths
rem ===========================================================================

rem Sets %1 to the 8.3 form of the path in %2. Only for a path about to be
rem handed to java.exe; see where launch_cmd is built for why. A path that has
rem no short form - 8.3 generation turned off for the volume, or a file that is
rem not there - comes back unchanged, which is what it would have been anyway.
:to_short_path
for %%I in ("%~2") do set "%~1=%%~sI"
if not defined %~1 set "%~1=%~2"
exit /b 0
