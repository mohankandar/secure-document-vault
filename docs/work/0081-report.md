# Report 0081: notifications (integration H)

Date: 2026-11-27
Branch: `integration/h-notifications` (from `release/vero-r7` at 0b6a852)
Outcome: **DONE** (screen tour: see "Not done")

## What was done

1. **`platform-notifications-spring`** (platform-core 1.1.1, one message per recipient) and the `notif_` run of the schema step
   (version 1, history table `notif_schema_history`, which Vero names: the module names none). The app user keeps select only on
   `notif_schema_version`.
2. **Vero's catalogue** (`VeroEvent`, `VeroCatalogue`): business events per entity (approval waiting, request approved, declined,
   quarantine incident opened, released, legal hold placed, release asked, released, roles withheld, retention cleanup with failures)
   and technical ones for IT (documents not stored, deliveries still missing documents, storage nearly full, full, unidentified intake
   attempts, key service not reachable), plus platform-core's licensing events. Names are generic: none says what kind of request or
   document it is about.
3. **Content (answer 4).** Event, counts, times, link. The record's identifier goes **by e-mail only**, only when the record's level is
   known and is not Highly Confidential, and only when it fits the module's shape; Teams and Slack never get it. As plan section 13 says,
   every event with an identifier is **two event types**: the e-mail one and its Teams/Slack twin (off until the admin picks a channel).
   The e-mail type is never given a channel; should it be, its message goes without the identifier.
4. **Nobody learns of a Highly Confidential document:** recipients are roles in an entity, the text never names a level, title, name,
   file name or reason; a print or download request (always Highly Confidential) is "Approval waiting" without an identifier.
5. **Recipient lists** kept by the admins (table `notification_recipients`, changeset 049): an entity's lists per role (Business
   Admin), the customer's IT Admin list (IT Admin). Audited (`NOTIFICATION_RECIPIENT_ADDED`, `_REMOVED`) with role and user ID, never
   the address; the address is in no log or audit. A requester is told the outcome when they are on one of the customer's lists.
6. **Screens:** IT › Notifications (channels from the install config, each secret by its path; health; app address; technical events;
   customer's time zone; IT Admins' list) and Business administration › Notifications (which events, how often, e-mail, which channels;
   the entity's time zone; its lists). Secrets through a `SecretResolver` over the key service's KV store (`secret/platform/<path>`,
   key `value`); no secret in the database or on a screen.
7. **Hooks:** approvals (waiting, decided), legal holds, quarantine (opened, release asked, released), role conflicts, the retention
   cleanup; a watch every 5 minutes for the technical conditions (alert once lasted, repeat, Recovered). Everything is published after
   the commit; a notification never stops or undoes the work.
8. **Time zones from identity:** `tenants.time_zone` copied to `idn_entity.time_zone` and dropped (changeset 049);
   `SDV_DEFAULT_TIME_ZONE` and `sdv.tenant-defaults.time-zone` are gone. Reports: the entity's own zone, else the customer's, else
   UTC; `timeZoneSource` is `ENTITY`, `CUSTOMER` or `NONE`. In-app notices stay as they are.
9. Error codes `SDV-NTF-001` to `-007`; `SDV-RPT-004` reworded, `SDV-SYS-010` retired. IT and Business Admin guides, runbook
   ("Notifications", the schema step, settings), release notes.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results



## Tests whose assertions changed, and why

| Test | Why |
|---|---|
| `IntakeReportServiceTest`, `SensitivityReportServiceTest` | The install default is gone: the customer's zone from identity takes its place; `TENANT`/`INSTALL_DEFAULT` become `ENTITY`/`CUSTOMER`/`NONE`; the start refusal `SDV-SYS-010` became the report refusal `SDV-RPT-004` |
| `IntakeReportPostgresTest`, `SensitivityReportPostgresTest`, `ActivityReportPostgresTest` | Zones are set in identity's rows instead of `tenants.time_zone` (gone) |
| `ActivityReportServiceTest`, `StorageReportServiceTest`, `*PostgresTest`, `RestrictionsPostgresTest` | Constructors without the install default; assertions unchanged |
| `ReportsControllerTest` | The mocked reports' zone source value |
| `SchemaVersionTest`, both schema scripts | The notifications run; 049 |
| `ChangesetChecksumBaselineTest`, `JsonShapesBaselineTest` | One new changeset line; two new one-component request records (`AddressRequest`, `ZoneRequest`) |

## Decisions taken



## Files changed

80 files, +4614 / -1047 (`git diff --stat 0b6a852..HEAD`). New: `application/notifications/` (VeroEvent, VeroNotice, RecipientListService), `NotificationPort`, `NotificationRecipientRepository`, `persistence/notifications/`, `web/notifications/` (catalogue, port, recipients, secrets, settings service, controller, watch, configuration), changeset 049, the two screens and their words in `frontend/sdv-ui/src/r7/`, tests. Changed: the approvals, hold, quarantine, role-conflict and cleanup services (hooks), the four report services and `TenantZone` (zones), schema runs, `application.yml`, `pom.xml`, both schema scripts, `Start-SDV-Testers.ps1`, error codes, guides, runbook, release notes.

## Commits

(filled in below)

## Not done, and why



## Questions for the owner

- Answer 4 (identifier by e-mail only, never Highly Confidential, never Teams or Slack) is built as decided; the owner reviews it
  before it is released, as the task says.

## Suggested next step

0082 (approvals from notifications).
