#!/usr/bin/env python3
"""Non-mutating SDV DEV Vault readiness and application Transit access preflight."""
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: sdv-vault-preflight.py VAULT_URL TOKEN_FILE")
    base = sys.argv[1].rstrip("/")
    tokenfile = Path(sys.argv[2])
    if not base.startswith(("http://127.0.0.1:", "http://localhost:")):
        raise SystemExit("DEV Vault must use a localhost endpoint")
    if not tokenfile.is_file() or not tokenfile.stat().st_size:
        raise SystemExit("Application Vault token file is absent or empty")
    if tokenfile.stat().st_mode & 0o077:
        raise SystemExit("Vault application token file must be private (chmod 600)")
    token = tokenfile.read_text(encoding="utf-8").strip()
    if not token:
        raise SystemExit("Vault application token file is empty")

    def read(path, authenticated=False):
        headers = {"X-Vault-Token": token} if authenticated else {}
        request = Request(base + "/v1" + path, headers=headers)
        with urlopen(request, timeout=5) as response:
            return json.load(response)

    try:
        state = read("/sys/seal-status")
        if not state.get("initialized") or state.get("sealed"):
            raise SystemExit("Vault is sealed or uninitialized. Unseal manually; no automatic initialization.")

        # Vault uses the ACL permissions on the target path for this internal
        # mount preflight endpoint. This performs no encryption, key creation,
        # token lookup or mutation of Vault state.
        prefix = os.environ.get("SDV_VAULT_KEY_PREFIX", "sdv-tenant-")
        mount_name = os.environ.get("SDV_VAULT_TRANSIT_MOUNT", "transit").strip("/")
        if not mount_name or not prefix or "/" in prefix:
            raise SystemExit("Invalid SDV Transit mount or tenant-key prefix configuration")
        for operation in ("keys", "datakey/plaintext", "decrypt"):
            target = mount_name + "/" + operation + "/" + prefix + "preflight"
            mount = read("/sys/internal/ui/mounts/" + target, authenticated=True)
            if mount.get("data", {}).get("type") != "transit":
                raise SystemExit("Vault Transit mount is missing or inaccessible for " + operation)
    except HTTPError as exc:
        raise SystemExit("Vault Transit ACL preflight denied/unavailable (HTTP " + str(exc.code) + ")") from None
    except (URLError, ValueError, KeyError) as exc:
        raise SystemExit("Vault readiness/Transit preflight failed: " + type(exc).__name__) from None
    finally:
        token = ""

    print("Vault preflight PASS: unsealed, existing Transit mount and SDV policy paths accessible")
    print("Note: preflight does not create keys or perform cryptographic operations.")


if __name__ == "__main__":
    main()
