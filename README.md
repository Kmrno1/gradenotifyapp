# BEU Grade Notifier

Chrome extension that watches the [my.beu.edu.az](https://my.beu.edu.az) student
portal and raises a desktop notification when a grade or absence count changes,
so you do not have to keep reloading the grades page.

## How it works

The portal has no public API, but its grades module is self-contained: a request
to `index.php?mod=grades` returns every semester's mark table, either as a full
page or wrapped in a `{"CODE":"1","DATA":"<html>"}` envelope. The extension
polls that one URL and diffs the result.

```
alarm (every N minutes)
  -> fetch index.php?mod=grades with the browser's own session cookie
  -> parse mark tables                          (src/parser.js)
  -> diff against the last snapshot             (src/diff.js)
  -> notify + store the new snapshot            (src/checker.js)
```

Two consequences of polling inside the browser are worth calling out:

- **Your password is never involved.** The request rides on the session cookie
  of the browser you are already signed in with, so there is nothing to store
  and nothing to leak. When the session expires the extension reports
  `logged-out` and waits instead of trying to sign in.
- **Only the mark tables are read.** No page is rendered and nothing is sent
  anywhere; the snapshot lives in `chrome.storage.local` on your machine.

### Mark columns are read from the table header

Column layout differs between semesters. Up to 2024-2025 the tables carry
`DVM`; from 2025-2026 that is gone and `SEM` plus an optional `LAB` appear:

| Semester          | Mark columns                                    |
| ----------------- | ----------------------------------------------- |
| 2024-2025 1 and 2 | `SDF1 SDF2 TSI DVM SSI ƏI TI ORT`               |
| 2025-2026 1       | `SDF1 SDF2 SEM LAB TSI SSI ƏI TI ORT`           |
| 2025-2026 2       | `SDF1 SDF2 SEM TSI SSI ƏI TI ORT`               |

So each table's own header row decides the mapping. Addressing cells by fixed
index appears to work and silently reports one exam's mark under another
column's name.

### What counts as a change

Any mark whose value differs from the stored one, including the usual case of a
blank cell gaining a value:

| Stored  | Now  | Result                       |
| ------- | ---- | ---------------------------- |
| *blank* | `47` | notify — grade entered       |
| `44`    | `47` | notify — grade corrected     |
| `47`    | `47` | silent                       |

The **first** successful check only records a baseline: with no previous
snapshot every existing mark would look new and produce a notification for
grades you already know about. Alerts begin from the second check.

A course appearing for the first time with no marks yet is registration, not
grading, and stays silent. `Qaib` (absences) is in the same table, so
attendance changes are reported too; that can be switched off in the popup.

## Install

1. `git clone` this repository.
2. Open `chrome://extensions`, enable **Developer mode**.
3. **Load unpacked** and select the repository folder.
4. Sign in to [my.beu.edu.az](https://my.beu.edu.az) in the same browser.
5. Open the extension popup and press **Check now** to record the baseline.

The extension has no build step: the sources are loaded as they are.

## Settings

Available from the popup: check interval (15/20/30/60 min), absence alerts,
and an on/off switch. Default is every 20 minutes — polling much more often
adds load to the portal for no practical gain, since marks are entered by hand.

## Development

```sh
npm install
npm test     # node:test, no browser needed
npm run lint
```

`src/parser.js`, `src/diff.js` and `src/checker.js` hold the logic and are
covered by tests; `src/background.js` is the thin Chrome glue (alarms,
notifications) and `src/popup.*` the UI.

Tests run against `test/fixtures/`, a real grades page reduced to its mark
tables. Course codes and names are kept because the layout quirks matter,
while all marks and absence counts are randomised and every identifying
element of the page was removed.

## Scope

Unofficial and unaffiliated with Baku Engineering University. It reads only
the grades page of the account already signed in, on a human timescale.

## Possible next steps

- Cover the `mod=elan` (announcements) and `mod=ejurnal` (e-journal) modules.
- Phone notifications, which need a server component to hold the session.
- GPA calculation from `AKTS` and `ORT`, which are already parsed.
