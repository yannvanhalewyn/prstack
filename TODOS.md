# TODOs

- [x] Verify unpushed branches (*) and prompt to push them.
- [x] Check if branch is pushed before making PR
        It's annoying if you lose the description
- [x] Add a 'ref' option to run command from other leaf
- [x] ~Add ignored branches to local config
- [x] Indicate review status
- [ ] Make PRs scrollable
- [ ] Handle too narrow screen.
        Now ANSI RESET codes are not used
- [ ] Work from MEGAMERGE, be aware of multiple branch paths
- [ ] Add marker indicating current stack when '--all'
- [ ] Support injecting PR between existing PRs
        Base branch of existing PR would need to be changed
        `gh pr edit 123 --base main`
- [ ] Read all reviews and check if any was approved, instead of latest, this in order to support rebases
- [ ] ~Default reviewers in `.prstack.edn`?
- [ ] ~Add `--dry-run` flags to preview actions
- [ ] ~Implement `--yes` flag to skip prompts for automation
- [ ] ~Interesting idea: maybe someday a VS code integration
- [ ] ~Add global config at `~/.config/prstack/config.edn`

Idea: UX improvement, generate a short deterministic stack number / ID and make it so it can refer to it in other commands
Idea: Support other merge bases than main, where multiple PRs are stacked on a merge branch
Idea: merge stack or partial stack. Start a routine that merges and awaits merge status..

# Config idea

```clojure
{:github {:default-reviewers ["@team/reviewers"]
          :draft-prs? false}
 :jujutsu {:log-template "commit_id.short() ++ \" \" ++ description.first_line()"}
 :notifications {:enabled true
                 :success-sound true}}
```

Checkout https://www.textualize.io/blog/7-things-ive-learned-building-a-modern-tui-framework/
