#!/usr/bin/env bash
# Extracts every ```mermaid block from the Markdown files and renders it with mermaid-cli.
# A diagram that does not parse fails the build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(mktemp -d)"
echo '{"args":["--no-sandbox","--disable-setuid-sandbox"]}' > "$OUT/puppeteer.json"

count=0
while IFS= read -r file; do
  awk -v out="$OUT" -v name="$(echo "$file" | tr '/.' '__')" '
    /^```mermaid[[:space:]]*$/ { inblock=1; n++; path=sprintf("%s/%s_%02d.mmd", out, name, n); next }
    /^```[[:space:]]*$/ && inblock { inblock=0; close(path); next }
    inblock { print > path }
  ' "$file"
done < <(cd "$ROOT" && find README.md docs examples -name '*.md' -not -path '*/node_modules/*' 2>/dev/null)

failed=0
for diagram in "$OUT"/*.mmd; do
  [ -e "$diagram" ] || continue
  count=$((count + 1))
  if ! npx -y -p @mermaid-js/mermaid-cli mmdc -q -p "$OUT/puppeteer.json" -i "$diagram" -o "$diagram.svg" \
      > "$diagram.log" 2>&1; then
    echo "FAIL $(basename "$diagram")"; sed 's/^/    /' "$diagram.log" | head -20; failed=1
  fi
done
echo "$count Mermaid diagram(s) checked"
exit $failed
