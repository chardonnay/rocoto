#!/usr/bin/env bash
# Prints an access token for a demo user. DEMO ONLY: the resource owner password grant hands the
# user's password to the client; use the authorization code flow (people) or client credentials
# (services) in real life.
#
#   ./get-token.sh [user] [password]        default: kate / demo-password-123
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
REALM="${REALM:-rocoto-demo}"
USERNAME="${1:-kate}"
PASSWORD="${2:-demo-password-123}"

response="$(curl -s --fail-with-body \
  -d grant_type=password \
  -d client_id=rocoto-cli \
  --data-urlencode "username=$USERNAME" \
  --data-urlencode "password=$PASSWORD" \
  "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token")" || {
    echo "token request failed: $response" >&2
    exit 1
  }

# Extract access_token without jq
printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
