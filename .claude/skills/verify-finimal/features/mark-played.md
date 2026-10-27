# Mark played or unplayed

A right-click on a movie or episode opens its menu. **Mark played** and **Mark unplayed** set the played flag on the server, and the poster's check mark follows.

## Sub-features

- `menu-open` opens the item menu with a right-click on a movie poster or an episode row.
- `mark-unplayed` clears the played flag on the server and removes the poster's check mark.
- `mark-played` sets the played flag on the server and shows the check mark.

## How to get to it (user POV)

- Libraries → `Movies` → right-click a poster.
- Libraries → `Shows` → a series → a season → right-click an episode row.

## Driving it with finimal-verify

Preconditions:

- Launched as `test`. Libraries screen shown.
- Read the start state: `$V api /Users/<USER_ID>/Items/54f15171b8f4c52c08819d2f3d376614 | jq .UserData.Played`. The menu shows the opposite action of that value.

- **Open the library.** Run `$V click Movies` and `$V wait "Subs Test"`. The grid shows three posters. Played ones have a check mark at the top right.
- **Open the menu.** Run `$V rclick "Subs Test"` and `$V wait "Mark unplayed"` (or `"Mark played"`). The menu lists `Start over` and the mark action. Run `$V shot mark-played-menu`.
- **Mark unplayed.** Run `$V click "Mark unplayed"`, then `$V shot mark-played-after-unplayed`. The check mark on `Subs Test (2022)` is gone. `$V api …/Items/54f15171b8f4c52c08819d2f3d376614 | jq .UserData.Played` prints `false`.
- **Mark played.** Run `$V rclick "Subs Test"`, `$V click "Mark played"`, and `$V shot mark-played-after-played`. The check mark is back, and the server prints `true`. This step also restores the shared server's state.

## Gotchas

- The right-click also works on the title under the poster, which is where OCR finds the text.
- While the menu is open, it covers the title, so `$V text` no longer shows `Subs Test`.
- A check mark alone is not proof: the app keeps play state locally. Read the server flag.
