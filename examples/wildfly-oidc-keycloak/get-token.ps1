# PowerShell counterpart of get-token.sh: prints an access token for a demo user. DEMO ONLY: the
# resource owner password grant hands the user's password to the client; use the authorization
# code flow (people) or client credentials (services) in real life.
#
#   .\get-token.ps1 [-User kate] [-Password ...]      default: kate and the demo password of the realm
param(
    [string] $User = 'kate',
    [string] $Password
)
$ErrorActionPreference = 'Stop'
$KeycloakUrl = if ($env:KEYCLOAK_URL) { $env:KEYCLOAK_URL.TrimEnd('/') } else { 'http://localhost:8081' }
$Realm = if ($env:REALM) { $env:REALM } else { 'rocoto-demo' }
if (-not $Password) {
    # the demo password lives in the imported realm, not in this script
    $realmFile = Get-ChildItem (Join-Path $PSScriptRoot 'keycloak') -Filter *.json | Select-Object -First 1
    $realmUser = (Get-Content -Raw $realmFile.FullName | ConvertFrom-Json).users | Where-Object username -eq $User
    if (-not $realmUser) { throw "no user $User in $($realmFile.Name) - pass -Password" }
    $Password = $realmUser.credentials[0].value
}
try {
    # a hashtable body is sent as application/x-www-form-urlencoded
    $response = Invoke-RestMethod -Method Post -Uri "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token" -Body @{
        grant_type = 'password'; client_id = 'rocoto-cli'; username = $User; password = $Password
    }
} catch {
    throw "token request failed: $($_.Exception.Message)"
}
$response.access_token
