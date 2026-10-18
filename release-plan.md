# Release Plan

Two steps:

1. **Drop gum.** Rebuild the prompts on top of `bb-tty`, copying gum's look and
   key bindings. After this the only runtime requirement is `bb` (plus `gh` /
   `jj` / `git`, which we can't avoid).
2. **Ship a self-contained binary.** Append the prstack uberjar to the `bb`
   binary, publish per-platform builds on GitHub Releases, and install them via
   a Homebrew tap.

Step 2 only starts once step 1 is merged. A binary that still needs `gum` on
the PATH defeats the point.

---

## Step 1: Replace gum with bb-tty prompts

### What we use gum for today

All prompts live in `src/bb_tty/tty.clj`. Every call site uses single
selection (`:limit 1`), so multi-select is out of scope.

| Function         | gum command  | Call sites                                                                 |
|------------------|--------------|----------------------------------------------------------------------------|
| `prompt-confirm` | `confirm`    | `sync.clj` (×5), `create_prs.clj` (×2), `tui/db.clj` (×2, inside `run-in-fg!`) |
| `prompt-pick`    | `choose`     | `init.clj` (×2), `sync.clj` (diverged branch)                              |
| `prompt-filter`  | `filter`     | `feature_base.clj`                                                         |
| `prompt-input`   | `input`      | `bitbucket.clj` (PR title)                                                 |
| `prompt-write`   | `write`      | `bitbucket.clj` (PR description)                                           |
| `with-spinner`   | `spin`       | unused, delete it                                                          |
| `spin`           | none, already ours | `ui.clj`, `list.clj`                                                 |

### How gum does it (gum v0.17, bubbletea v2)

Read from the gum, bubbles and sahilm/fuzzy sources. These are the things
that make it feel good:

**Rendering**
- **Inline, not fullscreen.** `choose`, `confirm`, `input`, `write` and `spin`
  draw at the cursor position, below whatever was printed before. Only
  `filter` with the default `--height 0` switches to the alt screen and uses
  the full terminal height.
- **Repaint in place.** Bubbletea's inline renderer moves the cursor up to the
  first line of the previous frame, overwrites the lines and clears leftovers.
  Nothing flickers because the screen is never fully cleared.
- **Disappears when done.** Every `View()` returns `""` once `quitting` is set,
  so the prompt is erased and leaves no trace in the scrollback. The result goes
  to stdout; the UI is drawn on stderr.
- **Cursor hidden** while a list or confirm is shown. The text inputs show a
  blinking block cursor (530ms blink, color 212).
- **Lines never wrap.** Everything is truncated to the terminal width, which is
  what makes "move up N lines" repaints reliable.

**Exit semantics**
- `enter`: submit
- `esc`: quit without a result (exit 1, "nothing selected")
- `ctrl+c`: abort (exit **130**). In a shell script that kills the whole flow,
  not just the prompt.

**Help bar.** A blank line and then one dim line under every prompt, e.g.
`←↓↑→ navigate • enter submit`. Each item is a key and a description joined
with ` • `. Disabled bindings (like `toggle` in single-select) are hidden.
Dark-background colors: key `#626262`, description `#4A4A4A`, separator
`#3C3C3C` (≈ 256-color 241 / 239 / 237).

**Palette (256-color)**
- `212` (pink): cursor, selected item, fuzzy-match highlights, spinner,
  selected confirm button background
- `99` (purple): headers of `choose` and `filter`
- `240` (gray): placeholder text, the `> ` filter prompt, headers of
  `input` and `write`
- `#7571F9` (bold): the confirm prompt. **prstack doesn't use this or the
  purple 99 headers.** It overrode them while using gum, so prompts and
  headers use the terminal's default foreground.
- Confirm buttons: selected = bg `212` / fg `230`, unselected = bg `235` /
  fg `254`, both with padding `0 3` and margin `0 1`

#### `spin`
- Default spinner `dot`: `⣾ ⣽ ⣻ ⢿ ⡿ ⣟ ⣯ ⣷` at 10 fps, color 212, then a
  space and the title.
- The command's output is captured, not shown, and the line is cleared when
  the command finishes.
- Ours already works this way, but uses the `minidot` frames at 80ms in
  magenta. Switching to `dot` / 100ms / 212 is a one-line change.

#### `confirm`
```
 Push feature-x?            ← prompt, default foreground in prstack, margin-left 1

   Yes      No              ← buttons; the selected one has the pink background

←→ toggle • enter submit • y Yes • n No
```
- `y`/`Y` confirms right away. `n`/`N`/`q` declines right away.
- `←→ h l tab shift+tab ctrl+n ctrl+p` toggle between the buttons.
- `enter` submits the highlighted button. The default is Yes.
- `esc` counts as No. `ctrl+c` aborts.

#### `choose`
```
Which VCS would you like to use?    ← header, default foreground in prstack
> Jujutsu (recommended)             ← cursor "> " plus the text, both 212
  Git

  ••                                ← page dots, only shown with >1 page

←↓↑→ navigate • enter submit
```
- Height 10. The list is **paginated**, not scrolled: `←→ h l` flip pages.
  The dots use the subdued color for the active page and a very subdued one for
  the others.
- `↓ j ctrl+j ctrl+n` and `↑ k ctrl+k ctrl+p` move the cursor and **wrap
  around**. `g`/`home` and `G`/`end` jump to the first and last item.
- Without multi-select there are no item prefixes, only the cursor.

#### `filter`
```
Select feature base branch          ← header, default foreground in prstack
> feat█                             ← prompt "> " 240, placeholder "Filter..." 240
• feature-base-api                  ← indicator "•" 212 on the cursor line,
  feat/login                           matched characters in 212
  my-feature

↓↑ navigate • esc blur search • enter submit
```
- Fuzzy matching with sahilm/fuzzy scoring, sorted by score. With an empty
  query it shows every option in the original order.
- The text input is focused by default. `esc` unfocuses it, `/` focuses it
  again. While unfocused, `j`/`k`/`g`/`G` navigate.
  `↑↓ ctrl+j ctrl+k ctrl+n ctrl+p` always navigate.
- Strict by default: you can only submit an existing option, not the typed
  text.
- Fullscreen (alt screen) by default. With `--height N` it renders inline
  with a scrolling viewport.

#### `input`
- `> ` prompt, placeholder `Type something...` in 240, header in 240 above the
  input.
- Readline-style editing keys from bubbles `textinput`: `←/ctrl+b`,
  `→/ctrl+f`, `alt+←/alt+b`, `alt+→/alt+f`, `home/ctrl+a`, `end/ctrl+e`,
  `backspace/ctrl+h`, `delete/ctrl+d`, `ctrl+w`/`alt+backspace` (delete word
  back), `alt+d` (delete word forward), `ctrl+u`, `ctrl+k`.
- `--value` pre-fills the text with the cursor at the end. Lines longer than
  the width scroll horizontally.

#### `write`
- A textarea 5 lines high, with `┃ ` as the line prefix (color 7) and the
  header in 240.
- **`enter` submits, `ctrl+j` inserts a newline, `ctrl+e` opens `$EDITOR`.**
  The Bitbucket hint text "ctrl+d to submit" is out of date and needs fixing
  either way.

### Design in bb-tty

The existing `bb-tty.tui` is fullscreen and clears the screen on every render.
The prompts need a separate **inline** mode. Proposed layout:

#### Where we deviate from gum (decided)

1. **Answered prompts leave a one-line record.** gum erases the prompt
   completely. We erase the frame and print one line in its place, so a long
   `sync` run can be read back from the scrollback.

   | Outcome                   | Record                                                      |
   |---------------------------|-------------------------------------------------------------|
   | Submitted                 | `✔` (212) + prompt + answer (212)                           |
   | `esc`                     | `✗` (240) + prompt + `skipped` (240). For `confirm` the answer is `No`. |
   | `ctrl+c`                  | `✗` (240) + prompt + `aborted` (240), printed before the abort is thrown |

   Per prompt:
   - Multi-line prompts (like the merge confirm) use their first line only.
   - `write` shows the first line of the text plus `(+N lines)` in 240.
   - The spinner leaves no record, since the callers print their own result
     line.
   - The record goes to the tty like the frame does, so piped stdout stays
     clean.
2. **Light and dark terminals get their own palette.**
   - **Detection** runs once per process and caches the result:
     1. Send the OSC 11 query (`\e]11;?\e\\`) and parse the reply
        `\e]11;rgb:RRRR/GGGG/BBBB`. Compute its luminance.
     2. If there's no reply within ~100ms (some terminals, tmux without
        passthrough), fall back to `COLORFGBG`. A background index of 0–6 or
        8 means dark.
     3. Otherwise default to dark.
   - **Timing.** The query must run in raw mode **before** the key reader
     starts. `open-terminal` already notes that JLine's own probe got its
     reply mixed into the event loop's input, and we'd hit the same problem.
   - **Colors that adapt** use gum's light/dark pairs:
     - help bar key `#909090`/`#626262`, description `#B2B2B2`/`#4A4A4A`,
       separator `#DADADA`/`#3C3C3C`
     - active page dot `#847A85`/`#979797`, inactive `#DDDADA`/`#3C3C3C`
   - **Confirm buttons.** gum keeps them dark-only. We use unselected bg `254` /
     fg `235` on light terminals.
   - **Colors that stay the same:** 212 and 240 read fine on both
     backgrounds.
   - The main TUI can use the same detection later. That's out of scope here.
