param([string]$IdfPath = $env:IDF_PATH, [string]$HostGcc = 'gcc')
$ErrorActionPreference = 'Stop'
if (-not $IdfPath) { throw 'Activate ESP-IDF or provide -IdfPath.' }
$cjson = Join-Path $IdfPath 'components/json/cJSON'
New-Item -ItemType Directory -Force build-host | Out-Null
& $HostGcc -std=c11 -Wall -Wextra -Werror -I main -I tests/stubs -I $cjson tests/test_core.c main/protocol.c main/control.c "$cjson/cJSON.c" -o build-host/test_core.exe
if ($LASTEXITCODE) { throw 'Host compilation failed' }
& ./build-host/test_core.exe
if ($LASTEXITCODE) { throw 'Core tests failed' }
node --check web/app.js
if ($LASTEXITCODE) { throw 'JavaScript syntax check failed' }
node tests/test_web.cjs
if ($LASTEXITCODE) { throw 'Web tests failed' }

$motionSources = Get-ChildItem firmware/stm32/src -Filter '*.c' | ForEach-Object { $_.FullName }
& $HostGcc -std=c11 -Wall -Wextra -Werror -Ifirmware/stm32/src -Ifirmware/stm32/board -Ifirmware/esp32/main -Ifirmware/shared tests/test_replay.c $motionSources firmware/esp32/main/dollybot_protocol.c firmware/esp32/main/vofa.c -lm -o build-host/test_replay.exe
if ($LASTEXITCODE) { throw 'Replay test compilation failed' }
& ./build-host/test_replay.exe
if ($LASTEXITCODE) { throw 'Replay tests failed' }
