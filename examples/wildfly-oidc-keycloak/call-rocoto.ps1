# PowerShell counterpart of call-rocoto.sh: obtains a token for the given demo user and calls
# Rocoto with it.
#   .\call-rocoto.ps1 [-User kate] [-Path /api/v1/server]
param(
    [string] $User = 'kate',
    [string] $Path = '/api/v1/server'
)
$ErrorActionPreference = 'Stop'
$RocotoUrl = if ($env:ROCOTO_URL) { $env:ROCOTO_URL.TrimEnd('/') } else { 'http://localhost:8080/rocoto' }
$token = & (Join-Path $PSScriptRoot 'get-token.ps1') -User $User

'== claims Rocoto looks at'
$segment = $token.Split('.')[1].Replace('-', '+').Replace('_', '/')
$segment += '=' * ((4 - $segment.Length % 4) % 4)
$claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($segment)) | ConvertFrom-Json
$claims | Select-Object iss, aud, exp, preferred_username, realm_access | ConvertTo-Json -Depth 5

"== GET $RocotoUrl$Path"
# -UseBasicParsing: Windows PowerShell 5.1 would otherwise need Internet Explorer's parser
(Invoke-WebRequest -UseBasicParsing -Uri "$RocotoUrl$Path" -Headers @{ Authorization = "Bearer $token" }).Content
