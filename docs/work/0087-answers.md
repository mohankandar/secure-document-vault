# Answers to plan 0087 (remote testers), design chat, 2026-11-24

1. **Tunnel: Cloudflare Tunnel with Access and a domain** (owner, 2026-11-24). Only listed e-mail addresses get through; testers install nothing. All other recommendations of the plan accepted by the owner.
2. **Keys for the tester database:** **its own key**, not the development keys. The tester instance is set up as its own customer in the `vero-transit` mount, through the setup command (runbook, "Keys for a customer"). It costs nothing and keeps tester and development data apart.
