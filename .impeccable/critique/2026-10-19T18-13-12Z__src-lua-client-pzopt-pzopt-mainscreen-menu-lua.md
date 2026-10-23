---
target: PZ OPTIMIZATION settings menu
total_score: 17
max_score: 40
na_heuristics: 
p0_count: 2
p1_count: 3
timestamp: 2026-10-19T18-13-12Z
slug: src-lua-client-pzopt-pzopt-mainscreen-menu-lua
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
