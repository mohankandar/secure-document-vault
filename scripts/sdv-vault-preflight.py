#!/usr/bin/env python3
"""Preflight an existing, unsealed SDV DEV Vault and validate its Transit token."""
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: sdv-vault-preflight.py VAULT_URL TOKEN_FILE")
    base, tokenfile = sys.argv[1].rstrip("/"), Path(sys.argv[2])
    if not base.startswith(("http://127.0.0.1:", "http://localhost:")):
        raise SystemExit("DEV Vault must use localhost")
    if not tokenfile.is_file() or not tokenfile.stat().st_size:
        raise SystemExit("Application Vault token file is absent or empty")
    if tokenfile.stat().st_mode & 0o077:
        raise SystemExit("Vault token file must be private (chmod 600)")
    token = tokenfile.read_text().strip()
    def get(path, use_token=False, body=None):
        headers = {}
        if use_token:
            headers["X-Vault-Token"] = token
        data = json.dumps(body).encode() if body is not None else None
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = Request(base + "/v1" + path, headers=headers, data=data,
                      method="POST" if body is not None else "GET")
        with urlopen(req, timeout=5) as response:
            return json.loads(response.read())
    try:
        state = get("/sys/seal-status")
        if not state.get("initialized") or state.get("sealed"):
            raise SystemExit("Vault is sealed or uninitialized. Unseal it manually. No automatic initialization.")
        if not get("/auth/token/lookup-self", True).get("data"):
            raise SystemExit("Vault application token is invalid")
        mount = get("/sys/internal/ui/mounts/transit", True)
        if mount.get("data", {}).get("type") != "transit":
            raise SystemExit("Vault Transit is not available")
        prefix = os.environ.get("SDV_VAULT_KEY_PREFIX", "sdv-tenant-")
        for op in ("encrypt", "decrypt"):
            result = get("/sys/capabilities-self", True,
                         {"paths": ["transit/" + op + "/" + prefix + "preflight"]})
            if not set(result.get("capabilities", [])).intersection({"create", "update", "sudo"}):
                raise SystemExit("Vault token lacks required " + op + " capability on Transit tenant keys")
    except (HTTPError, URLError, ValueError, KeyError) as exc:
        raise SystemExit("Vault readiness/token/Transit preflight failed: " + type(exc).__name__) from None
    finally:
        token = ""
    print("Vault preflight PASS: unsealed, Transit mounted, application token authorized")
if __name__ == "__main__":
    main()
