# Accounts and PIN

Several users of one server share the device. Settings → `Accounts and PIN` lists the saved accounts with `(current)`, `Add account`, and `Set PIN`/`Remove PIN` for the current one. With more than one account, a cold start opens "Who's watching?", and a PIN is asked when switching to an account that has one.

## Sub-features

- `accounts-list` shows `test (current)` and `Add account`.
- `accounts-add` logs a second user (`test2`) in from `Add account`. The Server field is locked to the current server.
- `accounts-switch` switches users from the list and asks for the PIN if one is set.
- `accounts-pin` sets and removes a PIN (`PIN` dialog, `Wrong PIN` on a mismatch).
- `accounts-start` shows "Who's watching?" at a cold start with two accounts.

## How to get to it (user POV)

- Libraries → gear icon → `Accounts and PIN`.
- Libraries → the round account avatar (top right).

## Driving it with finimal-verify

Preconditions:

- Launched as `test`. Libraries screen shown.

- **Open.** Run `$V xy 1364 132` (gear) and `$V wait "Accounts and PIN"`. The Account section reads `test · http://localhost:8097`. Run `$V click "Accounts and PIN"`, `$V wait "Add account"`, and `$V shot accounts-list`. The list shows `test (current)`.
- **Add test2.** Run `$V click "Add account"`, `$V click Username`, `$V type test2`, `$V click Password`, `$V type test`, `$V click "Log in"`, and `$V wait Movies 20`. Then go back to Accounts and run `$V shot accounts-two`. The list has both users.
- **PIN.** Run `$V click "Set PIN"`, `$V type 1234`, and `$V click OK`. The row shows `PIN`. Switch to `test` and back. A `PIN` dialog appears, a wrong value shows `Wrong PIN`, and `1234` switches. Run `$V shot accounts-pin`.
- **Restore.** Run `Remove PIN` on any account that got one. `$V stop` deletes the run's settings, which removes the local accounts. Tokens stay on the server as devices named after this machine.

## Gotchas

- The PIN and the account list are local to the run's settings file. The server never sees them, so the proof is the dialog behavior plus `settings.json` (`accounts` has a `pin` of the form `salt:hash`).
- The gear and the avatar are icons without text. Use the coordinates from `SKILL.md`. They hold for the default 1280x800 window only.
- Only the Open step was driven while this map was written. The add, PIN and switch steps follow `Screens.kt` (`AccountsScreen`, `PinDialog`) and have not been run yet.
