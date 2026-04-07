#!/usr/bin/env bash
set -euo pipefail

NGINX_CONF="/etc/nginx/sites-enabled/ai-pool"

echo "[1/3] Patching $NGINX_CONF ..."

sudo python3 - << 'PYEOF'
import sys

path = "/etc/nginx/sites-enabled/ai-pool"
content = open(path).read()

insert = """
    # ── ds-python TSFM sidecars ──────────────────────────────────────────────
    location /math/chronos/ {
        proxy_pass http://127.0.0.1:11111/;
    }

    location /math/llama/ {
        proxy_pass http://127.0.0.1:11112/;
    }

    location /math/ttm/ {
        proxy_pass http://127.0.0.1:11113/;
    }
"""

if "/math/chronos/" in content:
    print("  already patched, skipping.")
    sys.exit(0)

# Insert before the final closing brace
idx = content.rfind("}")
if idx == -1:
    print("ERROR: could not find closing brace in nginx config")
    sys.exit(1)

content = content[:idx] + insert + content[idx:]
open(path, "w").write(content)
print("  patched OK.")
PYEOF

echo "[2/3] Testing nginx config ..."
sudo nginx -t

echo "[3/3] Reloading nginx ..."
sudo nginx -s reload

echo ""
echo "Done. Sidecar routes now available on port 9090:"
echo "  http://localhost:9081/math/chronos/health"
echo "  http://localhost:9081/math/llama/health"
echo "  http://localhost:9081/math/ttm/health"
