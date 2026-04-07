#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

setup_venv() {
    local name="$1"
    local req="$2"
    local venv="$SCRIPT_DIR/.venv-${name}"

    echo ""
    echo "[setup:${name}] ── venv: ${venv}"

    if [ ! -d "$venv" ]; then
        echo "[setup:${name}] Creating virtual environment ..."
        python3 -m venv "$venv"
    fi

    echo "[setup:${name}] Installing dependencies from ${req} ..."
    "$venv/bin/pip" install --upgrade pip --quiet
    "$venv/bin/pip" install -r "$SCRIPT_DIR/${req}" --quiet
    echo "[setup:${name}] Ready."
}

setup_lagllama_venv() {
    local venv="$SCRIPT_DIR/.venv-lagllama"

    echo ""
    echo "[setup:lagllama] ── venv: ${venv}"

    if [ ! -d "$venv" ]; then
        echo "[setup:lagllama] Creating virtual environment ..."
        python3 -m venv "$venv"
    fi

    "$venv/bin/pip" install --upgrade pip --quiet

    # Step 1: install gluonts + all other deps first (avoids bad metadata in
    # older gluonts wheels that pip hits when resolving lag-llama transitively)
    echo "[setup:lagllama] Installing base dependencies ..."
    "$venv/bin/pip" install -r "$SCRIPT_DIR/requirements-lagllama.txt" --quiet

    # Step 2: install lag-llama itself, skipping dep resolution (already satisfied)
    echo "[setup:lagllama] Installing lag-llama (--no-deps) ..."
    "$venv/bin/pip" install \
        "git+https://github.com/time-series-foundation-models/lag-llama.git" \
        --no-deps --quiet

    echo "[setup:lagllama] Ready."
}

# ── 0. Free sidecar ports (kill any leftover processes) ──────────────────────
for port in 11111 11112 11113; do
    if fuser -k "${port}/tcp" 2>/dev/null; then
        echo "[cleanup] Freed port $port"
        sleep 1
    fi
done

# ── 1. Set up one venv per model ─────────────────────────────────────────────
setup_venv        "chronos"  "requirements-chronos.txt"
setup_lagllama_venv
setup_venv        "granite"  "requirements-granite.txt"

echo ""
echo "[start] All venvs ready. Launching TSFM sidecar pool ..."
echo "        Chronos-Bolt  → http://localhost:11111"
echo "        Lag-Llama     → http://localhost:11112"
echo "        Granite TTM   → http://localhost:11113"
echo ""

# ── 2. Launch pool using system python (sidecar_pool.py has no heavy deps) ──
cd "$SCRIPT_DIR"
exec python3 sidecar_pool.py "$@"