3. **`filter` runs fullscreen, like gum.** It switches to the alt screen and
   the list fills the terminal height minus the header, input and help bar.
   On submit it leaves the alt screen and prints the inline record on the
   normal screen, so the result reads like the other prompts.

#### Namespaces

- **`bb-tty.ansi`**
  - Add 256-color and truecolor support (a `(fg 212)` / `(bg 235)` /
    `(fg "#626262")` helper).
  - Add a `palette` that takes the detected background (`:light` / `:dark`)
    and returns the named gum colors.
  - Add `CLEAR_TO_END` (`\e[J`) and a parametrised `cursor-up`.
  - Add pure parsers for the OSC 11 reply and `COLORFGBG`. The detection
    itself (`tty/detect-background!`) lives in `bb-tty.tty`, since it needs
    the terminal.
- **`bb-tty.tty`**
  - Extend `key-map` with `:enter` (`\r`), `:esc` (a lone `\e` after the
    ambiguous timeout), `:backspace` (`\x7f`, `\b`), `:delete` (`\e[3~`),
    `:tab`, `:shift-tab` (`\e[Z`), `:home`/`:end` (`\e[H` `\e[F` `\e[1~`
    `\e[4~`), `:alt-left`/`:alt-right`, `:alt-backspace`, and the ctrl keys as
    plain chars (`\u0003` is ctrl+c, and so on).
  - Check how ctrl+c reaches us in raw mode. JLine's `enterRawMode` may keep
    ISIG on, in which case SIGINT fires instead of a `\u0003` key. Either clear
    ISIG for the prompt or install a SIGINT handler, so ctrl+c always goes
    through our abort path.
