# Search

`Search` on the Libraries bar opens a search screen across all libraries, with the field focused. Each library grid also has its own `Search` field that filters that library.

## Sub-features

- `search-global` finds movies and series across libraries as you type.
- `search-empty` shows `Type to search` before input and `No results for “…”` for no match.
- `search-library` filters one library's grid.

## How to get to it (user POV)

- Libraries → `Search` (top right).
- Libraries → a library → the `Search` field above the sort chips.

## Driving it with finimal-verify

Preconditions:

- Launched as `test`. Libraries screen shown.

- **Open.** Run `$V click Search` and `$V wait "Type to search"`. Run `$V shot search-open`.
- **Match.** Run `$V type subs` and `$V wait "Subs Test"`. Only `Subs Test (2022)` shows. Run `$V shot search-match`.
- **No match.** Run `$V key ctrl+a` and `$V type zzzz`, then `$V wait "No results"` and `$V shot search-none`.
- **In a library.** Run `$V key Escape`, `$V click Movies`, `$V click Search` (the field), `$V type hevc`, and `$V wait "HEVC Test"`. The other two titles are gone. Run `$V shot search-library`.

## Gotchas

- On the Libraries screen, `$V click Search` hits the bar button. Inside a library, the same text is the field label, so it focuses the field.
- Results refresh after a short pause in typing. Wait for the text instead of taking a screenshot right after `type`.
