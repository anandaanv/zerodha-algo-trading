"""
Helper for obtaining a JWT from the local backend for /api/** calls.

Reads USERNAME, PASSWORD, BACKEND_URL from the repo-root .local-dev-credentials
file (gitignored) and POSTs to /api/auth/login to get a fresh JWT.

Per CLAUDE.md: never hardcode credentials in scripts, commits, or memory files.
"""
from __future__ import annotations

import json
import os
import urllib.request
from pathlib import Path

_CACHED_JWT: str | None = None
_CACHED_BASE: str | None = None


def _find_repo_root() -> Path:
    """Walk up from this file until a .git dir is found."""
    here = Path(__file__).resolve().parent
    for parent in [here, *here.parents]:
        if (parent / ".git").exists():
            return parent
    raise RuntimeError("could not locate repo root (no .git dir found)")


def _read_creds_file() -> dict[str, str]:
    creds_path = _find_repo_root() / ".local-dev-credentials"
    if not creds_path.exists():
        raise FileNotFoundError(
            f"{creds_path} not found. Create it with BACKEND_URL, USERNAME, PASSWORD "
            f"(see CLAUDE.md 'Local Backend Login Credentials')"
        )
    out: dict[str, str] = {}
    for line in creds_path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def get_jwt(force_refresh: bool = False) -> str:
    """Return a valid JWT for the local backend. Cached after first call."""
    global _CACHED_JWT, _CACHED_BASE
    if _CACHED_JWT and not force_refresh:
        return _CACHED_JWT
    creds = _read_creds_file()
    base = creds.get("BACKEND_URL", "http://localhost:8080").rstrip("/")
    username = creds.get("USERNAME")
    password = creds.get("PASSWORD")
    if not (username and password):
        raise RuntimeError("USERNAME/PASSWORD missing in .local-dev-credentials")
    body = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(
        f"{base}/api/auth/login",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=15) as r:
        resp = json.loads(r.read().decode())
    token = resp.get("token")
    if not token:
        raise RuntimeError(f"login response missing 'token': {resp}")
    _CACHED_JWT = token
    _CACHED_BASE = base
    return token


def get_api_base() -> str:
    """Return backend base URL (e.g. http://localhost:8080). Triggers a login if needed."""
    if _CACHED_BASE is None:
        get_jwt()
    return _CACHED_BASE or "http://localhost:8080"
