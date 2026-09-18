#!/usr/bin/env bash
# Obtains a token for the given demo user and calls Rocoto with it.
#   ./call-rocoto.sh [user] [path]          default: kate /api/v1/server
set -euo pipefail

ROCOTO_URL="${ROCOTO_URL:-http://localhost:8080/rocoto}"
USERNAME="${1:-kate}"
REQUEST_PATH="${2:-/api/v1/server}"

TOKEN="$("$(dirname "$0")/get-token.sh" "$USERNAME")"

echo "== claims Rocoto looks at" >&2
printf '%s' "$TOKEN" | cut -d. -f2 | python3 -c '
import base64, json, sys
segment = sys.stdin.read().strip()
claims = json.loads(base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4)))
print(json.dumps({k: claims.get(k) for k in ("iss", "aud", "exp", "preferred_username", "realm_access")}, indent=2))' >&2

echo "== GET $ROCOTO_URL$REQUEST_PATH" >&2
curl -s -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL$REQUEST_PATH"
echo
