# Release Plan

## Status

Step 1 is merged (PR #8). Step 2 is in progress, starting with task 1 (entry
point and `--version`).

Run the tests with:

```
bb -e "(babashka.classpath/add-classpath \"test\") (require 'clojure.test) (doseq [ns '[bb-tty.keys-test bb-tty.ansi-test bb-tty.inline-test bb-tty.prompt-test bb-tty.fuzzy-test bb-tty.text-input-test bb-tty.text-area-test prstack.tui.db-test prstack.tui.rows-test prstack.stack-test prstack.vcs.graph-test prstack.vcs-test]] (require ns)) (let [{:keys [fail error]} (apply clojure.test/run-tests (filter #(re-find #\"-test$\" (str %)) (map ns-name (all-ns))))] (System/exit (+ fail error)))"
```

`prstack.vcs-test` creates repos in `tmp/`, which a sandbox may block.
Interactive prompts need a real terminal and can't be tested in a sandbox.

Three steps:

1. **Drop gum.** Rebuild the prompts on top of `bb-tty`, copying gum's look and
   key bindings. After this the only runtime requirement is `bb` (plus `gh` /
   `jj` / `git`, which we can't avoid).
2. **Release on top of bb.** Publish the prstack uberjar on GitHub Releases and
   install it via a Homebrew tap that pulls in `bb` as a dependency. This is
   how borkdude ships neil, his own bb tool.
3. **Self-contained binaries (later, optional).** Append the uberjar to the
   `bb` binary for people who don't use Homebrew. Only after step 2 has
   shipped.

Step 2 only starts once step 1 is merged. A release that still needs `gum` on
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
   - The fuzzy port is checked against the Go library's output
     (`test/bb_tty/fuzzy_reference.edn`).
   - The shared line editor (`bb-tty.text-input`) is built here already,
     since `filter` needs it.
   - **Typing resets the cursor to the best match.** gum keeps the cursor
     index, only clamping it to the number of matches.
   - **`ctrl+j/k/n/p` always navigate.** In gum they also reach the text
     input, so `ctrl+k` deleted the rest of the query and moved up at the
     same time.
6. **`input`**: wire up the line editor from task 5.
7. **`write`**: build the textarea on top of the line editor, with
   `ctrl+e` → `$EDITOR` (write a temp file, run the editor in the foreground,
   read the file back). If the textarea gets hairy, ship `$EDITOR` only first;
   it's a valid stopping point.
   - **Keys are now read from stdin directly** (`bb-tty.keys` parses them),
     instead of with JLine's `BindingReader`. JLine 4.4.0 (bundled with bb)
     reads on a background thread to support timeouts. After a timeout (a
     spinner, the wait after esc) that thread keeps blocking on stdin, and
     takes the next key from the next prompt or from a program like the
     editor or `gh`. Polling `System/in` with `available()` never leaves a
     read behind.
   - Bracketed paste is enabled while a prompt runs, so pasted newlines don't
     submit `write`.
   - The Bitbucket "ctrl+d to submit" hint is fixed.
8. **Clean up** (done, except `bb-tty-release-plan.md`, which is kept: it's a
   plan for extracting bb-tty, not a stale doc, and bb-tty no longer depends
   on prstack). `hashp` moved to the `:dev` alias of `deps.edn`, as
   `dev/user.clj` requires it. The README requires bb 1.12.216 or newer, the
   first release with the JLine classes bb-tty uses.
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

## Step 2: Release on top of bb

### How other Clojure tools do it

- **clj-kondo and jet** are compiled with GraalVM `native-image`, once per
  platform. That doesn't apply here: prstack is a bb script and has to run on
  bb.
- **neil** is a bb tool like prstack. Its Homebrew formula has
  `depends_on "borkdude/brew/babashka"` and installs the script. It also has a
  `:bbin/bin` entry in `bb.edn`.
- borkdude's tap (`borkdude/homebrew-brew`) keeps a `<tool>.template` and an
  `update-<tool>` script per formula. The script reads the latest tag,
  downloads the release assets, computes the sha256 sums and fills in the
  template with `sed`. The tap isn't bumped by the release workflow.
- Homebrew core has no `babashka` formula, so the dependency is
  `borkdude/brew/babashka`.

The self-contained `cat bb uber.jar` binary is borkdude's documented approach
(wiki: "Self-contained executable"). It's moved to step 3 because of the macOS
signing findings listed there.

### Tasks (in order)

Commit each one separately.

1. **Entry point and version.**
   - `prstack.main/-main` is the uberjar main.
   - Add `resources/PRSTACK_VERSION` (starting at `0.1.0`) and
     `prstack --version` / `-V`. Add `"resources"` to `:paths` in `bb.edn`
     and `deps.edn`.
   - `bin/prstack` already adds every `:paths` entry to the classpath, so
     running from a clone keeps working.
   - Verify: `./bin/prstack --version`, `bb -m prstack.main --version`.
2. **Uberjar task.**
   ```clojure
   :tasks
   {uberjar {:doc "Build target/prstack.jar"
             :task (shell "bb uberjar target/prstack.jar -m prstack.main")}}
   ```
   - The bb uberjar contains the sources, not AOT-compiled classes. Startup
     stays the same as today.
   - Verify: `bb uberjar`, then `unzip -l target/prstack.jar`. Copy the jar out
     of the repo and run `bb prstack.jar --version` and `bb prstack.jar list`
     from another repository.
   - `bb uberjar` writes to `java.io.tmpdir`. In a sandbox, pass
     `-Djava.io.tmpdir=$TMPDIR`.
3. **bb version check.** The jar runs on whatever `bb` the user has. At
   startup, compare `(System/getProperty "babashka.version")` against
   1.12.216 (the first release with the JLine classes bb-tty uses) and print
   an upgrade hint instead of a stack trace.
   - Verify: fake a lower version in a REPL, or run on an old bb release
     downloaded to `$TMPDIR`.
4. **Runtime checks.**
   - On startup of commands that need them, check for `gh` (GitHub remotes)
     and `jj`/`git`, and print an install hint instead of a stack trace. This
     item is in `,todos.md`.
   - Ask `gh auth status` once when the GitHub remote fails with an auth
     error.
   - Verify without touching your setup: `env PATH=/usr/bin:/bin` hides `gh`
     and `jj`. `env GH_CONFIG_DIR=(mktemp -d)` makes `gh` act logged out.
5. **CI release** (`.github/workflows/release.yml`, on `v*` tags).
   - One `ubuntu-latest` job: install bb, `bb uberjar`, rename the jar to
     `prstack-<version>.jar`, write its sha256, and
     `gh release create` with both attached.
   - Check that the tag matches `resources/PRSTACK_VERSION` and fail
     otherwise.
   - Tags with `-rc` are created as pre-releases.
   - Verify: push `v0.1.0-rc1`, watch it with `gh run watch`, then
     `gh release download v0.1.0-rc1` and `shasum -a 256 -c`. Delete the rc
     release and tag afterwards.
6. **Homebrew tap** (`yannvanhalewyn/homebrew-tap`, `Formula/prstack.rb`).
   ```ruby
   class Prstack < Formula
     desc "Manage stacks of pull requests with Jujutsu or Git"
     homepage "https://github.com/yannvanhalewyn/prstack"
     url "https://github.com/yannvanhalewyn/prstack/releases/download/v0.1.0/prstack-0.1.0.jar"
     sha256 "..."
     license "MIT" # whichever license the repo picks

     depends_on "borkdude/brew/babashka"

     def install
       libexec.install "prstack-#{version}.jar" => "prstack.jar"
       (bin/"prstack").write <<~EOS
         #!/bin/bash
         exec "#{Formula["borkdude/brew/babashka"].opt_bin}/bb" "#{libexec}/prstack.jar" "$@"
       EOS
     end

     def caveats
       "prstack uses jj or git, and gh for GitHub remotes."
     end

     test do
       assert_match version.to_s, shell_output("#{bin}/prstack --version")
     end
   end
   ```
   - Start with borkdude's setup: `prstack.template` plus an `update-prstack`
     script, run by hand after a release. Automating the bump from the release
     workflow (it needs a token with write access to the tap) can come later.
   - Verify: `brew install yannvanhalewyn/tap/prstack`, `brew test prstack`,
     `brew audit --strict yannvanhalewyn/tap/prstack`. Try it first with the
     rc jar.
7. **bbin.** Add `:bbin/bin {prstack {:main-opts ["-m" "prstack.main"]}}` to
   `bb.edn`, so `bbin install io.github.yannvanhalewyn/prstack` works.
   - Verify: `bbin install .` from the repo, then `prstack --version`.
8. **README.** The install section lists Homebrew first, then bbin, then
   manual: install bb, download the jar and run `bb prstack.jar` (or an
   alias), or clone the repo as today.
   - Verify: follow the README word for word in a clean setting (a Docker
     container, or a second macOS user).
9. **License.** The repo has no `LICENSE` file yet. Without one, nobody is
   allowed to use or share the code, and `brew audit` flags the formula. Pick
   one (MIT and EPL-1.0 are common for Clojure tools) before the release.
10. **Release `v0.1.0`.**

---

## Step 3: Self-contained binaries (later, optional)

Append the uberjar to the `bb` binary for each platform:
`cat bb prstack.jar > prstack && chmod +x prstack`. The jar is the same on
every platform, so one Linux job can build all of them from pinned bb release
tarballs (`macos-aarch64`, `macos-amd64`, `linux-amd64-static`,
`linux-aarch64-static`).

**Findings on macOS arm64** (bb 1.13.220, tested with a hello-world jar):
- The concatenated binary **runs** without re-signing.
- `codesign -v` fails with `main executable failed strict validation`, and
  `codesign -s - --force` fails with the same error. Ad-hoc re-signing is
  **not** a fix, because Apple's tools reject data appended after the Mach-O.
- Untested: a copy downloaded through a browser gets the quarantine flag, and
  Gatekeeper will probably block it because the signature doesn't validate.
  Users would need `xattr -d com.apple.quarantine prstack`. Downloads through
  `curl` and Homebrew don't get the flag. Also untested: installing such a
  binary through Homebrew.

Test the browser download and a Homebrew install of the binary before
publishing these. Linux has no signing, so the Linux binaries are the
low-risk part.
