# Design review: the QoL work, as a whole

Many quality-of-life features went in one at a time (bulk actions, launch templates, inline answers,
background mode, tiles, backup parts, app lock). Each was tested on its own; this pass looks at them
together, as someone using the app would: Home and its sheets, Hosts, Settings and its pages,
tailnets and tiles, notifications, the widget and launcher shortcuts. The terminal screen and its key
bar are out of scope here (terminal-controls.md).

Rex is the source of truth for words: its states are Working, Blocked, Failed (`error`), Done and
Idle (rex-parity.md, "Working without a record").

Each finding: where it is, what a person hits, and the fix. **P1** is fixed in this pass, **P2** is
recorded in [backlog.md](backlog.md) as not worth the churn yet.

## P1: fixed

| # | Finding | Where | What a person hits | Fix |
| --- | --- | --- | --- | --- |
| 1 | Settings' pages have no way back to Settings | Settings › Background and notifications, Appearance, Security, Quick replies, Diagnostics | Open Settings, then Appearance, change the theme, press the back arrow or Back: the home, not Settings. To change one more setting, ⚙ and find the row again. The host and tailnet pages under Notifications already had one, back to Notifications | Each of these pages, opened from Settings, has the sheet's back arrow, which goes back to Settings; swiping down still closes it. Opened from anywhere else (the composer's Edit quick replies, a notification), no arrow. Backup and Import hosts open from the app's root (RexApp) and Terminal controls from its own pages: still to do (backlog) |
| 2 | The Notifications page buries its own switches | Settings › Background and notifications | "Tell me when" (which alerts) came after six Android checks, a row per tailnet, a row per host and three tile rows: on an API 30 phone, four screens down | Order now: Background alerts, Tell me when, While I am looking, Progress bars, then Android's checks, tailnets, hosts, tiles, sound and vibration, Reset |
| 3 | The notification switches use other words than Rex and the home | Notifications' Tell me when, the channel names, the Reset confirm | The home and the widget say blocked, failed, done; the switches said "A program waits for me" and "A program finishes", the channel "Finished", the reset "Waiting, failed, done" | "A program is blocked on me", "A program fails", "A program is done"; channel "Done"; reset "Blocked, failed, done". Rex's words, as the home's chips and counts |
| 4 | Two Connect all that do different things | Home ⋮ and the empty Needs you / Working / Activity list | The empty list's Connect all also retries a host that failed or keeps redialing; the ⋮'s connected only hosts never dialed, and was gone while every host was dialed and one had failed | One Connect all: every host the filters do not hear (not connected, failed, or redialing with a problem); a failed one is retried now |
| 5 | Two Tailnet items that go to different places | Home ⋮ › Tailnet and Settings › Tailnet | Once joined, Settings' row opens the tailnet's sheet (state, Connect or Disconnect, its page); the ⋮'s opened the join screen | The ⋮'s Tailnet does what Settings' does: the sheet once joined, the join screen before |
| 6 | The host sheet's "New tab from template" opens New tab | Host sheet; Settings › Backup's preview | The item opens the same chooser as every New tab (Blank tab first, then templates); the label suggests templates only. The backup preview called a host's templates "New tab from template" too | The host sheet says New tab; the backup preview says Launch templates |
| 7 | The host sheet's middle card mixes making and looking | Host sheet | New session, New tab, Collapse, Collapse all sessions, Expand all sessions and Mark all as seen on one card of six | Two cards: New session and New tab; then the folds and Mark all as seen. Same items, same order, so the row's TalkBack actions are unchanged |
| 8 | Settings › Security's row hides the screenshot block | Settings | With the lock on and screenshots blocked, the row said only "Locked with the phone's lock" | "Locked with the phone's lock · Screenshots blocked" when both are on |
| 9 | TalkBack: sheet rows are not said as buttons, switches or selected | Every sheet (`SheetItem`), the session switcher, the host dialog | A row is read as its text with no role; an On/Off row is not said to be a switch; the session switcher's current session (a check) and the host dialog's Password / Private key choice are not said to be selected | Rows are buttons; On/Off rows are switches (Background alerts and Notifications' switches, Security's two); a marked row is selected; Password and Private key are a pair of radio buttons to TalkBack |
| 10 | Docs describe the old group names | rex-parity.md "What the phone does now", home.md's sheet lists | Waiting, Running; the session sheet's "New tab from template" | Updated with this pass |

## P2: recorded in the backlog

| # | Finding | Where | What a person hits | Proposed fix |
| --- | --- | --- | --- | --- |
| 11 | On/Off rows are text, not Material switches | Notifications, Security, host and tailnet pages | A setting reads "On" at the row's end; Material 3 draws a switch. TalkBack now says switch (9), the look is unchanged | A `SheetSwitch` row with an M3 `Switch` in place of the detail; touches every settings test that reads "On"/"Off" |
| 12 | The tab sheet puts Open and Rename above Approve and Deny | Tab sheet on a blocked tab | The most pressing thing is second. The row's own buttons and the tree already lead with them, so the cost is low | Answers first when there are any, then Open, Rename |
| 13 | The host sheet's New session is greyed out offline; + offers "Connect & create" | Host sheet vs the bottom bar's + | Two answers to "make a session on a host that is away" | New session connects and creates, as + does (and New tab already does) |
| 14 | Settings' Import hosts also exports, Backup and Security are cards of one | Settings | "Import hosts" holds Save hosts to a file and Share hosts; two cards of one row each | "Import and export hosts" beside Backup on one card |
| 15 | The host dialog's "join the tailnet (tap here)" is text with a tap | Host dialog | Not said as a button; no ripple | A TextButton "Join tailnet" under the note |
| 16 | A tab's status words still say "waiting for approval" | Tab rows, Status details | The chips and captions say Blocked; the row says waiting | "blocked: approval", "blocked: question", "blocked: login" if Rex's own clients word it so (unconfirmed) |
| 17 | The Notifications page is one long list | Settings › Background and notifications | Even reordered (2), it holds eight cards | Split Android's checks and the per-host and per-tailnet pages into "Background mode ›" |

## Second wave: five P2 items, done

| # | Was | Now | Test |
| --- | --- | --- | --- |
| 11 | On/Off rows ended in the words "On" or "Off" | A Material 3 switch (`SheetItem`'s `checked`), the row the toggle | DesignReviewTest.aSwitchRowIsASwitchToTalkBack |
| 12 | A blocked tab's sheet had Open and Rename above Approve and Deny | Approve, Deny, Reply, Interrupt first, then Open and Rename; the row's TalkBack actions follow | DesignReviewTest.aBlockedTabsSheetLeadsWithItsAnswers |
| 13 | The host sheet's New session was greyed out offline | Enabled, saying "Connect & create", as +'s picker; it connects and makes the session (greyed only while the host reconnects) | MultiHostTest |
| 15 | "join the tailnet (tap here)" was text with a tap | The note, then a "Save and join tailnet" button | HostDialogQolTest (emulator) |
| 1 | Backup, Import hosts and Terminal controls closed to the home | Opened from Settings, their first page has the back arrow to Settings (`HomeRequest.Settings` for the two the app's root shows) | DesignReviewTest.everyPageOpenedFromSettingsGoesBackToIt |

Left: 14 (Import and export hosts beside Backup), 16 (blocked words on rows) and 17 (a Background mode page), too big for the upstream PR.
