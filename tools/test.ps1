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
