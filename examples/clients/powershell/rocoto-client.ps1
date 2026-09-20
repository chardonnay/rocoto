# Talks to Rocoto from PowerShell: Windows PowerShell 5.1 and PowerShell 7 (Windows, Linux, macOS),
# no modules needed. Everything goes through one System.Net.Http.HttpClient, because
# Invoke-RestMethod behaves differently in 5.1 and 7 (error handling, -SkipHttpErrorCheck) and
# cannot read Server-Sent Events.
#
# Environment: ROCOTO_URL (default http://localhost:8080/rocoto) and either ROCOTO_TOKEN or
# ROCOTO_USER + ROCOTO_PASSWORD.
#
#   $env:ROCOTO_USER = 'otto'; $env:ROCOTO_PASSWORD = '...'; .\rocoto-client.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$Base = if ($env:ROCOTO_URL) { $env:ROCOTO_URL.TrimEnd('/') } else { 'http://localhost:8080/rocoto' }
$Orders = 'com.example.demo:type=Orders'

function Get-Authorization {
    if ($env:ROCOTO_TOKEN) { return "Bearer $($env:ROCOTO_TOKEN)" }
    if ($env:ROCOTO_USER -and $env:ROCOTO_PASSWORD) {
        $pair = [Text.Encoding]::UTF8.GetBytes("$($env:ROCOTO_USER):$($env:ROCOTO_PASSWORD)")
        return 'Basic ' + [Convert]::ToBase64String($pair)
    }
    throw 'Set ROCOTO_TOKEN, or ROCOTO_USER and ROCOTO_PASSWORD'
}
$Authorization = Get-Authorization

$Client = New-Object System.Net.Http.HttpClient
$Client.Timeout = [TimeSpan]::FromSeconds(30)

# Sends one request. Rocoto reports errors as JSON: {"status":403,"error":"Access denied by policy"}
function Invoke-Rocoto {
    param([string] $Method, [string] $Path, $Body)
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]$Method), "$Base$Path"
    [void] $request.Headers.TryAddWithoutValidation('Authorization', $Authorization)
    if ($null -ne $Body) {
        # The Content-Type is mandatory: anything but application/json is answered with 415.
        $json = ConvertTo-Json -InputObject $Body -Depth 10 -Compress
        $request.Content = New-Object System.Net.Http.StringContent $json, ([Text.Encoding]::UTF8), 'application/json'
    }
    $response = $Client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        $message = $text
        try { $message = (ConvertFrom-Json $text).error } catch { }
        $retryAfter = $response.Headers.RetryAfter    # set on 429 (lockout)
        throw "HTTP $([int] $response.StatusCode): $message$(if ($retryAfter) { " (retry after $retryAfter)" })"
    }
    ConvertFrom-Json $text
}

# Resource style: the ObjectName is ONE URL-encoded path segment.
function Read-Attribute {
    param([string] $MBean, [string] $Attribute)
    $path = '/api/v1/mbeans/' + [Uri]::EscapeDataString($MBean) + '/attributes/' + [Uri]::EscapeDataString($Attribute)
    (Invoke-Rocoto GET $path).value
}

# Server-Sent Events: read the response as a stream, an empty line ends an event.
function Watch-Attribute {
    param([string] $MBean, [string] $Attribute, [int] $Count = 3, [int] $Interval = 1000)
    $query = 'mbean=' + [Uri]::EscapeDataString($MBean) + '&attribute=' + [Uri]::EscapeDataString($Attribute) +
        "&interval=$Interval&onChange=false"
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::Get), "$Base/api/v1/subscribe?$query"
    [void] $request.Headers.TryAddWithoutValidation('Authorization', $Authorization)
    [void] $request.Headers.TryAddWithoutValidation('Accept', 'text/event-stream')
    $response = $Client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        # denied or unknown targets fail before the stream starts
        throw "HTTP $([int] $response.StatusCode): $($response.Content.ReadAsStringAsync().GetAwaiter().GetResult())"
    }
    $reader = New-Object System.IO.StreamReader ($response.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
    try {
        $name = 'message'; $data = ''; $seen = 0
        while ($seen -lt $Count -and $null -ne ($line = $reader.ReadLine())) {
            if ($line.StartsWith('event: ')) { $name = $line.Substring(7) }
            elseif ($line.StartsWith('data: ')) { $data += $line.Substring(6) }
            elseif ($line -eq '' -and $data) {
                "$name $data"
                if ($name -eq 'value' -or $name -eq 'error') { $seen++ }
                $name = 'message'; $data = ''
            }
        }
    } finally {
        $reader.Dispose(); $response.Dispose()    # closes the connection = unsubscribes
    }
}