- **New `bb-tty.inline`**, a tiny Elm loop modeled on bubbletea:
  ```clojure
  (run! {:init        state
         :update      (fn [state key] state')  ; returns (done value) to finish
         :view        (fn [state] lines)
         :record      (fn [state outcome] line)  ; outcome is :submitted, :skipped or :aborted
         :fullscreen? false                     ; true for filter
         :tick        {:ms 100 :f (fn [state] state')}})  ; optional, for blinking and spinners
  ```
  - Runs in raw mode and draws on the JLine terminal writer (the tty), so
    stdout stays clean.
  - **Repaint inline:** move the cursor up `(dec prev-line-count)`, `\r`, print
    the new lines joined by `\r\n`, then `CLEAR_TO_END`.
  - **Repaint fullscreen:** enter the alt screen once, then go to the cursor
    home position and redraw on every repaint.
  - Truncate every line to `cols - 1` with `ansi/truncate`.
  - Cap the height to the terminal rows.
  - **On finish** (in `finally`):
    1. Erase the frame, or leave the alt screen.
    2. Print the `:record` line.
    3. Show the cursor and restore the terminal attributes.
  - Re-render on resize. Rather than handling `SIGWINCH`, the loop checks the
    terminal size at least every 100ms, which keeps all rendering on one
    thread.
  - Only renders when `(System/console)` is present. Without a TTY, a
    non-interactive call should fail with a clear message instead of hanging.
