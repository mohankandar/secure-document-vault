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

- The ingestion contract is 1.5.0 ([what changed](../api/README.md#changelog)): a metadata value is at most 512 characters,
  and `503 SDV-KEY-002` when a delivery has a sensitive field and the key service can't be used.
- Identifiers that differ only in letter case or spaces name the same record.
- A database with field values of an earlier version is refused by the schema step and left unchanged.
