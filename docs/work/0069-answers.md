# Answers to plan 0069's questions (design chat, 2026-11-24, LV-19: decided as recommended; owner may change any)

1. **Keys per customer**, not per entity: yes (matches the owner's "separate keys per customer" and the platform's model; customer exit is one step).
2. **Local documents unreadable after the crypto move:** drop and re-seed them, with a new local database made beside the old one; the old one is kept until the owner deletes it.
3. **Names and addresses sensitive by default** (exact-match search only): keep the platform's default; a Business Admin can switch a field off before data arrives.
4. **Record numbers in notifications:** the owner's rule is "event, identifier, link; nothing sensitive". Decided: the record's identifier goes in **e-mail only**, never for a Highly Confidential record, and **never in Teams or Slack** (webhooks post to a third party's servers); those get the event and the link only. Flagged for the owner's review.
5. **Recipients' addresses:** lists kept by the admins first; addresses from sign-in later.
6. **Intake after the licence grace period:** 503 with its own `SDV-LIC-…` code (not busy, no `Retry-After`), so senders keep documents and retry; a contract version bump (minor).

Use these when the integration tasks are written after `platform-core-v1.0.0`; recheck plan section 13 against the release first.
