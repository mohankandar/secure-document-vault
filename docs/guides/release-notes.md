# Release notes

## Next release

**For people who search**

- **Searching people by a part of their name ends by default.** Names, addresses and other sensitive fields are stored
  encrypted and found by their whole value only, in their own field (Filters); capitals don't matter. Searching everywhere and
  "contains" leave them out. A Business Admin can switch "Sensitive" off for a field before the first document with a value
  arrives; afterwards it is locked.
- Sensitive fields are marked "Sensitive". A value you may not see is not shown at all, and the record shows its identifier
  instead of the name.
- A record has one set of values: the latest delivery's replace earlier ones.

**For admins**

- **Notifications by e-mail, Teams and Slack.** IT connects the channels and chooses the technical notifications (IT ›
  Notifications); each Business Admin chooses the entity's (Business administration › Notifications) and keeps the lists of
  who gets e-mail. An e-mail may name a record, never a Highly Confidential one; Teams and Slack never do.
- **Time zones are set in Vero:** the customer's (IT) and an entity's own (Business Admin).

**For IT and sending systems**

- **`SDV_DEFAULT_TIME_ZONE` is gone:** zones are identity's. Before upgrading, set the customer's zone to its value. The schema step
  adds the notifications tables and one table of recipient lists, and moves an entity's own zone to identity.
- Notifications: mail server, Teams and Slack in the install config, each secret by its path in the key service (runbook, "Notifications").
  Links in messages are paths: the web server answers them with `index.html`.
- **New required setting `SDV_ERASE_LOG_FOLDER`** and the erase log: a restored backup is brought up to date (`--erase-replay`) before Vero takes work.
  The schema step adds one table. A document volume can't be the log's folder (`SDV-ERS-002`); probes see `SDV-ERS-001` while it stops work.
- A record's search field values are erased with its last document.
- The ingestion contract is 1.5.0 ([what changed](../api/README.md#changelog)): a metadata value is at most 512 characters,
  and `503 SDV-KEY-002` when a delivery has a sensitive field and the key service can't be used.
- Identifiers that differ only in letter case or spaces name the same record.
- A database with field values of an earlier version is refused by the schema step and left unchanged.
