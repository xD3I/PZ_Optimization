# PZ OPTIMIZATION menu: critique and redesign (2026-10-19)

Method: Impeccable 4.0.4 `critique`, dual-agent. Assessment A (design review: the four screenshots, the three Lua files,
the pre-10-18 options tab in git) and Assessment B (Impeccable's detector plus mechanical scans) ran as separate agents.
The detector reads only web files (`.html .css .jsx .tsx .vue ...`, `scripts/detector/node/file-system.mjs:26-30`). It
dropped the `.lua` files without a word and exited 0, so it gave no evidence either way. Assessment B's
mechanical scans (fonts, colours, setting counts, tip lengths, joypad handlers) stand in for it. There was no browser
pass: the menu is in-game ISUI with no URL.

Target: the main-menu / pause-menu screen "PZ OPTIMIZATION" as released on 2026-10-19 (HEAD `adef1841`),
`src/lua/client/pzopt/pzopt_mainscreen_menu.lua` (`menu`), `pzopt_optimizations_layout.lua` (`layout`),
`pzopt_optimizations_options.lua` (`options`). Line numbers are HEAD's unless marked "worktree". Screenshots:
`~/Zomboid/Screenshots/pzopt-options-1..4.png` (5120x2160, 00:52). Shot 1 is the default Simple view of
Enhancements > Lighting and shadows > God rays. Shots 2-4 show the same page in the Advanced view (16 rows plus "Show 1 more (Everything view)"), with three different rows selected.

Nothing below was verified in game. Runs were not allowed for this pass. The prototype in section 3 has been parsed
(luajit), and the one Java test it touches passes. That is all the checking it has had.

---

## 1. Critique

### Design health: 17 / 40 (Poor: a major UX overhaul is needed)

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of system status | 2 | The value is in the row and the restart count is shown. But the detail's tick box is always filled, so it contradicts the row (P0 below). |
| 2 | Match the real world | 1 | "bake", "budgets", "chunk textures", "Key: godRays", "Java: read by pzopt.Config only". The sidebar says OPTIMIZATIONS / ENHANCEMENTS / PROFILER while the layout calls them Performance / Visuals / Tools. |
| 3 | User control and freedom | 2 | DEFAULT, Reset category and the Stock preset exist. But the view resets to Simple every session, the sidebar cannot be collapsed, and "Show N more" jumps straight to Everything. |
| 4 | Consistency and standards | 1 | Click selects and a second click toggles, while the stock Options screen toggles at once. The tick box is labelled "On" whatever its state. "Off" uses the same orange as the Enhancements accent and the warnings. |
| 5 | Error prevention | 2 | Presets, uninstall and restart ask first, but a second click on a selected row flips a setting silently. |
| 6 | Recognition rather than recall | 1 | Simple hides 408 of the 497 settings. A description shows only after a click. The view switch lives only on the home page, and pages never say which view is active. |
| 7 | Flexibility and efficiency | 2 | Ctrl+F search, LB/RB, LT/RT and export / import are good. There is no hover preview, a change takes two actions, and there is no "changed only" filter. |
| 8 | Aesthetic and minimalist design | 1 | Shot 1 has two rows in a list about 1,900 px tall. Description lines run about 190 characters. Developer metadata sits in every detail, and "God rays:" is repeated on 16 rows. |
| 9 | Error recovery | 2 | DEFAULT / Reset / Stock preset / Uninstall exist, but there is no undo and no "what did I change". |
| 10 | Help and documentation | 3 | Rich descriptions, the before / after clips, Fix a problem and R3 help are all there. But the texts are walls: the median tip is 211 characters, p90 is 526 and `gcHeap` is 1,564. |

### Design specificity

The content is unmistakably this mod's. Before / after clips come from the same save, spot and hour. There are
per-resource cost bars, measured numbers in Fix a problem (153 → 235 fps, 46 → 58 fps), and an honest "Next launch /
In use now / Default" line. Nothing else on the Workshop has this.

The interaction and the visuals are a generic engineer's master-detail app:
- one font size (UIFont.Small: 83 uses, Medium 22, Large 5),
- a four-level tree,
- each value about 1,300 px from its label,
- developer metadata as the last thing in every description.

Take the clips away and it could be any debug panel. The menu does not yet carry the confidence that the clips and the
measurements earn.

### Overall impression

The data model underneath is excellent and complete. The screen built on it makes players work for every fact. A
setting's meaning, cost and state appear only after a click, in a pane far from the row. The default view shows almost
nothing, and the one control that would show more is two levels away on the home page. The single biggest opportunity
is to put the information players asked for in the row itself, so they can scan and compare without clicking. The
second is to make the detail follow the mouse again, as the first version did.

### What works

1. **The before / after clips and Fix a problem.** They show proof and explain causes. Keep them and make them easier to reach.
2. **A complete controller model.** It has an explicit focus order, LB/RB between sections, LT/RT paging, a hint bar and a
   restart prompt on Y. Few PZ mods have one. The fix below changes one step of it, not the model.
3. **Honest state.** It says whether a setting is live or applies at the next launch, and shows pinned-by, the mod
   compatibility notices and a "Restart to apply N" counter with RESTART NOW.

### Priority issues, by player impact

**P0-1. Every on/off tick box in the detail pane renders checked.**
- Where: shot 1, bottom-left of the detail (the filled amber box labelled "On" under the chart, while the God rays row says
  Off). Shot 4 is the same: "sun under porch roofs" is Off and the box is filled.
- Code: `menu:1019` is
  `get = function() return kind == "bool" and (settingNext(k)) == "true" or showValue(...) end`. For an Off switch the
  `and` gives `false`, so the `or` returns `"Off"`, a truthy string. `PzoptMenuControl:prerender` (`if on then drawRect`)
  then always fills the box.
- Why it matters: players cannot trust the one control that says whether a feature is on. That explains part of "it is
  almost impossible to understand what does what". A filled box beside a row that says Off reads as broken.
- Fix: return a boolean for `bool`, and label the box "Enabled", not a static "On". Prototyped (section 3).

**P0-2. The details follow the click, not the mouse, and a change takes two clicks.**
("I have to click through every single option one by one just to see its detailed info")
- Where: the middle list in all four shots. Hover only tints the row (`menu:1610`). `PzoptMenuList:onMouseUp`
  (`menu:1656-1669`) selects on the first click and toggles on a second click on the same row.
- The first version (the options tab, `18b55b49`) refreshed its preview panel for the row under the mouse.
- Fix:
  - Hover-select after a short dwell (about 150 ms), and only while the mouse is moving, so a resting mouse never
    fights the controller or the wheel.
  - Make the value a target in the row: a tick box beside On / Off, `< value >` arrows around a choice. One click on it
    changes the setting.
  - Prototyped (section 3).

**P1-1. The default view hides 82 % of the settings, forgets the player's choice, and puts its switch on another page.**
("I can't change anything except for master-switches - it just doesn't show any options")
- Where: shot 1. The list shows "God rays", "God rays: brightness (%)" and "Show 15 more (Everything view)" in an otherwise
  empty panel. The sidebar counts read 7 / 11 / 13 / 7 here and 33 / 66 / 33 / 19 in shots 2-4, and nothing says what
  they count.
- Code:
  - `VIEW = { level = 1 }` (`menu:337`) is never saved.
  - The filter is `shownKeys` (`menu:350`).
  - The only view switch is `viewRow` (`menu:709`), inserted under "Tools" on the home page (`menu:764`).
  - The "Show N more" row (`menu:1070`) jumps to Everything (tier 3, the tuning numbers), not to Advanced.
- Structure:
  - The category page (`catPage`, `menu:855`) lists only the master switch and subcategory nodes. A player who
    stops at a category sees nothing but master switches, which is exactly the Workshop report.
  - In Simple, "Drawing the horde" and "Horde first sight" show 0 rows, and so do three "More" subcategories.
  - Simple shows 89 of 497 settings (`layout:151`). The per-category counts are in the appendix.
- Fix (section 2): put a category's settings on its own page with the subcategories as headings, make the view a
  persisted toggle on every page, and let "more" expand in place.

**P1-2. Controller focus expands the sidebar ("you can't keep tabs closed, they always expand").**
- Code:
  - Each D-pad step in the sidebar calls `focusSide` (`menu:2730`), which calls `selectNode` (`menu:2736`).
  - `sideRows` (`menu:1932`) then expands the selected group's categories and the selected category's subcategories
    (`menu:1945`, `1950`).
  - Walking down OPTIMIZATIONS therefore opens 8 categories one after another. Each one re-lays the rows under
    the cursor and loads its page.
- Fix: the sidebar cursor moves without opening anything. A opens a node, and B or left collapses it. Once
  subcategories leave the sidebar (section 2), it has two levels and never grows past about 20 rows.

**P1-3. Settings players need cannot be found; the Java heap is the example.**
- Code:
  - The section "Java memory and garbage collector" (`options:740`) matches no subcategory and no `home` prefix
    (`layout:143-148`).
  - `settingsTree` therefore drops `gcHeap` and eight related keys into the last Performance category's
    "More" (`menu:300-316`).
  - It is tier 2, so the default Simple view never lists it.
  - The category was named "System, sound and mods" (`layout:89`), with no "memory" in sight.
- Fix: give it a named subcategory, list it in Simple, add a Fix a problem entry and a home-page row, and add search
  synonyms (RAM, heap, Xmx, GB). The first three are prototyped.

**P2-1. Text size, line length and focus visibility at 5K.**
- Rows, the sidebar, controls and buttons all use UIFont.Small: row height `fontH(Small) + 10` (`menu:1497`), a
  ~22-26 px line on a 2,160 px screen. The detail body uses ISRichTextPanel's default NewSmall in grey 0.7.
- The detail pane takes 58 % of the content width (`menu:2025`), so description lines run about 190 characters (shot 1,
  right half). Comfortable reading is 65-80.
- The list is 42 % wide, and its value sits flush right (`drawTextRight`), about 1,300 px from a label that is itself
  ~400 px wide.
- The controller focus is a 1 px border over an 18 % tint (`menu:1608`, sidebar `menu:1810`).
- Fix:
  - Use Medium rows from 1,440 px of screen height (or follow the game's font-size option).
  - Cap the description at about 80 characters.
  - Put the value column next to the label column.
  - Draw a 3 px focus ring.

**P2-2. Every description reads like a changelog.**
- In shot 1, God rays opens with a 600-character paragraph. It is followed by five metadata lines: "Applies at once; no
  restart needed.", "Next launch: Off In use now: Off Default: Off" (the four-space gaps at `menu:937` collapse in rich
  text), "Key: godRays", "Released 2026-09-27. Java: read by pzopt.Config only", and the clip's caption.
- Shot 4 says "Applies on the next launch." in the tip and "Takes effect on the next launch." again right under it.
- Only 310 of 497 tips have a first sentence of 120 characters or less. Even those usually describe the mechanism
  ("Car physics runs in fixed 10 ms steps."), not what the player sees.
- Fix: an authored one-line `short` per setting (start with the 89 Simple ones), the long text under it, and the
  key, Java classes and release date shown only in the All view.

### Cognitive load: 6 of 8 checks fail (high)

The checks that fail:
- single focus: the three panes and the animated clips compete;
- chunking: the home page has 18 rows; God rays in Advanced has 16 flat rows plus a "Show 1 more" row;
- visual hierarchy: everything is Small; the label and its value sit far apart;
- minimal choices: see the list below;
- working memory: players must remember where a setting lives and which view is active, and comparing two settings
  means clicking each;
- progressive disclosure: the global, hidden filter shows 18 %, and 0-row subcategories still appear.

Grouping passes partly (the sidebar tree groups well; a subcategory page is flat). "One thing at a time" passes.

Decision points with more than 4 visible options:
- the home page (18 rows);
- the Optimizations group page (master, 8 categories, reset);
- the sidebar in shot 1 (14 rows);
- Zombies > On other cores (28) and Cheaper checks (27);
- the heap chooser (13 values, stepped one at a time).

### Persona red flags

- **Alex (power user):**
  - Two clicks per toggle, and the view resets every launch.
  - "Show more" skips Advanced.
  - The sidebar counts are totals, not "changed by me", and there is no "changed only" filter.
  - 497 settings, but no way to compare two without clicking each.
- **Sam (controller, low vision at 5K):**
  - Small text and a 1 px focus ring.
  - The sidebar re-lays itself under the cursor on every D-pad step.
  - The description scrolls only after the last control (`moveDetail`).
  - The tick box lies about the state.
- **Jordan (first-timer):**
  - The home page has no "start here". Recommended sits mid-page under "Presets", and Fix a problem sits under "Help".
  - Simple pages are almost empty.
  - Headings use jargon ("Bake budgets", "Sprite buffers").
  - Descriptions are walls of text that end in "Key: godRays / Java: ...".
  - Jordan asks for "the old menu back".

### Minor observations

- "God rays:" prefixes 16 of the 17 rows on the God rays page. Inside a subcategory the prefix is redundant; keep it in search results.
- Shot 4: "sun under porch roofs" plays the generic God rays clip (`clipOf` falls back to the section's clip), captioned
  "GOD RAYS OFF / ON", which does not show this setting.
- Settings without effect bars move the tick box and buttons up the panel (shots 2 and 4). This breaks the
  "controls keep their place" intent of `showDetail`.
- The effect axis "lowest / low / mid / high / ultra / max" reads like graphics presets. Nothing says it compares
  against the stock game when the setting is on.
- BACK sits at the far bottom-left, about 2,000 px from the content, and the "PZ OPTIMIZATION" title is a hidden link to home.
- Pause-menu entries overlapping other mods' entries is a layout bug, already fixed on the `esc-fix` branch; it is not
  covered here.
- Players compare the menu with Viewpoint's. We did not inspect Viewpoint for this pass (no browser, no game). The
  common thread in the comments is "everything visible, explained where it sits".

### Questions to consider

1. The clips are the best thing on the screen. Why are they behind a click and a SHOW CLIPS toggle instead of playing as
   the cursor passes a row?
2. Does any player need 497 settings in a menu? Should the tuning numbers live only in All (and in export / import
   text), with the menu built for the ~120 a person would change?
3. Optimizations ("the defaults were measured best") and Enhancements (opt-in visuals players want to try) are browsed
   the same way. Should Optimizations be one switch, presets and Fix a problem, and Enhancements the browsable gallery?

---

## 2. Redesign proposal

Goal: a player can scan a whole category, see what each setting does, what it costs and whether it is on, and change it
with one action, without ever clicking a row just to read it. A controller player can move through the sidebar without it
growing under the cursor.

Everything below uses the toolkit the screen already uses. The rows are drawn by `PzoptMenuList:prerender`, the detail is
`ISRichTextPanel` plus `PzoptMenuControl`, and the clips and bars come from `PzoptPreview`. No new widget classes from
the game are needed.

### 2.1 Information architecture: two levels in the sidebar, one page per category

```
 Sidebar (2 levels, never more)          Page = one category, subcategories as headings
 ------------------------------          -----------------------------------------------
 START HERE                              Enhancements > Lighting and shadows      [Common | All]
 OPTIMIZATIONS                 on        LIGHTING AND SHADOWS ENABLED                    [x]
 ENHANCEMENTS                  on        -- Per-pixel lighting ------------------------------
   Image quality          2 / 7          ...
 > Lighting and shadows   1 / 11         -- God rays ----------------------------------------
   Reflections and glass  0 / 13         God rays                                [ ] Off
   Atmosphere and detail  0 / 7          ...
 PROFILER                                + 15 more god-ray settings
 BENCHMARK                               -- Sun, moon and clouds ----------------------------
 UPDATE              up to date          ...
 MOD COMPATIBILITY CHECK
```

- **The category is the page.** `catPage` lists every subcategory as a `section` row followed by its settings; the
  master switch stays first. The subcategory nodes and their sidebar rows go away.
  - LT/RT, which page the list today, jump heading to heading.
  - The breadcrumb names the heading of the selected row.
  - `options_nav=perf/drive/1` keeps working by opening the category with the cursor on that heading.
- **Sidebar counts mean something.** "2 / 7" is changed from default / shown, and "off" stays for a category whose
  switch is off.
- **START HERE replaces home's top.** It holds:
  - Recommended / Stock / Low-end as three big rows, showing which one is applied;
  - Fix a problem;
  - quick rows for the four settings players ask about most: Java memory, Upscaler, frame cap / pacing, Mod
    compatibility. Each row is a real `settingItem` with its inline control.
  - Export / Import / Uninstall move under a "Manage" heading at the bottom.

### 2.2 The view: "Common | All", on every page, remembered

- Replace the global Simple / Advanced / Everything filter with a two-state toggle drawn on the breadcrumb line of every
  settings page. X on a controller toggles it.
- It is saved like `previewClips` (a `pzopt.Config` key, for example `menuView`, saved through UserOptions).
- **Common** (the default) lists the layout's `simple` keys under every heading. A heading with more ends in
  "+ N more <heading> settings". Activating that row expands that subcategory in place: the extra rows are indented and
  dimmed, and the expansion is remembered for the session.
- A heading with no common setting ("Drawing the horde", "Horde first sight") shows as one collapsed row:
  "Drawing the horde: 10 settings, defaults measured best". This replaces showing an empty heading.
- **All** shows every setting. The tuning numbers (today's tier 3) come last under each heading, after a dim
  "Tuning" sub-label.
- The key, the Java classes and the release date show only in All.

### 2.3 Row anatomy: scan and compare without clicking

```
 +----------------------------------------------------------------------------------------+
 | God rays                                        [ ] Off    GPU ++  VRAM +            * |
 |   Shafts of sun and lamplight through windows and doors, in dust, fog and rain.        |
 +----------------------------------------------------------------------------------------+
 | God rays: brightness                            < 100 % >                              |
 |   How bright the shafts are.                                                           |
 +----------------------------------------------------------------------------------------+
   label (Medium)                                  value      cost chips (vs stock)   changed
   one-liner (Small, grey 0.66)                    column     max 3 chips             dot
```

- **The value column starts right after the widest label of the page.** It sits at a fixed 55 % of the list instead of flush right.
  - A tick box draws beside On / Off, and arrows around a choice.
  - One click on the value changes it.
  - Right-click, or a click on the left arrow, steps a choice back.
- **The one-liner** is a new optional field, `short`, on each entry in `options` (what the player notices, 90
  characters at most). It is authored for the 89 Common settings first. Until then the row shows the label only; do not
  auto-cut the tip, because first sentences describe the mechanism.
- **Cost chips** come from `PzoptSettings.effects(key)`, the same data as the bars: up to three resources the setting
  moves, `+` / `++` / `+++` above stock in the Enhancements accent, `-` below stock in green.
- **The changed dot** marks a setting whose next-launch value differs from the build's default (`settingNext` vs
  `getPzoptOptionDefault`). The sidebar's "N changed" count comes from the same test.
- **Drop the repeated prefix.** Inside its own heading, "God rays: brightness (%)" shows as "Brightness (%)".
  `PzoptOptionLabel` keeps the full label for search and Fix a problem.

### 2.4 The detail pane: follows the mouse, short first, technical last

```
 +-------------------------------------------+
 |  [ stock clip ]      [ with the setting ] |   clips: only when this setting has its own
 |  GOD RAYS OFF         GOD RAYS ON         |   (KEY_CLIP), else the section clip is labelled
 +-------------------------------------------+   "Section clip: God rays"
 |  God rays                         (Medium)|
 |  Shafts of sun and lamplight through      |   the one-liner
 |  windows and doors.                       |
 |                                           |
 |  Cost vs stock   GPU  ++   VRAM  +        |   compact chart: only the moved rows,
 |  Applies at once.            [x] Enabled  |   one "applies" line, the control
 |                              [DEFAULT]    |
 |  ---------------------------------------- |
 |  Sunlight (and moonlight) falls through   |   the full tip, <= 80 characters a line,
 |  windows and open doorways into rooms...  |   scrolls on its own (RS on a pad)
 |                                           |
 |  All view only: key godRays / released    |
 |  2026-09-27 / Java: pzopt.Config          |
 +-------------------------------------------+
```

- **Selection follows the mouse** after a 150 ms dwell (the first step, prototyped). On a controller the selection
  follows the cursor as today.
- **The control and DEFAULT sit beside the summary,** at a fixed place, not under a chart whose height changes per
  setting. This fixes the jumping controls in shots 2 and 4.
- **One "applies" line.** Use "Applies at once." or "Applies at the next launch."; never both the tip's sentence and
  `RESTART_NOTE`. Strip the tip's trailing "Applies on the next launch." when it matches.
- **The pane's width is capped** at about 80 characters of the body font. The spare width at 5K goes to the list's value
  and cost columns.

### 2.5 Controller

- **Sidebar:** the D-pad moves the cursor only (`sideIndex` plus the focus ring). A opens the node and expands its
  categories, and left or B collapses it. An opened group stays expanded until the player collapses it. `focusSide` stops
  calling `selectNode`, and `sideRows` expands `self.openGroup`, not the selected node.
- **Page:** the D-pad moves rows, A changes the value (today's behaviour on a pad), X toggles Common | All, Y is DEFAULT
  on a setting, and LT/RT move heading to heading. RS scrolls the description without leaving the list. This removes the
  "description scrolls only after the last control" trap.
- **Focus:** a 3 px ring in the group colour plus the 4 px accent bar, on both the sidebar and the list.

### 2.6 Text scale

- **The base font follows the screen.** UIFont.Medium for row labels, values and the detail body when
  `getCore():getScreenHeight() >= 1440`, Small below. Headings go one step up. Compute it once in `createChildren` and
  keep it in `self.fontRow` / `self.fontBody`.
- **A "Text size: Normal | Large" row** in START HERE overrides the choice, saved like the view.
- **The screen frame is capped.** At or above 3,840 px wide the whole screen (sidebar plus page) is capped at about 3,000
  px and centred. The sidebar keeps 16-28 % of that, not of the 5K window.

### 2.7 Discoverability of settings like the heap

- **Memory gets its own subcategory** (prototyped): System, memory, sound and mods > Memory (Java heap), with
  `gcHeap` and `luaGcNoop` in Common.
- **The heap gets a START HERE quick row** showing the machine's RAM: "Java memory: Auto (4 GB of 32 GB)". The RAM is
  read where the Java side already computes the half-RAM cap.
- **Search synonyms.** `PzoptSettings.search` gets a small alias table: ram / heap / xmx / gb → gcHeap; fps cap / limiter →
  the frame-cap keys; blurry / sharp → upscaler and spriteFilter; stutter → Fix a problem.
- **A layout test:** fail when a Performance key lands in a generated "More". Today 21 keys do (Assessment B), the gc*
  ones among them.

### 2.8 What this answers

| Player report | Answered by |
|---|---|
| "click through every single option one by one" | hover-select (2.4), one-liners and cost chips in the row (2.3) |
| "almost impossible to understand what does what" | one-liners, cost chips, the tick-box fix, short-first details |
| "doesn't show any options" / "only master switches" | the category is the page, Common lists every heading's common settings, in-place "+ N more" (2.1, 2.2) |
| heap only found by searching "memory" | the Memory subcategory, the START HERE quick row, search aliases (2.7) |
| "tabs always expand when using controller" | the sidebar cursor no longer opens nodes; two levels only (2.5) |
| "barebones" / "the old one was better" | everything visible again with inline controls (the old tab's strength) plus the new search and clips |
| tiny text at 5K | Medium fonts from 1,440 px, capped frame and measure (2.6) |

---

## 3. First step that ships on its own (prototyped, uncommitted, not verified in game)

The changes are in the worktree `~/pzopt-wt/menu-impeccable` (branch `menu-impeccable` from `origin/master` `adef1841`).
Nothing is committed. Diff: `git -C ~/pzopt-wt/menu-impeccable diff`.

| Change | Where (worktree) | Fixes |
|---|---|---|
| A switch's tick box returns a boolean, and its label is "Enabled" instead of "On". | `menu` `settingItem` (`get`, the control `label`) | P0-1 |
| Detail follows the mouse: a row the moving mouse rests on for 150 ms becomes the selection. A resting mouse never selects (the controller and the wheel keep theirs), and nothing happens while a prompt or the help is up. | new `PzoptMenuList:hoverSelect`, called at the top of `PzoptMenuList:prerender` | P0-2 |
| One-click values. A setting or master row with an inline value draws a tick box (filled from the control's `get`) or `< value >` arrows. A click on that zone changes it at once, and a click on the left arrow steps a choice back. A click elsewhere on the row behaves as before. | `valueDeco` / `decoW` above `PzoptMenuList`; `layoutItems` reserves the width; `prerender` draws it and records `self.valueX[i]`; `onMouseUp` acts on it | P0-2 |
| "Show N more" steps one view at a time (Simple → Advanced → Everything). It names the count that step adds, has SHOW MORE on Y and SHOW ALL on X, and skips to Everything when Advanced adds nothing. | `menu` `subPage` | P1-1 (partly) |
| The heap gets a home: "System, memory, sound and mods" > "Memory (Java heap)" (the nine gc keys). `gcHeap` and `luaGcNoop` join `simple`. A new Fix a problem entry, "Freezes with many mods or in big hordes" (its numbers come from the `gcHeap` / `luaGcNoop` tips), points at it. | `layout` system category, `simple`, `problems` | P1-3 |
| The value line reads "Next launch: Off  /  In use now: Off  /  Default: Off" (the old four-space gaps collapsed in rich text). | `menu` `settingDesc` | P2-2 (minor) |
| The compat page's path to the Java-mods switch names the screen and the renamed category. | `pzopt_mainscreen_compat.lua:173` | copy |
| The nine gc keys are listed as UNGATED: the System switch never gated them, and listing them in the layout made the test check them. | `tests/pzopt/CategoryMastersTest.java` | keeps the test green |

What was checked:
- The three edited Lua files parse (`luajit -b`).
- `CategoryMastersTest` passes when compiled against this worktree's `Config.java`.
- The test at HEAD fails on the new layout, as it should ("category system: gcHeap is neither gated ...").
- `scripts/build.sh` was not run (it needs the bytecode audit and would write `build/`), and nothing was installed or
  launched.

Risks to check in game:
- Hover-select rebuilds the detail (clips, chart, controls) at most once per dwell. The old tab did the same per hovered
  row, but watch the clips' texture loads while sweeping the mouse down a long page.
- The value zone is `x >= valueX - 8`, covering the box or arrows plus the value text. On a row whose value wraps under
  the label (a long choice), there is no zone and the old two-click path stays.
- The tick fill reads `controls[1].get()` under `pcall`. The overlay row ("Shown / Hidden") fills from its own `get`.

How to verify it once runs are allowed, through the queue:
- `options_shots=vis/light/4;perf/system/1;problems/9` gives screenshots of the God rays page (box state), the new
  Memory page and the new problem.
- `options_select=gcHeap` shows the heap's detail.
- `options_joy=focus,right,down,down,a,shot` checks that a pad toggle still works.
- `options_read=1` with `options-readability.py` gives Jev's readability verdict before and after.
- Hover cannot be scripted by the existing rig, so the mouse paths need a short manual check. The maintainer is usually
  at the machine.

Next small steps, in order. Each is independent and none was prototyped:
1. The controller sidebar: cursor separate from selection (2.5). It touches only `focusSide` and `sideRows`.
2. Persist the view (`menuView` key) and draw the Common | All toggle on the breadcrumb line (2.2).
3. Drop the subcategory prefix from labels inside their heading, and the duplicate "applies" sentence (2.3, 2.4).
4. Medium fonts from 1,440 px and the 80-character measure (2.6).
5. The category-as-page restructure (2.1). This is the largest step; the rest do not depend on it.
6. Author `short` one-liners for the Common settings, then the cost chips (2.3).

---

## Appendix: numbers

Settings per category per view (layout at the worktree, tiers as `PzoptSettings.tier`):

| Group | Category | Simple | Advanced | Everything |
|---|---|---|---|---|
| Optimizations | Smoothness and pacing | 7 | 20 | 29 |
| Optimizations | Driving | 5 | 19 | 28 |
| Optimizations | World drawing | 7 | 29 | 41 |
| Optimizations | Lighting, cutaways and weather | 6 | 20 | 34 |
| Optimizations | Zombies and hordes | 5 | 71 | 91 |
| Optimizations | Boot and loading | 5 | 28 | 32 |
| Optimizations | Menus, inventory and map | 3 | 15 | 15 |
| Optimizations | System, memory, sound and mods | 7 | 29 | 31 |
| Enhancements | Image quality | 7 | 33 | 33 |
| Enhancements | Lighting and shadows | 11 | 66 | 67 |
| Enhancements | Reflections and glass | 13 | 33 | 35 |
| Enhancements | Atmosphere and detail | 7 | 19 | 20 |
| Profiler | Performance overlay | 8 | 24 | 29 |

(The counts exclude the keys that fall into a generated "More".)

At HEAD, before the prototype: 497 settings, 89 in Simple (17.9 %).
- Tip length in characters: min 33, median 211, p90 526, max 1,564 (`gcHeap`).
- 371 settings have effect bars.
- Label length in characters: median 37, max 83 (`frameQueueLowCapCalmMs`).
- 21 keys sit in no subcategory and fall into a generated "More".

Assessment B's extraction scripts are in the session scratchpad and are not part of the repo.

---

## 4. The maintainer's tab layout (request of 2026-10-19, evening)

The request, as received:
1. The top-level entries (Home, Optimizations, Enhancements, Profiler, Benchmark, Update, Mod compatibility check)
   become a row of tabs directly under the breadcrumbs.
2. The sidebar shows only the selected tab's pages, always expanded.
3. Controller prompts appear where they act, in the game's own glyph style, and only while a controller is the
   active input.
4. Every control (tick boxes, choosers, sliders, DEFAULT) lives on the rows of the middle column.
5. The right pane is read-only: the explanation, the effect bars and the clip.
6. The clip shows only the setting on: no "before", no side by side.

Impeccable verdict: it holds up, and it fixes problems the first proposal only worked around. It needs these
amendments:
- the sidebar becomes the table of contents of a continuous category page (4.3);
- a defined tab-overflow rule (4.3);
- one rule for what the D-pad does on a value row (4.5);
- a caption that keeps the measured "stock" number for the Optimizations clips once their before GIF goes (4.6).

These have been prototyped behind one flag in the worktree (4.8). Nothing was run.

### 4.1 Scores (Impeccable heuristics, /40)

The three right-hand columns are estimates for designs on paper; only the "Current" column was scored against
the shipped screen.

| # | Heuristic | Current | Proposal (sec. 2) | Tabs as asked (1-6 alone) | Tabs + sec. 2 merged | Why the merged score moves |
|---|---|---|---|---|---|---|
| 1 | Visibility of status | 2 | 3 | 3 | 4 | The selected tab and sidebar row always say where you are; prompts show what each button does here and now; the row's own tick box is the only state shown. |
| 2 | Match the real world | 1 | 2 | 1 | 2 | Tabs are the game's own Options idiom. The jargon still needs the one-liners. |
| 3 | User control and freedom | 2 | 3 | 3 | 3 | Nothing expands under the cursor; LB / RB wrap; B is one rule (list → sidebar → close). |
| 4 | Consistency and standards | 1 | 3 | 3 | 3 | Values change on the row, like stock Options. The D-pad on a choice row is the one context rule (4.5). |
| 5 | Error prevention | 2 | 3 | 2 | 3 | The reset sits on the changed row only; uninstall and presets still ask first. |
| 6 | Recognition rather than recall | 1 | 3 | 2 | 3 | Prompts in place; hover shows the details. As asked alone, the hidden Simple filter and click-to-read remain. |
| 7 | Flexibility and efficiency | 2 | 3 | 3 | 3 | LB / RB between tabs from anywhere, one-click values, wheel and LT/RT. |
| 8 | Aesthetic and minimalist design | 1 | 3 | 3 | 3 | A read-only pane with one clip and no buttons is the biggest declutter of all the options. |
| 9 | Error recovery | 2 | 2 | 2 | 3 | The changed dot and "reset" on the row make "what did I change" visible. |
| 10 | Help and documentation | 3 | 3 | 3 | 3 | Unchanged: the clips, Fix a problem, R3. |
| | **Total** | **17** | **28** | **25** | **30** | Poor → Good |

Cognitive load (merged design), 2 of 8 checks fail:
- **Chunking:** the Optimizations sidebar holds 1 + 8 + 34 rows, which scrolls at 1080p.
- **Minimal choices:** the tab row has 7 entries.

Both are acceptable for a settings tool. The table-of-contents rule (4.3) and the "MODS" short label keep them in
check.

Persona red flags left in the merged design:
- **Sam (controller, 5K):** the D-pad means "change value" on a choice row and "go to the sidebar" elsewhere. The
  prompts make that visible, but it is still a mode. The focus ring is still 1 px; 3 px is a follow-up.
- **Jordan (first-timer):** "OPTIMIZATIONS / ENHANCEMENTS / PROFILER" are still the mod's words. HOME ("Start here")
  is now the first tab, which helps.
- **Alex (power user):** LB / RB plus the per-tab memory make two-tab comparisons fast. He still has no "changed only"
  filter (follow-up).

### 4.2 Wireframes

Mouse, 5120x2160 (the 1080p layout is the same at about 37 % scale; differences are noted under each frame):

```
+--------------------------------------------------------------------------------------------------------------------+
| PZ OPTIMIZATION              PZ Optimization > Enhancements > Lighting > God rays     [Search settings (Ctrl+F)  x] |
|                              +--------------------------------------------------------------------------------------+
|                              |  HOME  OPTIMIZATIONS  [ENHANCEMENTS]  PROFILER  BENCHMARK  UPDATE  MODS              |
| Overview                     +================================--------------------------------------------------+   |
| Image quality            7   | LIGHTING AND SHADOWS            (list header) | [ GOD RAYS ON clip, 1024x432 ]    |
|   Upscaling              2   | Settings shown                     < Common >  |  (2x, centred; 512x216 at 1080p)  |
|   ...                        | Lighting and shadows enabled          [x] On   |                                   |
| Lighting and shadows    11   | -- PER-PIXEL LIGHTING ----------------------   | God rays                          |
|   Per-pixel lighting     3   | Per-pixel lighting                    [ ] Off  | Sunlight (and moonlight) falls    |
|   Sun, moon and clouds   5   | ...                                            | through windows ...               |
|   Ambient occlusion      1   | -- GOD RAYS --------------------------------   |                                   |
| > God rays               2   | * God rays                  reset     [x] On   | Effect on your hardware           |
| Reflections and glass   13   | God rays: brightness (%)             < 100 >   |  GPU   ......####......  high    |
|   ...                        | Show 15 more God rays settings                 |  VRAM  ......####......  high    |
| Atmosphere and detail    7   | Reset Lighting and shadows to defaults [RESET] |                                   |
| [BACK]                       |  [restart reminder bar when needed:  Restart to apply 2 settings  [RESTART NOW]]   |
+--------------------------------------------------------------------------------------------------------------------+
  sidebar: the tab's pages, all open; the     middle: every control, one page    right: read-only
  category's subcategories are its table      per category (the sidebar's
  of contents (a click scrolls there)         subcategory scrolls to its heading)
  (`*` = changed from default, `reset` = back to the default; both only on changed rows)
```

- **At 1080p:** the Optimizations sidebar (43 rows) is taller than the sidebar (about 33 rows of 26 px). A window over
  the rows follows the selection and the wheel moves it ("..." marks rows above). At 5K, 51 rows fit.
- **At 1080p with a large game font, or on a 1280x800 Steam Deck screen:** the tab row switches to its short labels
  (HOME OPTIMIZE ENHANCE PROFILER BENCH UPDATE MODS). If even those do not fit, it shows a window of whole tabs
  around the selected one, with `<` `>` at the cut ends.

Controller, the cursor in the list (prompts shown only while the pad was used last; game glyphs from `Joypad.Texture`):

```
| PZ OPTIMIZATION  [<] (B) Sidebar    | PZ Optimization > Enhancements > Lighting   (View) Search [Search settings] |
|                                     +-----------------------------------------------------------------------------+
|                                     | (LB) HOME  OPTIMIZATIONS  [ENHANCEMENTS]  PROFILER  BENCHMARK  UPDATE  MODS (RB) |
| Overview                            +------------------------------------------------+----------------------------+
| ...                                 | Settings shown      [<>] Change (A) Next  < Common > |  read-only detail   |
| > God rays               2          | God rays          (A) Toggle (X) DEFAULT     [ ] Off |  of the focused     |
|                                     | God rays: brightness (%)                   < 100 >   |  row; RS scrolls    |
| [BACK]                              +------------------------------------------------------+---------------------+
                                       legend (only what has no place): (LT/RT) Page  (RS) Scroll  (Menu) Close  (L3) Search  (R3) Help
```

Controller, the cursor in the sidebar:

```
| PZ OPTIMIZATION                     | PZ Optimization > Enhancements          (View) Search [Search settings] |
|                                     |  (LB) HOME  OPTIMIZATIONS  [ENHANCEMENTS] ...  MODS (RB)                |
| Lighting and shadows    11          |   list of the page under the cursor (it previews as the cursor         |
| > God rays          (A) Open        |   moves; the sidebar rows never move or open)                          |
| Reflections and glass   13          |                                                                         |
| [(B) BACK]                          |                                                                         |
```

Controller, View pressed: the game's own on-screen keyboard (vanilla `OnScreenKeyboard`, as every text field of the game
uses with a pad) opens on the search box; B closes it and the cursor is back where it was; the results then behave as
above (A on a result opens its page with the cursor on the setting, B back to the results).

### 4.3 Problems found, and how the prototype or the proposal answers them

- **Tab overflow** (long names, a large UI font, small screens):
  - 7 tabs with full labels are about 75 capital characters.
  - At 1x font they fit 1080p (content area about 1,550 px) and 5K (about 4,180 px) easily.
  - At a 2x font on 1080p, or on a Steam Deck, they do not. Rule: full labels, then short ones, then a window of whole
    tabs around the selected one, with arrows at the cut ends. Tabs never wrap to a second line, and LB / RB always
    reach every tab.
  - Recommendation: rename the "MOD COMPATIBILITY CHECK" tab to "MODS" outright. It is a third of the row's width.
- **Where Home, search and "Start here" go:**
  - HOME is the first tab. Its sidebar is "Start here" (the presets, quick rows, Clips, Export / Import / Uninstall as
    row buttons) plus "Fix a problem" with every problem listed under it.
  - Search sits at the top right, on the breadcrumbs' line above the tab row (maintainer, 2026-10-19): it is global, not
    per tab. Ctrl+F on a keyboard; View (Select / Back) on a pad, with its glyph next to the box while the pad drives,
    opens the game's on-screen keyboard on it; B closes it and the cursor returns.
  - Results replace the list, and no tab is highlighted while they show (`tabOf("search")` = nil).
- **Master switches:**
  - A group's switch is the first row of its tab's "Overview" page. The tab's label dims while the group is off, and
    the sidebar's Overview row says "off".
  - A category's switch is the first row of its page, as today.
  - Switches are never in the tab row: a tab that toggles when clicked would be a trap.
- **Deep links from search or Fix a problem:**
  - `openSetting` → `selectNode(node, "master", key)`: the tab switches to the setting's tab and the sidebar's window
    scrolls to its subcategory.
  - The cursor lands on the row, and the view is raised if the setting is outside it.
  - B goes back to the results (`backTo`, kept). The tab remembers the page (`tabLast`), so LB / RB back to it returns
    there.
  - Fix a problem lists its settings with their controls in place, so most fixes never jump at all.
- **Common | All:** the first row of every category page, "Settings shown < Common >", a plain chooser row: a click on
  its value, A, or left / right on a pad (View is the search). Each heading's "Show N more <heading> settings" row opens
  the rest of that subcategory in place.
- **The sidebar's depth vs "always expanded":**
  - Optimizations has 8 categories and 34 subcategories.
  - Proposal: make the sidebar the table of contents of one continuous category page (section 2.1). Selecting
    "God rays" scrolls the Lighting and shadows page to its heading instead of loading a page with two rows.
  - Built (after approval): a subcategory's sidebar row scrolls the category page to its heading, and the sidebar's
    highlight follows the heading of the row the cursor is on.
- **Mouse users:**
  - Tabs are clicked; a hover tints them.
  - The LB / RB space is reserved but empty, so the tabs never move when the input device changes.
  - The wheel scrolls the sidebar's window.
  - The details follow the mouse (150 ms dwell) and nothing in the right pane is clickable.
- **5K vs 1080p:**
  - 5K: everything fits (51 sidebar rows); the clip is 2x (1024x432) in a pane about 2,400 px wide.
  - 1080p: the sidebar scrolls on Optimizations; the clip is 1x (512x216) in a pane about 890 px wide. The text keeps at
    least six lines under the clip and the chart (the existing `showDetail` rule).

### 4.4 Controller focus model

| Where the cursor is | Up / down | Left | Right / A | B | LB / RB | X / Y | LT / RT | RS | View | L3 / R3 |
|---|---|---|---|---|---|---|---|---|---|---|
| Tab row (no cursor of its own) | - | - | - | - | prev / next tab, wrapping; the cursor stays in its pane; the tab opens its remembered page | - | - | - | - | - |
| Sidebar | the rows of the tab (no wrap; BACK under the last), the page previews as it moves; nothing opens or closes | - | into the list (the row shows "(A) Open") | close the screen (BACK shows "(B)") | tabs | - | 5 rows | - | the search (on-screen keyboard) | search / help |
| List, a row without a choice | rows (no wrap) | to the sidebar (glyph at the sidebar's corner) | A acts: toggle, open, the row's button; right does nothing on a read-only page | to the sidebar (glyph "(B) Sidebar" at its corner) | tabs | the row's X (DEFAULT) / Y (main action), drawn on the row | page | scroll the detail | the search | search / help |
| List, a choice row (Common / All is one) | rows | previous value | next value (A too) | to the sidebar | tabs | as above | page | scroll the detail | the search | search / help |
| On-screen keyboard (search) | the keys | | | closes it, the cursor back where it was | | X delete, Y space (vanilla) | | | | |
| Right pane | never focused (read-only) | | | | | | | | | |

Wrap-around: LB / RB wrap (HOME ← → MODS); the D-pad never wraps (stopping at the end tells you you are there).
LB / RB from inside the list keep the cursor in the list of the new tab's remembered page.

### 4.5 One context rule: the D-pad on a choice row

The game's own Options screen steps a combo's value with the D-pad while it has the focus, and players expect that.
The maintainer's corner prompt ("D-pad Left = sidebar") conflicts on exactly those rows. Resolution in the prototype:
- left / right change the value on a choice row;
- left goes to the sidebar on every other row;
- B goes to the sidebar from every row.

The prompts show which applies: the sidebar corner shows "(<) (B) Sidebar" or just "(B) Sidebar", and a choice row
shows "(<>) Change". If play-testing shows players getting stuck on a choice row, drop "left = sidebar" entirely and
keep B. Rows with a slider would follow the choice rule.

### 4.6 The clips: only the "after" side

- **Pipeline:**
  - `harness/menu-gifs.py` writes `src/media/ui/pzopt/compare/<clip>-stock.gif` and `-opt.gif` (512x216, 24 fps, 4 s).
  - The menu's `clipPath` is their only consumer. The Workshop page links the dashboard, not these files.
  - `scripts/build.sh:90` ships the whole folder.
  - `pzopt.GifTextures` decodes a GIF only when it is drawn (`KEEP_CLIPS = 2`, `MAX_WIDTH = 512`).
- **Effect:** drawing only the `-opt` side means the stock GIF is never loaded, which halves the clip memory while the
  screen is open. Prototyped.
- **Size:**
  - The 29 stock GIFs are 30.7 MB, the 32 optimized ones 30.9 MB.
  - Dropping the stock side from what ships halves the clips (61.6 → 30.9 MB) and takes about 30 MB off the Workshop
    item and the release zip.
- **What is lost:**
  - The Enhancements clips (god rays, AO, HDR, mirrors, grading...) lose little: "off" is the game the player already
    knows, and the feature is visible in the after clip.
  - The Optimizations clips (drive, fog, storm, horde, load, grid) carry their message in the comparison: a burned-in
    live fps counter, stock in amber vs optimized in green. An after-only clip shows a number with nothing to compare
    it to.
- **Recommendation:**
  - Keep the stock number as a caption under the clip, e.g. "Same route, stock game: 153 fps". `menu-gifs.py` already
    reads both runs' `pzopt-frames.out` and can write the means into its JSON for the Lua.
  - Then stop shipping `*-stock.gif`: change the copy in `build.sh` to `*-opt.gif`, and add `menu-gifs.py --sides opt`
    for new clips.
  - Keep the stock GIFs in git and in cold storage for README and Workshop comparisons. They should not be deleted from
    the repo.
  - Rename the "Before / after clips" setting (`previewClips`) to "Clips".
  - Check its VRAM cost with one clip before deciding whether it can default to on.
- **Sizing:** a whole multiple of 512x216 when it fits the pane's width and a third of its height (the existing band),
  else scaled down:
  - 1080p: 1x (512x216), about 57 % of an 890 px pane.
  - 5K: 2x (1024x432), about 43 % of a 2,400 px pane. 3x (1536x648) does not fit the 626 px band.
  - The source is 512 px wide (`MAX_WIDTH`), so 2x is an upscale. Integer steps keep the pixel art crisp; a 1024 px
    re-encode would double the file size for little gain.

### 4.7 Conflicts with the first proposal (section 2), and how they resolve

| Section 2 said | With the tab layout |
|---|---|
| Common / All toggle on the breadcrumb line | It is the first row of every category page (a chooser: click, A, left / right), because only the middle column is interactive and View opens the search. |
| The control and DEFAULT beside the summary in the detail (2.4) | Dropped. The detail has no controls; DEFAULT becomes the changed row's "reset" (mouse) and X (pad). |
| The changed dot on the row | Kept, now with "reset" next to it. |
| Hover selects after a dwell | Kept, and now essential: it is the mouse's only way to read the details. With nothing clickable in the right pane, hover could even preview without moving the selection; the prototype still moves it (simpler, harmless). |
| The category is the page; subcategories as headings (2.1) | Merged: the sidebar lists them always expanded as the page's table of contents. |
| Sidebar: two levels, never more | Still two (category, subcategory): the tabs took level one. |
| B: detail → list → sidebar → close | Now list → sidebar → close; the detail never has the cursor. |
| One-liners, cost chips, text scale, heap subcategory, tick-box fix | Unchanged and still needed. The tick fix matters less once the detail's tick box is gone, but the box stays on the zoom curve's page. |

### 4.8 Prototype (uncommitted, not verified in game)

Everything is in `src/lua/client/pzopt/pzopt_mainscreen_menu.lua` behind `local TAB_LAYOUT = true` at the top of the
file (false = the released layout plus the section 3 fixes). The changes:

- **Tabs** (`TABS`, `tabOf`, `PzoptMainMenu:drawTabs`, `openTab`):
  - A tab row under the breadcrumbs (`createChildren`: `tabY` / `tabH`, the page starts under it).
  - Full, then short, then windowed labels; the selected tab is tinted with a 4 px accent; the group colours are kept,
    and a group that is off is dimmed.
  - A click opens the tab at its remembered page (`tabLast`, recorded in `selectNode`).
  - LB / RB (`cycleSection`) step through the tabs and wrap.
- **The tab's sidebar** (`tabSideRows`, which `sideRows` calls):
  - The selected tab's pages, always open: the group's "Overview", its categories and every subcategory; Benchmark's
    pages; Home's "Start here", "Fix a problem" and its problems; one row for Update and for Mods.
  - A window over the rows follows the selection; the wheel moves it (`PzoptMenuSidebar:onMouseWheel`); hidden rows
    are not drawn or clicked.
  - Home's page no longer repeats the top-level entries.
- **B** (`goUp`): list → sidebar → close; `backTo` (search, Fix a problem) still goes back first.
- **In-place prompts** (`promptsOn`: the pad has the screen and `wasMouseActiveMoreRecentlyThanJoypad()` is false;
  `glyph` / `drawPrompts` draw `Joypad.Texture` art, which follows the Controller options' Xbox / PS4 / Steam Deck
  style):
  - LB / RB at the tab row's ends.
  - "(<) (B) Sidebar" at the sidebar's top-right corner while the list has the cursor.
  - "(A) Open" on the sidebar's focused row.
  - On the list's focused row: "(<>) Change", "(A) Toggle / Next / Open / APPLY", "(X) DEFAULT", "(Y) ...".
  - The B glyph on BACK and the Menu (Start) glyph on RESTART NOW, via ISButton's own `isJoypad` / `joypadTexture`.
  - The bottom legend keeps only LT/RT, RS, Menu, L3 and R3.
- **Controls only on the rows:**
  - The detail of a settings page and of Home is read-only (`showDetail`: `readOnly`). The zoom curve keeps its four
    numbers and its plot there until rows can edit a curve.
  - A row with its own action and nothing to toggle draws it as a button at its end, which a click or A presses:
    presets' APPLY, EXPORT / IMPORT / UNINSTALL, the DLSS download (`firstAction`, `valueDeco` "button",
    `activateItem`).
  - A changed setting shows an amber dot and "reset" (`isChanged` / `reset` on `settingItem`). X still resets.
  - Left / right step a choice row (`stepRow`).
- **One clip** (`PzoptMenuClips:prerender`, `showDetail`): only the setting-on GIF, centred, at a whole multiple of
  512x216 when it fits, else scaled down.

Checked: the file parses (`luajit -b`), and `luajit -bl` shows no new global names (every new helper is a local defined
before its first use).

Not done in the prototype:
- the table-of-contents page (4.3);
- the Common / All toggle and the View binding;
- moving the Benchmark setup, Update and Mods pages' detail controls to rows (they keep their detail buttons for now);
- the stock-number caption and the `build.sh` filter (4.6);
- a 3 px focus ring.

Harness: `options_joy` sequences that climbed levels with B (`benchmark-pad-check.sh`, the pad rig steps in
harness/CLAUDE.md) now land in the sidebar instead. Update them when this is adopted.

How to verify once runs are allowed:
- `options_shots=home;vis/light/4;perf/system/1` at 5K and at `--option width=1920 --option height=1080`, for the tab
  row, the sidebar window and the clip size.
- `options_joy=focus,rb,rb,down,down,right,a,b,b,shot` for the prompts and the B chain. `forceHints` shows the prompts
  without a pad.
- The tab overflow at the large UI font: a `benchmark-run.sh` `--font 3` or `options.ini fontSize=3` run at 1920x1080.
- A short manual pass with the mouse: tabs, hover, the row buttons, reset.
- The maintainer's eye on the glyph sizes at 5K.

---

## 5. Built and recorded (2026-10-19 evening, after the maintainer's approval)

Everything in section 4 is built in the worktree (`TAB_LAYOUT = true`), together with the later layout requests:
- the search box at the top right;
- View opening the search;
- Common | All as a row;
- the centred 16:9 frame for ultrawide screens.

It is uncommitted and was installed only by the queue jobs' own `--install`. `scripts/build.sh` passes with the bytecode
audit green (9152 unedited methods, 0 mismatches). `scripts/test.sh` is green, `CategoryMastersTest` included; one
earlier run had `CombinedDispatchTest` fail once, and it passed 3 of 3 runs alone and in the full re-run (a timing test
in `FrameBatch`, untouched here).

### 5.1 What the build adds over section 4.8

- **The category is one continuous page, and the sidebar is its table of contents** (`tocPage`):
  - Its first row is "Settings shown < Common / All >", then the category switch, then every subcategory as a heading
    with its settings.
  - A sidebar subcategory scrolls the page to its heading, and the sidebar's highlight follows the heading of the row
    under the cursor.
  - "Show N more <heading> settings" opens the rest of that one subcategory in place (`EXPANDED`). "Show fewer" closes it.
- **Common | All** is that first row, a plain chooser: a click on its value, A, or left / right on a pad. The Simple /
  Advanced / Everything row is gone from HOME in this layout.
- **Search sits at the top right**, on the breadcrumbs' line above the tabs (`placeSearch`).
  - The View (Select / Back) glyph and "Search" are drawn next to it while a pad drives.
  - View opens the game's own on-screen keyboard on it (`focusSearch`, vanilla `OnScreenKeyboard`); B closes it and the
    cursor is back where it was.
  - Ctrl+F still works.
  - A search result row opens its setting on its page with the cursor on it (`openSetting`); its value still changes on
    the row.
- **Benchmark, Update and Mods controls are rows** (`inlinePage`):
  - Controls and actions that every row of a page carried (the benchmark setup's Passes, cool-down and name, START /
    PAST RESULTS, the report's buttons, the comparison's "Only what differs") become rows of their own.
  - On setup they sit under a "Run" heading at the bottom; elsewhere under "Actions" at the top.
  - The benchmark name is a row with an EDIT... button that opens a text box (`askText`).
  - Every row draws its own actions as buttons; the right pane is read-only on every page (only the zoom curve keeps its
    numbers there).
- **The focus ring is 3 px** (list and sidebar). The tab is labelled **MODS**, and **RB sits right after the last tab**.
- **Ultrawide (Steam comment, 19:37 UTC):**
  - The whole screen is a centred frame at most 16:9 wide: 3,840 px on a 5120x2160 screen, 2,560 on 5120x1440, 1,920 on
    3840x1080.
  - Every value starts right after the page's widest label (a value column between 30 and 60 % of the list), with its
    reset and the controller prompts after it, so changing a setting needs no eye travel across the screen.
  - Every setting is within two levels: a tab, then a sidebar row (category or subcategory) whose page lists it.
- **Fixed on the way:**
  - The sidebar's group "Overview" row always said "off": `cond and nil or "off"` is always "off".
  - Value-zone clicks call the row's control (`c.set`) rather than the row's action.
- **Harness:**
  - `options_tour=1` with `harness/menu-tour.sh` is the mouse tour: real cursor moves, clicks, wheel and typing through
    xdotool, calibrated against the game's own mouse position. Game px = X px / 0.8 on this desktop.
  - The `options_joy` rig gained `hints`, `wait`, B-closes-the-on-screen-keyboard and `options_joy_ms`.

### 5.2 The videos and stills

Everything is in `docs/media/menu-preview-2026-10-19/`. Both videos are AV1 10-bit PQ / BT.2020 at 5120x2160, trimmed by
stream copy from the run recordings, so they are as recorded and not tone-mapped. The run folders are under
`harness/runs/menu-video-*`.

- **The controller take** used the screen's own pad handlers (`options_joy`, prompts forced on with `forceHints`), not
  the in-game virtual pad. With `pzopt.VirtualPad` the pad activated (A reached the main menu), but no D-pad press ever
  reached the game's handlers (run `menu-video-pad-*`). The game dir carries PZ_Controller's `pzctl` Lua, which wraps
  those handlers; that was not investigated further.
- **`menu-preview-mouse.mp4`** (84 s, mouse tour, `harness/menu-tour.sh`, run `menu-video-mouse4-*`), times approximate:

  | Time | What it shows |
  |---|---|
  | 0-4 s | the main menu, then the screen opens on HOME (presets with APPLY buttons on their rows) |
  | 4-21 s | every tab clicked in turn (Optimizations, Enhancements, Profiler, Benchmark, Update, Mods), back to Enhancements |
  | 21-27 s | the sidebar: Lighting and shadows, then God rays scrolls the category page to its heading |
  | 27-38 s | hovering rows: the read-only right pane follows the mouse (the "on" clip only, the effect bars, the text) |
  | 38-42 s | one click on God rays' tick box (Off → On: the tick box, the amber dot and "reset"), then reset |
  | 42-50 s | "Show 15 more God rays settings" opens that subcategory in place; hovering the new rows |
  | 50-58 s | "Settings shown" → All (the page grows, the wheel scrolls it), then back to Common |
  | 58-71 s | a click in the top-right search box, "memory" typed, the results; the heap row opens System > Memory (Java heap) with the cursor on it |
  | 71-84 s | the heap's page, then the game quits |

- **`menu-preview-controller.mp4`** (70 s, `options_joy` with the prompts on, run `menu-video-joy3-*`):

  | Time | What it shows |
  |---|---|
  | 0-7 s | HOME with the in-place prompts: LB / RB around the tab row, the View glyph + "Search" by the search box, the focused row's A / X |
  | 7-17 s | RB through Optimizations, Enhancements, Profiler, Benchmark; LB back to Enhancements |
  | 19-30 s | left into the sidebar ("(B) Sidebar" moves away, the row shows "(A) Open", B on BACK); down through the tab's pages, each previewing; nothing expands or collapses |
  | 30-38 s | A into Lighting and shadows: "Settings shown" with "(<>) Change (A) Next"; right → All, left → Common |
  | 38-46 s | down through the rows: "(A) Toggle (X) DEFAULT" on each, the sidebar's highlight following the heading |
  | 46-53 s | View: the game's on-screen keyboard opens on the search box; B closes it, the cursor back on its row |
  | 53-70 s | B to the sidebar, B closes the screen, the game quits |

- **Stills** (1920 px wide unless noted):
  - `still-mouse-home.png`, `-settings.png` (God rays page), `-changed.png` (God rays on: tick box, dot, reset),
    `-search.png` ("memory"), `-heap.png` (System > Memory (Java heap));
  - `still-controller-sidebar.png`, `-list.png`, `-search.png` (on-screen keyboard);
  - 1:1 crops at 5K: `still-controller-tabs-crop.png` (LB / RB, the row prompts), `-row-crop.png`, `-search-crop.png`
    (View + Search);
  - `still-32x9-1..3.png`: a 5120x1440 window (run `menu-32x9-*`), HOME, God rays, the heap page, scaled to 2560 px wide.
    The frame is 2,560 px wide and centred, and every value sits right after its label;
  - `before-release-1..4.png`: the maintainer's screenshots of the released menu (1600 px copies).

### 5.3 Still not done

- **Search ranking:** "memory" lists the heap 16th. Aliases (ram / heap / xmx / gb) and a boost for exact label words
  are needed (2.7).
- **The stock-fps caption for the Optimizations clips is skipped:** the clip metadata (`harness/menu-gifs.json`) names
  the runs but not their frame rates, and those runs are in cold storage, not on disk.
- **The `build.sh` change** that stops shipping the `*-stock.gif` files (about 30 MB) is left out on purpose; it is a
  follow-up.
- **HOME's "Before / after clips" row** still has that name; it should read "Clips" now that only the after side plays.
- **Text sizes are unchanged:** the rows are still in UIFont.Small, and section 2.6 is not built.
- **The one-liners and cost chips (2.3)** are not built.
- **The zoom curve's four numbers** are still edited in the right pane.
- **The real pad:** the virtual pad's D-pad problem above, and a real-pad pass (`benchmark-pad-check.sh`) needs new
  steps for this layout (B no longer climbs levels).
- **The maintainer's original screenshots are gone:** `~/Zomboid/Screenshots/pzopt-options-1..3.png` (the inputs of
  section 1) were overwritten by the 32:9 run's `options_shots`; `-4.png` is intact. Downscaled copies (1600 px wide)
  of all four originals are kept as `docs/media/menu-preview-2026-10-19/before-release-1..4.png`.

## 6. The settings tree (maintainer's request, 2026-10-19 night)

The request: "remove the delayed hover to select; the centre page lists all the settings with the controls under it,
grouped as a collapsible tree; each setting has an on / off toggle and under it, indented, the modifiers of the setting".

- **Hover-select is gone** (not just shortened). A click selects a row; the mouse resting on a row does nothing.
  `HOVER_MS` / `PzoptMenuList:hoverSelect` are removed. This reverses the decision of 4.7: with every control in the
  list itself, the right pane is read on demand and a click is the expected way to ask for it.
- **One page per category lists every setting.** Common / All and "Show N more" are gone. Each subcategory is a
  group heading (arrow, capitals, "N settings (M changed)", the count amber when anything is changed); a click or
  Left / Right folds and opens it. "Fold all" / "Open all" sits at the right of the list head when a page has more than
  one group. A node the player navigates to (search, `key:`) is opened first.
- **Every setting is a row with a switch before its name.** A bool is its switch; a choice with an off value (`off`,
  `false`, or the layout's `offValues`: `spriteFilter=stock`, `darknessFloorPct=0`) gets a switch plus its chooser at the
  value column, shown only while the switch is on. Turning one on uses the choice used earlier this session, then the
  default if it is not off, then `onValues` (`upscaler=fsr1`), then the first non-off choice; the chooser steps over
  the off value. A choice with no off value (numbers, modes) is just a chooser. Category masters and the overlay rows
  are switches too.
- **Modifiers are indented under their setting** (at most two levels, tree guide lines, a fold arrow when a setting
  has modifiers; Y folds on the pad). Rows under a switch that is off are dimmed (still editable). Nothing in the data
  said what modifies what, so `PzoptSettings.nest` derives it: the layout's `parents` table first (curated for the
  Performance keys, "" = top level), then the label prefix ("God rays: brightness" goes under "God rays"), then a
  number / mode right after a switch goes under that switch. The dump of every subcategory was read by hand.
- **Selection** is a tint plus a focus ring when the pad is in use; the 4 px left bar is gone (craft floor).
- Harness: `options_tour=1` (`pzopt_harness_tour.lua`) clicks instead of hovering, and the "Show more" / Common-All
  steps became: toggle God rays at its switch, reset, fold the setting, fold a group, Fold all, Open all.

### 6.1 Second round (2026-10-20)

- **One page per tab.** The Optimizations / Enhancements / Profiler "Overview" pages are gone; a tab is one page with
  its master switch, then every category as a heading (switch, name in the medium font, "N settings (M changed)",
  **"Reset to defaults" at the right end**; X with a controller), its subcategories as groups and the settings tree under
  them. The old "Reset ... to defaults" rows at the page's end are gone; the tab's own reset sits at the right end of its
  switch. The sidebar is the page's table of contents: a category or subcategory row scrolls to its heading.
  Rows under an off category (or an off tab switch) are dimmed. The tree's layout is cached per page (a tab holds ~300
  rows) and redone on a change, a width change, or once a second.
- **Every value with choices is a dropdown.** A framed box with the value and a down arrow, one width per page; a click
  (A) opens the list under it (over it when there is no room), each choice with its note wrapped in grey under it, the
  current one marked; a click or A picks, B / Escape / a click outside closes, the wheel scrolls a long list. The D-pad's
  left / right still steps the closed value. A switched choice lists every value but its off one (the switch is the way
  off); the zoom curve lists its presets by name ("custom" for a hand-set curve) and now steps too (it did not). The
  benchmark's Passes / cool-down are dropdowns too.
- **No restart prompt.** A change that waits for a restart only shows "Restart to apply N settings" with RESTART NOW at
  the bottom right (RESTART NOW still asks before quitting).
- **The sidebar follows the scroll** (`PzoptMainMenu:followScroll`, every frame): its highlight, the breadcrumbs and the
  tab's remembered place are the section of the list's top row; the wheel moves it too (it used to follow only the
  selected row). A way back (B to the search results) moves with it.
- **Search results under their categories**: a heading per category (its tab and "N matches"), the category of the best
  match first, the matches under it in score order; "Go to section" at the heading's right end (A / X with a controller)
  opens the tab scrolled to that category, and B comes back to the results.
- **Own tabs for loading, mods and multiplayer** (2026-10-20, menu only: `settingsTree` moves them; the layout data and
  the released options tab are unchanged). LOADING (after OPTIMIZATIONS) is the "Boot and loading" category; its switch
  (`catLoadEnabled`) is the tab's, and the whole mod's `enabled` still dims it. The mod settings (`modProfile`,
  `modCompat`, `uiRetainedMods`, `luaWorkerGate`) are a "Mod settings" category and the multiplayer one
  (`luaChecksumExempt`) a "Multiplayer settings" category on the MODS tab, renamed MODS & MP (breadcrumb "Mods &
  multiplayer"), under the compatibility check's Choice row. Optimizations' "System" category
  lost "mods" from its name, and its tab-wide reset now resets only what its page lists (it used every page-1 key).

### 6.2 Third round (2026-10-20)

- **UPDATE tab settings**: `updateCheck`, `updatePrefetch` and `updateFromWorkshop` became an "Update settings" category
  under the updater's rows (`MOVED_TABS`). "System, memory and sound" no longer lists updates.
- **HELP tab** (last tab): "Fix a problem" and the problems moved off Home's page and sidebar. Home now keeps Start here
  and Tools.
- **BACK under the sidebar**: it is a child of the screen now. It sits level with the bar under the page and has the same
  height. The sidebar now runs exactly as far as the page area.
- **No layout shift from row buttons**: a row's `reserve` (the button sets it can show) keeps the width of the widest
  set. When RESTART GAME appears after a MODS & MP choice, or UPDATE NOW changes to RESTART GAME, the value column no
  longer moves and the row no longer drops to a second line.
- **Preset card over the sidebar** (`PzoptPresetCard` / `PzoptPresetPop`): "Preset:" and a dropdown, level with the
  breadcrumbs and the tab row.
  - The list holds the built-in presets (with the first sentence of each tip), the presets saved on this PC, "Save
    current settings as..." and, while a saved preset is in use, its Delete.
  - Saved presets live in `Zomboid/Lua/pzopt-presets.ini`, one `[name]` heading per preset followed by its key=value
    lines (`PzoptSettings.userPresets` / `saveUserPreset` / `deleteUserPreset` / `settingsLines`).
  - Loading a saved preset imports its text: keys it does not list go back to the default, and pinned keys stay as they
    are.
  - The box shows a saved preset whose text equals the current settings. Otherwise it shows the preset applied last this
    session, if the settings are as that preset left them, or Recommended when every setting is at its default.
    Anything else is Custom.
  - Applying or loading always asks first. When the current settings are Custom, the question also says they are not
    saved.
  - With a controller, the card is the sidebar's row 0 (Up from the first row). A opens the list; Up / Down / A / B
    work inside it. Escape or a click outside closes it.

### 6.3 Fourth round (2026-10-20)

- **No HOME tab.** The screen opens on OPTIMIZE. Home's tools moved to the preset card, and the page is left only for
  the uninstall rig's old path.
- **Preset card**, from top to bottom:
  - PZ OPTIMIZATION;
  - "Preset:" and its dropdown;
  - EXPORT / IMPORT... / UNINSTALL, in equal thirds. Their tooltips say what each does. UNINSTALL is red and is
    disabled (the reason is in its tooltip) where the uninstall cannot run.
  - With a controller, the dropdown is sidebar index -1 and the tools are index 0 (left / right choose a tool). The
    `devUninstallDrive` rig presses the card's UNINSTALL.
- **The sidebar is drawn as the page's tree** (`PzoptMenuSidebar:renderTabTree`):
  - the list's colours and highlight (fill, 3 px focus border);
  - categories at depth 0 with a fold arrow; a click on it or D-pad left folds the category (`SIDE_FOLD`, kept until
    the game closes);
  - subcategories on guide lines with a tick, the last one's guide stopping at its middle.
- **"Before / after clips" switch** at the sidebar's foot under a divider; with a controller it is the place after the
  last row. BACK, now as wide as the sidebar, comes after it.
- **SYSTEM tab** after LOADING: Optimizations' "System, memory and sound" category, split off the same way as LOADING
  (`SPLIT_TABS`). Its category switch (`catSystemEnabled`) is the tab's switch.

### 6.4 Fifth round (2026-10-20)

- **A setting's detail is one card** (`PzoptMainMenu:settingCard`). From top to bottom:
  - The setting's name.
  - The effect on the hardware as a line of chips under the name: the resource, a 40 px bar from the stock middle, and
    the level word. This replaces the chart in the tab layout. A setting that moves no resource has no chips.
  - The text.
  - A divider, then the metadata: Applies (amber "Needs restart" / "At once, no restart"), Released, Key, Java.
- **Restart notes:** "Takes effect on the next launch.", "Applies at once; no restart needed." and the tips' own
  "Applies on the next launch." sentence are gone from the text in the tab layout (`stripLaunchNote`). The same applies
  to master and category switches (`masterRow`'s `meta`).
- **SAVE... beside the Preset: dropdown** replaces the list's "Save current settings as...". The list keeps Delete for
  a saved preset in use. With a controller, right from the box reaches SAVE....
- **No layout shift in the detail card:**
  - The effect line is a fixed grid (`effectGrid`): all nine resources sit in the same cells for every setting. The
    column count comes from the width, and the columns are evened out. A resource the setting does not move is dimmed
    as "stock".
  - The name keeps the room of the page's longest name (`cardTitleLines`).
  - The metadata always has Applies / Released / Key / Java, and Java takes at most two lines.
  - Moving between rows of a page moves nothing: the text always starts and ends at the same height.
- **The clip is in the setting's card** (while "Before / after clips" is on): it sits between the text and the
  metadata, with a divider above it and the metadata's divider below it. Every setting of a page keeps the same room for
  it. One without a clip of its own says so in the clip's box. The separate clip panel above the card is gone in the
  tab layout.
- **The clip's description is under the clip**, centred. Two lines are kept so the height stays fixed, and a longer
  text ends in "...". It no longer goes at the end of the text.

### 6.5 The benchmark tab (2026-10-20)

- **No overview and no Past results page.** The BENCH tab opens on the run planner: `selectNode` sends `bench` and
  `bench/history` to `bench/setup`. The planner's scene and option rows are switches, like the settings rows, and its
  PAST RESULTS button is gone. The compare page has no Past results button either.
- **The reports are the sidebar.** The sidebar lists Run planner, then Compare two runs, then a REPORTS heading with
  the count, then one row per run, newest first. Each run row shows:
  - the benchmark's name;
  - the date;
  - the preset;
  - "Score N, F fps avg";
  - "99%: X ms".

  Clicking a row, or moving onto it with the controller, opens that report. The heading rows are skipped. Rows can have
  different heights now, and the sidebar's scroll window allows for that.
- **Where the numbers come from.**
  - The 99% figure is the mean p99 frame time over the run's optimized scenes (`BenchResults.p99Mean`, history
    column 8).
  - The preset is matched from the settings the run recorded (`BenchSetup.optText`, column 9). These are filtered to
    the menu's export keys and then compared:
    - nothing changed: Recommended;
    - only `enabled=false`: Stock game;
    - the same text as a saved preset: that preset's name;
    - anything else: Custom;
    - a run that recorded no setup: "Preset not recorded".
  - The history is read at most every 5 s.
- **The report page:** PAST RESULTS is now COMPARE WITH PREVIOUS (the run before this one), and SHOW RESULTS FOLDER was
  added.

### 6.6 Scene cards and the report tree (2026-10-20)

- **The planner's scenes get the setting card.** The card shows the scene's clip, a RESOURCE USE grid and a facts panel.
  - The grid uses the same cells as a setting's effect line. Each bar fills from the left, from none to heavy.
  - The values are in `SCENE_INFO` in the menu. They come from what each route is built to load, so they are the same
    on every PC. The card's text says so.
  - The facts panel has four rows: Where, Length, Score and Tests.
  - The planner is a `cardPage`: its other rows (the options, Passes, Name, Start) keep the plain text detail.
- **Clips.** Rosewood spin, the highway drive and Louisville reuse `spin`, `drive` and `horde`. Three new pairs were cut
  from existing recordings by `harness/menu-gifs.py`, run as queue media job 13997:
  - `towndrive`: runs td-rel-stock-1 / td-rel-new-1, from route +12 s, with the counter;
  - `piershoot`: runs hs-stock-a / hs-opt-a, from route +14 s, with no counter (that day's stock pass sat at a leftover
    60 fps cap);
  - `visuals`: the all-enhancements showcase's ge-clear-off / -on panes, Visuals off vs on.

  The runs were brought back from cold storage into the worktree's `harness/runs/`.
- **The sidebar's reports are a tree.**
  - Each report's row shows the benchmark's name, or its date when it has none, with a fold arrow.
  - The scenes are its leaves.
  - Clicking a report opens it and unfolds it. Clicking a leaf opens the report on that scene's row (the PZ
    Optimization pass first).
  - The newest report starts unfolded and the older ones folded.
  - A leaf is lit while the list shows its scene; the report row is lit otherwise, or while it is folded.
  - The multi-line rows (date, preset, score, fps, 99%) are gone, and `runPreset` with them. The Java history columns
    (p99, the settings text) stay for a later use.

### 6.7 Small windows and the controller (2026-10-20)

Jev's readability tour (`--flag options_read=1`, `harness/options-readability.py`) ran in windowed 1024x768, 1280x720,
1280x800, 1366x768 and 1920x1080, and a controller walk (`--flag options_joy=...`) at 1280x720 and 1920x1080 (queue jobs
14092-14098). The tour now covers the tab layout: every tab page and its further pages, each scene card, the sidebar's
focus stops, the open preset list (`readStopsTabs` in the harness Lua). Every window size was "overflowing"; 1024x768 had
3,646 overlapping text pairs, 1920x1080 had 50.

- **The setting card piled up in a short pane.** The head, the metadata, the clip and the text sat at fixed places, so
  below about 800 px of height they were drawn over each other. A short card now fits by steps, decided on the room the
  page keeps for every row (so nothing moves between rows): the metadata goes into the text under the description, the
  caption keeps one line, the effect / RESOURCE USE grid goes into the text (label and word, in their colours), the clip
  shrinks to a third of its height, then it is left out. A caption too long for its lines shows its first sentence, the
  whole caption in the text.
- **Category headings** ("Boot and loading", "Multiplayer") were cut to "B..." beside "32 settings" and "Reset to
  defaults" in the side-by-side list; when the name does not fit, those two go on a line under it. Section headings take
  two lines. The tab's switch row wraps its name left of its "Reset to defaults" (they overlapped).
- **A deep row in a narrow list** wrapped its name into a column narrower than a word ("Spare-time" under the chooser);
  the control now goes on its own line under the name. A changed row's "reset" no longer runs past the list's edge.
- **The sidebar** cut long problem and subcategory names ("Storms, fog and rain are s..."): they wrap to two lines. The
  preset card in a narrow sidebar puts "Preset:" over the box (it showed "Reco...") and its tools two to a row (UNINSTALL
  sat on IMPORT...); the controller's in-place "Choose" only shows while the name fits beside it.
- **Breadcrumbs** ran into "Search": the path drops its first parts counting the "..." it draws, and a last part that is
  still too long is cut (the list's heading under it is whole).
- **Contrast.** `C_DIM` 0.40 was about 3.4:1 on the cards, under what small text needs, and OCR failed on it; now 0.52.
- **Controller.** LB on the first tab threw (`attempted index: id of non-table`): Kahlua's `%` keeps the dividend's sign,
  so `(0 - 1) % n` is -1 and the wrap indexed tab 0. The same pattern stepped choosers, Passes and Cool-down left past their
  first entry. All add the count before the modulo now. RB through all eleven tabs, the panes, A, the sidebar and B out
  were clean.
- **The judge** counted labels whose "..." means "opens a dialog" (SAVE..., IMPORT..., EDIT...) as cuts, the preset
  list's popup as overlapping the page under it, and asked for the old layout's screen names; all three are fixed in
  `harness/benchmark-readability.py`.

After the fixes: 1024x768 (job 14106) 0 overlaps, OCR read-back 92 %, Jev `minor_issues` (0.77); 1280x720 (job 14110,
the last build) 0 overlaps (966 before), OCR 90 %, `minor_issues` (0.67, readable 0.30), three cuts left: the second line
of the longest category names ("Lighting, cutaways and w..."). The controller walk 14103 checked the LB fix; no run logged
a Lua error.