# WebSocket: the upgrade request is authenticated like any other request.
function Watch-ThreadCount {
    param([int] $Count = 3)
    $socket = New-Object System.Net.WebSockets.ClientWebSocket
    $socket.Options.SetRequestHeader('Authorization', $Authorization)
    $none = [Threading.CancellationToken]::None
    $uri = [Uri] (($Base -replace '^http', 'ws') + '/api/v1/ws')
    [void] $socket.ConnectAsync($uri, $none).GetAwaiter().GetResult()
    $send = {
        param($message)
        $bytes = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $message -Compress))
        $segment = New-Object 'System.ArraySegment[byte]' (, $bytes)
        [void] $socket.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $none).GetAwaiter().GetResult()
    }
    # GetResult() of a Task without a value returns VoidTaskResult: [void] keeps it out of the output
    & $send @{ action = 'subscribe'; id = 'threads'; mbean = 'java.lang:type=Threading'
               attributes = @('ThreadCount'); interval = 1000; onChange = $false }
    $buffer = New-Object byte[] 16384
    for ($seen = 0; $seen -lt $Count; $seen++) {
        $text = ''
        do {
            $segment = New-Object 'System.ArraySegment[byte]' (, $buffer)
            $result = $socket.ReceiveAsync($segment, $none).GetAwaiter().GetResult()
            $text += [Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
        } until ($result.EndOfMessage)
        $text
    }
    & $send @{ action = 'unsubscribe'; id = 'threads' }
    [void] $socket.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'done', $none).GetAwaiter().GetResult()
    $socket.Dispose()
}

'== who am I'
(Invoke-Rocoto GET '/api/v1/server').caller | ConvertTo-Json -Compress

'== read'
"heap used: $((Read-Attribute 'java.lang:type=Memory' 'HeapMemoryUsage').used)"
Invoke-Rocoto POST '/api/v1/read' @{ mbean = $Orders; attributes = @('OrdersProcessed', 'QueueDepth', 'DbPassword') } |
    ConvertTo-Json -Compress

'== bulk: every item carries its own status'
$bulk = Invoke-Rocoto POST '/api/v1/bulk' @(
    @{ type = 'read'; mbean = 'java.lang:type=Threading'; attribute = 'ThreadCount' },
    @{ type = 'read'; mbean = 'java.lang:type=Runtime'; attribute = 'DoesNotExist' },
    @{ type = 'search'; pattern = 'java.lang:type=GarbageCollector,*' })
foreach ($item in $bulk.results) {
    $detail = if ($null -ne $item.value) { $item.value } elseif ($item.mbeans) { $item.mbeans -join ', ' } else { $item.error }
    "$($item.status) $detail"
}

"== exec (needs an allow rule for the caller's role)"
try {
    Invoke-Rocoto POST '/api/v1/exec' @{ mbean = $Orders; operation = 'drainQueue'; arguments = @(5) } | ConvertTo-Json -Compress
} catch {
    "failed: $($_.Exception.Message)"
}

'== error handling'
try {
    Read-Attribute 'nope:type=None' 'X'
} catch {
    "failed: $($_.Exception.Message)"
}

'== Server-Sent Events: three values of QueueDepth'
Watch-Attribute $Orders 'QueueDepth' 3

'== WebSocket: three values of ThreadCount'
Watch-ThreadCount 3

$Client.Dispose()
