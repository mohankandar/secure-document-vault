# Answers to plan 0069 section 15 and report 0072 (design chat, 2026-11-24)

Decided as recommended (LV-19). The owner may review later.

7. **Context on `StreamCipher`:** yes, ask platform-core for it as a 1.x addition (board SDV-25). Don't wait: 0076 uses Vero's own header; it may go later once the context exists.
8. **Start while the database is away:** accept the modules' stop at start (the service manager starts Vero again); while running, keep today's behaviour (503, ready again by itself). Runbook and clean-install check change in 0073.
9. **Vault step:** a **second** Transit mount, so the old local documents stay readable by the old build. Task 0083 writes the owner's script; the owner runs it before 0076. No session touches `sdv-vault`.
10. **Seat rules:** as plan section 4. Recorded only, never enforced.
11. **0080 item 1:** yes. A record's case-field values are erased when its last document is erased, after the same checks (legal hold, quarantine). The record row keeps nothing but its key.
