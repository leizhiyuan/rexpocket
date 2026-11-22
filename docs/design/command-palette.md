# Command palette

A pop-up over the terminal screen with a search field and one filtered list of everything the phone
can do there and everywhere it can go: Rex's command palette, as far as a phone client can perform it
through Rex's API. It is not a page: it opens over the pane and closes when a row runs.

## Does it fit a phone?

Yes. Rex's own iOS app has one: the docs list "every action in the Rex for iOS command palette"
([iOS Actions](https://www.superlogical.com/rex/docs/reference/ios-actions)), including
`client.palette.toggle` (Command Palette…), and its settings page has a Key Bar entry "Command
Palette - learn how to open commands and use modifier keys"
([iOS Settings](https://www.superlogical.com/rex/docs/reference/ios-settings)). On the Mac it is
`shift+cmd+p` ([Mac Actions](https://www.superlogical.com/rex/docs/reference/mac-actions)).

What it adds over what RexPocket had:

- **One place, searchable.** The ⋮ menu holds the actions, the title sheet the tabs and panes, the
  session sheet the sessions. Each is a tap away, but finding one means knowing which sheet it is
  in. The palette searches all of them at once: type `spl` for Split, `logs` for the tab named logs
  in any session.
- **A hardware keyboard.** With a keyboard on the desk (or a phone docked to a screen) nothing on
  the terminal screen was reachable from the keys. The palette opens with a chord and is driven by
  the arrows, Return and Escape.
- **Tabs of other sessions.** The title sheet lists only the session on screen; the palette lists
  the tabs of every session the phone has loaded, so another session's tab is one search away.

## What is in it

Rows are of three kinds, each row with a label and, at its end, what it is (its group).

| Kind | Rows | What a row does |
| --- | --- | --- |
| Action | Pane: Release or Take over, Split right, Split below, Next pane, Previous pane, Rename pane, Close pane (with a confirmation, only when the tab has another), Copy pane ID. Tab: New tab, Next tab, Previous tab, Rename tab, Close tab (confirmed). Session: New session, Next session, Previous session, Rename session, End session (confirmed). Phone: Paste, Send a file, Show/Hide keyboard, Full screen, Bigger text, Smaller text, Reset text size, Back to hosts. | What the same row of the ⋮ menu does, or for the rows only here: the Rex call (`session.focus_block` through the app's own pane switching, `session.attach` for a session) or the phone's own action. |
| Tab | Every tab of every session the phone has loaded, named as the home list names it, with its session and its program's state. The tab on screen is marked. | Shows that tab: in this session by switching tabs, in another by opening that session on that tab. |
| Session | Every session on the host. | Opens it, on the pane the phone showed there last. |

Rows that cannot act now are left out, as in the ⋮ menu: no pane rows while a closed pane's note is
on screen; Close pane only with two panes or more; Next/Previous only when there is somewhere to go.

Not in it, with why (the full list is in [rex-parity.md](rex-parity.md)):

- Rex's own client actions that need a desktop window: floating panes, Move Tab to New Window,
  Balance Splits, Resize Pane, Zoom (the phone shows one pane at a time already).
- Split left and split up: `session.new_split` takes a side, but the phone's split always puts the
  new pane after; the two directions it has are kept as they are in the ⋮ menu.
- The configuration file's custom actions (`rex.action`, run with `client.action`, listed by
  `client.keymap`), and the prompts they can open (`client_prompt_requested`). They belong here
  and are the next step; see rex-parity.md for the work it takes.
- Switching to another host: the terminal screen belongs to one host. Back to hosts goes to the
  list, where the hosts are.

## Ranking

The query is split on spaces; every word has to match the row's label, its group or its detail
(session name, state). A row's score is the sum of its words' best matches:

| Match | Score |
| --- | --- |
| the label starts with the word | 100 |
| a word of the label starts with it | 80 |
| the label contains it | 50 |
| its letters appear in order in the label (`spr` in Split right) | 20 |
| the group or detail contains it | 10 |

Higher scores first; equal scores keep the order below. With nothing typed the list is in this
order: actions (Pane, Tab, Session, Phone, as the ⋮ menu orders them), then the tabs of the session
on screen, then other sessions' tabs, then sessions. The ranking is `rankPalette` in core
(`Palette.kt`), tested on its own.

## Opening it

- **⋮ menu:** its first row, Command palette. The menu is where a person looks for what the screen
  can do, and the row is there in full screen too, where the ⋮ is the corner of the screen.
- **Keyboard:** Ctrl+Shift+P, and Meta+Shift+P (a Mac keyboard's Command key reaches Android as
  Meta), as `shift+cmd+p` on the Mac. The pane gives the chord to the screen before the terminal
  sees it, so it is not typed into the program. Ctrl+Shift+P has no byte of its own in a terminal
  (it is Ctrl+P's), so nothing a program could read is taken from it.
- No gesture: every gesture on the pane already has a job (terminal-controls.md, 2), and the ones
  left - a swipe from an edge - are the system's.

## Using it

- It opens as a bottom sheet with the search field at the top, focused, so typing starts at once;
  on a phone the screen keyboard comes up with it.
- The first row is selected. Up and Down move the selection, Return runs it, Escape closes the
  palette. A tap runs any row.
- A row closes the palette, then runs: a rename or a confirmation opens its dialog after the sheet
  has gone, as the ⋮ menu's rows do.

## How it relates to the rest

- **Home search** stays as it is. It filters the host list in place, folds and all, and it can
  reach hosts the phone is not connected to; the palette works on the connected host only. Home
  has the eye and the search, and its actions (add a host, settings) are one tap from the list.
  The palette belongs to the terminal screen, where the actions are.
- **The ⋮ menu** stays, unchanged except for its first row: it is the list to browse; the palette
  is the list to search.

## Tests

- `PaletteTest` (core): the ranking rules above.
- `CommandPaletteTest` (app/src/sharedTest, JVM and emulator): the palette opened from the ⋮ menu
  and from Ctrl+Shift+P on the pane, typing narrows and orders the rows on screen, Return runs the
  selected row and Down moves the selection, a tab of another session opens that session on it, a
  destructive row asks first.
- Screenshots of the open palette, portrait and landscape (`ScreenshotsTest`).
