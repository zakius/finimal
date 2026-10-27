# Log in

A logged-out start shows the Login screen: `Server`, `Port` (8096 by default), `Username`, `Password` with `Show`, and `Log in`. The app tries https before http and never falls back to http after a certificate error. A success opens Libraries and saves the account.

## Sub-features

- `login-ok` logs in and opens Libraries.
- `login-wrong` shows `Wrong username or password` on a 401.
- `login-port` rejects a port outside 1–65535 with an inline message.

## How to get to it (user POV)

- First start, or after Settings → `Log out` with no other accounts.

## Driving it with finimal-verify

Preconditions:

- Launched with `$V launch --logged-out`, which prints `ready` once `Password` shows.

- **Fill.** Run `$V click Server`, `$V type localhost`, `$V click 8096`, `$V key ctrl+a`, `$V type 8097`, `$V click Username`, `$V type test`, `$V click Password`, and `$V type test`. Then run `$V shot login-filled`.
- **Log in.** Run `$V click "Log in"` and `$V wait Movies 20`, then `$V shot login-done`. Libraries shows `Movies` and `Shows`.
- **Saved.** `$V env` now shows the app's `USER_ID` and `TOKEN`, and `$V doctor` reports `token valid`. The run's `scratch/config/finimal/settings.json` has `baseUrl` `http://localhost:8097`.
- **Wrong password.** In a new `--logged-out` run, use password `nope`. Run `$V wait "Wrong username"` and `$V shot login-wrong`.

## Gotchas

- Port `8096` is a prefilled value, not a placeholder. Select all before you type, or the field reads `80968097`.
- `Log in` is dark text on a light button. The helper's second OCR pass reads it. If `click "Log in"` fails, check `$V text` for the line.
- The https attempt against the plain-http test server adds a moment before the http retry. Give `wait` 20 s.
