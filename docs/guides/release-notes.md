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

**For IT and sending systems**

- **New required setting `SDV_ERASE_LOG_FOLDER`** and the erase log: a restored backup is brought up to date (`--erase-replay`) before Vero takes work.
  The schema step adds one table. A document volume can't be the log's folder (`SDV-ERS-002`); probes see `SDV-ERS-001` while it stops work.
- A record's search field values are erased with its last document.
- The ingestion contract is 1.5.0 ([what changed](../api/README.md#changelog)): a metadata value is at most 512 characters,
  and `503 SDV-KEY-002` when a delivery has a sensitive field and the key service can't be used.
- Identifiers that differ only in letter case or spaces name the same record.
- A database with field values of an earlier version is refused by the schema step and left unchanged.
