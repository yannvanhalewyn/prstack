# TODOs

- [x] Verify unpushed branches (*) and prompt to push them.
- [x] Check if branch is pushed before making PR
        It's annoying if you lose the description
- [x] Add a 'ref' option to run command from other leaf
- [x] ~Add ignored branches to local config
- [ ] Indicate review status
- [ ] Work from MEGAMERGE, be aware of multiple branch paths
- [ ] Add marker indicating current stack when '--all'
- [ ] ~Default reviewers in `.prstack.edn`?
- [ ] ~Add `--dry-run` flags to preview actions
- [ ] ~Implement `--yes` flag to skip prompts for automation
- [ ] ~Interesting idea: maybe someday a VS code integration
- [ ] ~Add global config at `~/.config/prstack/config.edn`

Idea: UX improvement, print stack number and make it so it can refer to it in other commands
Idea: Support other merge bases than main, where multiple PRs are stacked on a merge branch

# Config idea

```clojure
{:github {:default-reviewers ["@team/reviewers"]
          :draft-prs? false}
 :jujutsu {:log-template "commit_id.short() ++ \" \" ++ description.first_line()"}
 :notifications {:enabled true
                 :success-sound true}}
```

Checkout https://www.textualize.io/blog/7-things-ive-learned-building-a-modern-tui-framework/


# Videos

- PRStack overview
- Jujutsu Megamerges (and Prstack integration)
- Clojure with tmux and neovim

## Clojure video

- Show off marks and evaluating something in commented code. Maybe use the reitit-ring-coercion example. There is a failing and working case, bind both
