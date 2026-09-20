# PowerShell counterpart of test.sh (Windows PowerShell 5.1 and PowerShell 7): runs the requests
# documented in README.md and fails on the first unexpected status code.
# DEMO credentials only - see README.md. They are read from test.sh, so there is one place to
# change them.
#
#   docker compose up -d        (instead of "make up"; wait until /api/v1/health/live answers 200)
#   .\test.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$RocotoUrl = if ($env:ROCOTO_URL) { $env:ROCOTO_URL.TrimEnd('/') } else { 'http://localhost:8080/rocoto' }
$bash = Get-Content -Raw (Join-Path $PSScriptRoot 'test.sh')
$Password = [regex]::Match($bash, '(?m)^PASSWORD="([^"]+)"').Groups[1].Value
$Token = [regex]::Match($bash, '(?m)^TOKEN="([^"]+)"').Groups[1].Value
$Orders = 'com.example.demo%3Atype%3DOrders'
$script:Failures = 0
$Client = New-Object System.Net.Http.HttpClient
$Client.Timeout = [TimeSpan]::FromSeconds(30)

function Send {
    param([string] $Method, [string] $Url, [string] $User, [string] $Secret, [string] $Bearer, [string] $Json, [string] $Form)
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]$Method), $Url
    if ($User) {
        $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${User}:$Secret"))
        [void] $request.Headers.TryAddWithoutValidation('Authorization', "Basic $basic")
    }
    if ($Bearer) { [void] $request.Headers.TryAddWithoutValidation('Authorization', "Bearer $Bearer") }
    if ($Json) { $request.Content = New-Object System.Net.Http.StringContent $Json, ([Text.Encoding]::UTF8), 'application/json' }
    if ($Form) { $request.Content = New-Object System.Net.Http.StringContent $Form, ([Text.Encoding]::UTF8), 'application/x-www-form-urlencoded' }
    $Client.SendAsync($request).GetAwaiter().GetResult()
}

function Expect {
    param([int] $Want, [string] $What, [hashtable] $Request)
    $got = [int] (Send @Request).StatusCode
    if ($got -eq $Want) { 'ok    {0}  {1}' -f $got, $What }
    else { 'FAIL  {0}  {1} (expected {2})' -f $got, $What, $Want; $script:Failures++ }
}

$rita = @{ User = 'rita'; Secret = $Password }
$otto = @{ User = 'otto'; Secret = $Password }
$ada = @{ User = 'ada'; Secret = $Password }

Expect 200 'liveness probe is anonymous (health.allowAnonymous)' @{ Method = 'GET'; Url = "$RocotoUrl/api/v1/health/live" }
Expect 401 'everything else needs credentials' @{ Method = 'GET'; Url = "$RocotoUrl/api/v1/server" }
Expect 401 'wrong password' @{ Method = 'GET'; Url = "$RocotoUrl/api/v1/server"; User = 'rita'; Secret = 'wrong-password-000' }
Expect 200 'reader: server info' ($rita + @{ Method = 'GET'; Url = "$RocotoUrl/api/v1/server" })
Expect 200 'reader: read all attributes of the demo MBean' ($rita + @{ Method = 'GET'; Url = "$RocotoUrl/api/v1/mbeans/$Orders/attributes" })
Expect 200 'reader: normalized thread pools' ($rita + @{ Method = 'GET'; Url = "$RocotoUrl/api/v1/resources/threadpools" })
Expect 404 'reader: unknown attribute' ($rita + @{ Method = 'GET'; Url = "$RocotoUrl/api/v1/mbeans/$Orders/attributes/Nope" })
Expect 403 'reader: exec is denied' ($rita + @{ Method = 'POST'; Url = "$RocotoUrl/api/v1/exec"
    Json = '{"mbean":"com.example.demo:type=Orders","operation":"resetCounters"}' })
Expect 200 'operator: exec drainQueue(5)' ($otto + @{ Method = 'POST'; Url = "$RocotoUrl/api/v1/exec"
    Json = '{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}' })
Expect 403 'operator: write MaintenanceMode is denied' ($otto + @{ Method = 'PUT'
    Url = "$RocotoUrl/api/v1/mbeans/$Orders/attributes/MaintenanceMode"; Json = '{"value":true}' })
Expect 200 'admin: write MaintenanceMode=true' ($ada + @{ Method = 'PUT'
    Url = "$RocotoUrl/api/v1/mbeans/$Orders/attributes/MaintenanceMode"; Json = '{"value":true}' })
Expect 200 'admin: write MaintenanceMode=false' ($ada + @{ Method = 'PUT'
    Url = "$RocotoUrl/api/v1/mbeans/$Orders/attributes/MaintenanceMode"; Json = '{"value":false}' })
Expect 403 'admin: no allow rule for Threading.resetPeakThreadCount' ($ada + @{ Method = 'POST'; Url = "$RocotoUrl/api/v1/exec"
    Json = '{"mbean":"java.lang:type=Threading","operation":"resetPeakThreadCount"}' })
Expect 415 'POST without application/json' ($ada + @{ Method = 'POST'; Url = "$RocotoUrl/api/v1/exec"
    Form = 'mbean=java.lang:type=Memory&operation=gc' })
Expect 200 'token: metrics' @{ Method = 'GET'; Url = "$RocotoUrl/metrics"; Bearer = $Token }
Expect 200 'Jolokia protocol: version' ($rita + @{ Method = 'GET'; Url = "$RocotoUrl/jolokia/version" })

# Content checks
$masked = $rita + @{ Method = 'GET'; Url = "$RocotoUrl/api/v1/mbeans/$Orders/attributes/DbPassword" }
$body = (Send @masked).Content.ReadAsStringAsync().GetAwaiter().GetResult()
if ($body -like '*"value":"`*`*`*`*`*`*`*`*"*') { 'ok         DbPassword is masked' }
else { "FAIL       DbPassword is not masked: $body"; $script:Failures++ }

$metrics = (Send -Method GET -Url "$RocotoUrl/metrics" -Bearer $Token).Content.ReadAsStringAsync().GetAwaiter().GetResult()
foreach ($metric in 'jvm_memory_heap_used_bytes', 'tomcat_threads_busy', 'demo_orders_processed_total', 'demo_orders_queue_depth') {
    if ($metrics -match "(?m)^$metric") { "ok         metric $metric" }
    else { "FAIL       metric $metric missing"; $script:Failures++ }
}

$Client.Dispose()
if ($script:Failures -gt 0) { "$($script:Failures) check(s) failed"; exit 1 }
'all checks passed'
