# Browse libraries

The app opens on Libraries, which lists the server's movie and TV libraries. A library opens a poster grid with sort chips (`Name`, `Date added`, `Release date`) and an `Unplayed` filter. A series opens its seasons, and a season opens its episode rows with progress and a played mark.

## Sub-features

- `browse-libraries` lists `Movies` and `Shows` on the start screen.
- `browse-grid` shows posters with titles and played check marks.
- `browse-sort` reorders the grid by name, date added or release date.
- `browse-unplayed` hides played items, or shows `Nothing unplayed here`.
- `browse-series` lists seasons, and a Continue button when the series has unplayed episodes.
- `browse-season` lists episodes as `1. Episode 1` with runtime and `played`.
- `browse-back` returns with Esc, the back arrow, or the mouse's back button.

## How to get to it (user POV)

- Start the app while logged in.
- Click a library, a series poster, then a season.

## Driving it with finimal-verify

Preconditions:

- Launched as `test`. Libraries screen shown.

- **Libraries.** Run `$V shot browse-libraries`. The OCR has `Movies` and `Shows`, and the bar has `Search`.
- **Grid.** Run `$V click Movies` and `$V wait "Date added"`. Three titles show: `HEVC Test (2021)`, `Subs Test (2022)`, `Test Movie (2020)`. Run `$V shot browse-grid`.
- **Unplayed filter.** Run `$V click Unplayed` and `$V shot browse-unplayed`. With every item played, the grid shows `Nothing unplayed here`. Run `$V click Unplayed` again to clear the filter.
- **Series.** Run `$V key Escape`, `$V click Shows`, `$V wait Dette`, `$V click Dette`, and `$V wait "Season 2"`. Run `$V shot browse-series`.
- **Season.** Run `$V click "Season 1"` and `$V wait "Episode 1"`. The rows read `1. Episode 1`, `2. Test Show S01E02` and `3. Test Show S01E03`, with `6:00` and `played`. Run `$V shot browse-season`.
- **Back.** Run `$V key Escape` three times and `$V wait Movies`. You are on Libraries again.

## Gotchas

- OCR reads `S01E02` as `SO1E02`. Match `Episode 1`, `E02`, or the row number.
- The sort chip and filter state stay per screen while it is on the stack. Leave and reopen the library to reset them.
- Lists open from a saved copy first and then refresh. A screenshot right after the click can show the old list. Wait for the expected text.
