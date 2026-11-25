# Trying Vero yourself

For the owner. Everything is on this computer, with made-up data. Nothing you do can harm real documents.

## Start

In PowerShell:

```
cd C:\dev\sdv\secure-document-vault
git checkout release/vero-r7
git pull
.\Set-SDV-DevKeycloak.ps1
.\Start-SDV-OwnerTest.ps1
```

- `Set-SDV-DevKeycloak.ps1` is needed **once**, for the two new test people (second Business Admin, Platform operations). It asks for the
  Keycloak admin. "Not complete" with a warning about a person's group: send me the warning.
- `Start-SDV-OwnerTest.ps1` takes about five minutes the first time (it builds, makes the database `sdv_vero` and fills it) and half a
  minute afterwards. It deletes nothing. The **first time** it asks for a Vault token. In a second PowerShell window:

  ```
  docker exec -it -e VAULT_ADDR=http://127.0.0.1:8200 sdv-vault sh
  read -s VAULT_TOKEN; export VAULT_TOKEN
  vault token create -policy=platform-crypto-setup -ttl=1h -no-default-policy -field=token
  exit
  ```

  After the second line, paste your own Vault token and press Enter (nothing is shown). The third prints the token to paste into the
  first window.
- Open **http://localhost:5183/** when it says so. The same command again: "already running".

**Stop:** `.\Stop-SDV-OwnerTest.ps1`. Your data stays.

**After a computer restart:** start Docker Desktop, then unseal Vault (asks for your key):
`docker exec -it -e VAULT_ADDR=http://127.0.0.1:8200 sdv-vault vault operator unseal`, then `.\Start-SDV-OwnerTest.ps1`. It starts the
other containers itself.

## Signing in

Names and passwords are in `C:\dev\sdv\dev-test-users.properties` (only you can open it). To be two people at once, use a normal and a
private browser window. Sign out from the account menu, top right.

| Page | Test person |
|---|---|
| [Agent](#agent) | `vero-test-business-user` |
| [Steward](#steward) | `vero-test-document-steward` |
| [Restricted Steward](#restricted-steward) | `vero-test-restricted-steward` |
| [Business Admin](#business-admin) | `vero-test-business-admin`, and `vero-test-business-admin-2` for the second opinion |
| [IT Admin](#it-admin) | `vero-test-it-admin` |
| [Platform operations](#platform-operations) | `vero-test-platform-operations` |

`vero-test-no-group` is in no group: Vero should refuse it (`SDV-AUT-005`).

## Reporting a problem

Take a screenshot, and copy the reference from the error (it starts with `R-`). Say who you were signed in as, what you did, and what you
expected. Paste that into the chat. Please don't paste passwords.

## Not built yet: please don't test

- **Erase log:** bringing a restored backup up to date (in work).
- **Notifications** by e-mail, Teams or Slack.
- **Approvals from messages:** approving is done inside Vero only.

---

## Agent

The everyday person who looks things up. Sees only Home and Records.

| Try | What should happen |
|---|---|
| Search `300000000001` | The record is found; it opens on the right |
| Search `Patel` in the box that searches everywhere | Names are private: found by the **whole** name in its own field (Filters), not by a part here |
| Open documents | The pages show. No print or download buttons: your Business Admin has not allowed them |
| Open the record `300000000007` | Its Highly Confidential documents are locked; no title or content is shown |
| Type any other page's address, such as `#/governance/approvals` | You are not shown that page |

---

## Steward

Corrects documents. Sees Home, Records and Quarantine.

| Try | What should happen |
|---|---|
| Open `300000000001`, read, tag, comment, print, download | All work on Standard and Confidential documents |
| **Redact:** open a document, Redact, draw a box, write a reason, confirm | The text under the box is gone for good. Try a document you don't mind changing: this can't be undone |
| Redact a document that is on legal hold (as Business Admin, hold one first) | It isn't changed yet: the request waits for a Business Admin |
| Ask for a lower level on a Confidential document (`300000000005`) | "Request sent"; nothing changes until it is approved |
| **Quarantine** one document as delivered wrongly | Everybody else sees "temporarily unavailable". Review it and release it again |
| Open `300000000007` | Highly Confidential documents stay locked for you too |

---

## Restricted Steward

May open Highly Confidential documents, but printing and downloading them needs an approval.

| Try | What should happen |
|---|---|
| Open `300000000007` or `300000000008` | The documents open |
| **Request print** or **Request download**, with a reason | "Request sent". As Business Admin, approve it; back here, it can be used once, within 24 hours |
| Print it after the approval | The pages carry your name and the time |
| Let the Business Admin decline another one | You see that it was declined, and you can't print |
| **Quarantine** everything that matches a filter | The incident is listed with counts; people who can't see a document learn nothing about it |
| A document redacted while on hold: look at its sealed original | Only you can open it; it can't be printed or downloaded |

---

## Business Admin

Sets the rules and decides. Never sees a document, a title or a file name. If asked, choose **Acme Lending**.

| Try | What should happen |
|---|---|
| **Approvals:** approve one request, decline another with a reason | They leave the list; the person sees the answer. Nobody can approve their own |
| **Legal holds:** hold `300000000004` with a reason | All its documents are held; nothing of them is deleted |
| **Release the hold:** Request release | It waits for another Business Admin. Sign in as `vero-test-business-admin-2`, who didn't place it, and approve |
| **Roles & permissions:** allow Business Users to print | After signing in again, the Agent has the button on Standard documents |
| **Search fields:** look at "Sensitive" | Locked where values exist already |
| **Keeping documents**, **Reports**, **Audit report** | Counts and rules only; an export appears in the audit |
| **Licence** | The demo licence, its state and the dates |

---

## IT Admin

Connects systems. Never sees documents or approvals.

| Try | What should happen |
|---|---|
| **Sign-in:** Test sign-in | It says what it checked and whether it works. Nothing is changed |
| **Integrations** | The three demo systems; one is paused. Pause and start Redaction Service |
| **Storage** | Two volumes with free space. Add one in a folder that isn't allowed: refused, nothing made |
| **Problems** | The prepared examples: a document that couldn't be stored, one that is stuck, a missing one |
| **Problems:** the checks | What Vero depends on, each with its state; a diagnostic file can be downloaded |

---

## Platform operations

Looks after the running system. No documents. Needs `Set-SDV-DevKeycloak.ps1` run again (see Start).

| Try | What should happen |
|---|---|
| **Health** | Every part with its state: database, key service, sign-in, storage |
| **Audit:** filter by person or kind, export | Who did what and when; never a document's content. Your export is listed too |
| **Overview, Storage, Problems** | Counts that fit the demo data |
| Seal nothing and stop nothing on purpose | If you want to see a failure, tell me first: sealing Vault stops every document until you unseal it |