- **New `bb-tty.prompt`**. Each ported prompt moves from `tty/prompt-*` to
  `prompt/*`, and its call sites switch over. Thin wrappers in `bb-tty.tty`
  aren't possible, since `bb-tty.inline` requires `bb-tty.tty` and the
  requires would form a cycle.
  - `confirm`, `choose`, `filter`, `input`, `write`, each with pure
    `update`/`view` functions, so they're unit-testable without a terminal.
  - Shared `help-bar` and `text-input` (the line editor state and its keys)
    used by `input`, `filter` and `write`.
- **New `bb-tty.fuzzy`**: a port of sahilm/fuzzy (~80 lines). Bonuses: first
  char +10, after a separator (`/-_ .\`) +20, camelCase +20, adjacent match
  +5 (compounding). Penalty: −5 per unmatched leading char, capped at −15.
  It returns matched indexes for highlighting.

**Ctrl+c.** gum exits 130, and today `bblgum` turns that into
`false` / `nil`, so ctrl+c during `sync` declines one prompt and the next one
appears. Proposal: `ctrl+c` throws `(ex-info "Interrupted" {:type ::interrupted})`.
- `prstack.main` catches it and exits 130.
- `tui/db.clj`'s foreground runner catches it and returns to the TUI.
- `esc` keeps the current "no / nil" behaviour.

**Inside the TUI.** `run-in-fg!` closes the TUI and leaves the alt screen
before running `f` (see `with-running-ui`'s `finally`), so inline prompts land
on the normal screen just like gum's do today. No change needed there, but
check it during QA.

### Tasks (in order)

Commit each one separately. Each ends in a working tool, since gum keeps
handling whatever isn't ported yet.

1. **Inline renderer and keys.**
   - Add `bb-tty.inline` with both inline and fullscreen modes and the record
     line, and extend the key map.
   - Add the color helpers, background detection and the light/dark palette.
   - Verify with a scratch REPL demo that repaints a changing list, handles
     resize and prints a record. Check the detection in a dark and a light
     terminal theme and inside tmux.
2. **`spin`**: move it to `prompt/spin` on top of `bb-tty.inline`, using
   gum's `dot` frames, 100ms and color 212. Delete `with-spinner`. Since
   ctrl+c now reaches the spinner as a key, also add the "interrupted"
   handling: `main` exits 130 and the TUI's foreground runner returns to the
   TUI.
3. **`confirm`**: port it. It's the most used prompt and exercises the
   renderer, the help bar and the exit semantics.
4. **`choose`**: port it with pagination and dots.
5. **`fuzzy` + `filter`**: fullscreen in the alt screen with a scrolling list,
   then print the inline record after leaving it.
6. **`input`**: build the shared line editor.
7. **`write`**: build the textarea on top of the line editor, with
   `ctrl+e` → `$EDITOR` (write a temp file, run the editor in the foreground,
   read the file back). If the textarea gets hairy, ship `$EDITOR` only first;
   it's a valid stopping point.
8. **Clean up**
   - Remove `bblgum` from `bb.edn` / `deps.edn`.
   - Swap `babashka.json` for the built-in `cheshire.core` (2 files: `github.clj`,
     `bitbucket.clj`). This gets `bb.edn` to zero deps, so `bin/prstack` no
     longer needs `deps/add-deps`, which needs a JVM on first run.
   - Remove `hashp` (it's unused).
   - Update the README requirements.
   - Fix the Bitbucket "ctrl+d" hint.
   - Delete `bb-tty-release-plan.md` if it's still stale.

### Verification
- **Unit tests** (`test/bb_tty/`): `update`/`view` of every prompt fed with
  key sequences, and fuzzy scoring and ordering on a few fixtures.
- **Side-by-side QA against gum:** run the old and new prompt with the same
  input and compare look and keys. Cover:
  - narrow terminals (truncation, no wrapping)
  - resizing mid-prompt
  - the prompt drawn at the very bottom of the screen (forces the scrollback
    to scroll)
  - ctrl+c / esc in each prompt
  - prompts launched from the TUI (merge, then sync)
  - piping (`prstack list | cat`): the spinner must not end up in stdout
  - `NO_COLORS`
  - a light terminal theme, a dark one, and tmux (where the OSC 11 reply may
    never arrive)
  - the record line after submitting, after `esc` and after `ctrl+c`,
    including after leaving the fullscreen `filter`
- Run `clj-kondo --lint src` and `cljfmt check` after each task.

---

## Step 2: Self-contained binary

### Build

1. **Entry point and version**
   - Use `prstack.main/-main` as the uberjar main.
   - Add `resources/PRSTACK_VERSION` (or generate it from the git tag in CI)
     and `prstack --version`.
2. **Add `bb.edn` tasks**
   ```clojure
   :tasks
   {uberjar {:task (shell "bb uberjar target/prstack.jar -m prstack.main")}
    build   {:depends [uberjar]
             :task (shell "sh -c 'cat $(which bb) target/prstack.jar > target/prstack && chmod +x target/prstack'")}}
   ```
   The bb uberjar contains the sources, not AOT-compiled classes. Startup stays
   the same as today.
3. **Check on macOS arm64.** Appending to a signed Mach-O binary can break
   signature checks. If the binary gets killed, run `codesign -s -` on it
   (ad-hoc) after concatenating, and include that in the build.

### CI release (`.github/workflows/release.yml`, on `v*` tags)
- One `ubuntu-latest` job is enough, since nothing is compiled. Steps:
  1. Install bb and build `prstack.jar`.
  2. Download **pinned** bb release tarballs for `macos-aarch64`,
     `macos-amd64`, `linux-amd64-static` and `linux-aarch64-static` (check the
     exact asset names on the babashka releases page).
  3. Concatenate each with the jar and name them `prstack-<version>-<platform>.tar.gz`.
  4. Write `sha256` sums.
  5. `gh release create` with the jar attached as well, for bbin and
     power users.
- macOS ad-hoc signing needs `codesign`, which needs a `macos-latest` runner.
  Either run the macOS concatenation there or use `rcodesign` on Linux.
- Pin the bb version in one place, e.g. `.bb-version`, and bump it on purpose.
  It must be at least the first bb release that bundles JLine.

### Distribution
- **Homebrew tap** (`yannvanhalewyn/homebrew-tap`, `Formula/prstack.rb`):
  `on_macos` / `on_linux` × `on_arm` / `on_intel` URLs and sha256s, with
  `depends_on "gh" => :recommended` and a short `caveats` about jj/gh. Let the
  release workflow bump the formula (e.g. with `mislav/bump-homebrew-formula-action`
  or a small script that commits to the tap).
- **bbin**: `bbin install io.github.yannvanhalewyn/prstack` works for free once
  `bb.edn` has zero deps. Document it as the option for Clojure users.
- **Manual install**: download, untar and put on the PATH. Make this the README
  install section, with Homebrew first.

### Runtime checks (before announcing)
- On startup of commands that need them, check for `gh` (GitHub remotes) and
  `jj`/`git`, and print an install hint instead of a stack trace. This item is
  in `,todos.md`.
- Ask `gh auth status` once when the GitHub remote fails with an auth error.
