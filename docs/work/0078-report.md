# Report 0078: records, values and search on case fields (integration E2)

Branch: `integration/e2-values` (from `release/vero-r7` at 2b3da8b)
Outcome: **NOT DONE: design only, no code yet.**

## Design

1. **Record.** Table `records` (entity, source, record ID, created). Each submission points to one. The platform record is
   `RecordRef(scope, "s-<source id>", <record ID>)`: the identity is unique per record type, and a Vero record belongs to one system.
   A submission without identifier values is a record on its own.
2. **Intake.** `findByPrimary`, else `claim` with the identifier values only, then `update` with the rest. Claiming everything would refuse
   a later delivery whose other values differ (`VALUES_CONFLICT`); today such a delivery is accepted, and the contract stays.
   The insert is `on conflict do nothing`, so a race doesn't break Vero's transaction (to be proven with parallel deliveries).
3. **Values belong to the record**, the latest delivery's; today they belong to each submission.
4. **Lists never decrypt.** Item 6 allows one key-service call for an opened record. A results page of 25 titled by name would need 25.
   So a list shows a title made of fields that aren't sensitive, or the default title; the opened record shows the name.
5. **Search.** Exact on sensitive fields by fingerprint (two calls at most); other modes and free search over the rest.
6. **Admin reads** take the first primary in display order through one class over `fld_value`; a sensitive identifier is not shown there.
7. The old value, definition, order and title tables are dropped in the last changeset of this task, with 0077's copy.

## Open points to prove or settle

- Values are at most 512 characters there, 4,000 today: longer ones will be refused with a code.
- A field that is primary because of one system counts in the identity of another system's records too. Needs a test.
- A legal hold must stop the erase of values (`LegalHolds`), and a hold that can't be read must refuse.

## No other product's name or behaviour in code: yes (no code)
