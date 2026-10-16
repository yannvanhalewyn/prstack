# PRStack

[Website](https://prstack.dev) | [Documentation](https://prstack.dev/docs) | [GitHub](https://github.com/yannvanhalewyn/prstack) | [Issues](https://github.com/yannvanhalewyn/prstack/issues)

A CLI and TUI app for effortless PR stack management that embraces the chaos of day-to-day development.

<img width="1091" height="560" alt="prstack-tui" src="https://github.com/user-attachments/assets/cf25d384-5a36-4f17-9d8a-09a4fdda98cb" />

## What is PRStack?

PRStack dynamically discovers your PR stacks by tracing from your current branch back to trunk. No manual configuration, no rigid structures - just work naturally and let PRStack handle the rest.

## Why PRStack?

Traditional PR stack tools require upfront planning and rigid adherence to predefined structures. PRStack recognizes that development is organic. You use it by using your VCS however you want, make branches and pivot freely, and let the tool adapt to you. It attempts to minimize most common actions of creating and merging PRs to a few keystrokes.

It's designed to work with any Version Control System (VCS) and remote repos via Clojure protocols. It currently works with [Jujutsu](https://github.com/martinvonz/jj) and [Git](https://git-scm.com/), and [Github](https://www.github.com) or [Bitbucket Cloud](https://bitbucket.org) as host. The host is detected from the URL of the `origin` remote. It is designed primarily for Jujutsu and I recommend using that one. In the future I may add a plugin system for adding more VCS but for now if you want to use it with something else than Git / Github create an [issue](https://www.github.com/yannvanhalewyn/prstack/issues/new).

## How it works

PRStack infers a Directed Acyclic Graph (DAG) from your commit history, then traverses that graph to find all the paths back to the trunk. Then it offers a set of commands to manage PRs for your stacks integrating with a remote repo like Github. Running `prstack sync` will:

- Discover your stacks automatically
- Detect the stack you are currently working on (`--all` for syncing all stacks)
- Move local trunk branch to match remote
- Prompt to rebase your stacks when trunk changed
- Create PRs with correct head and base branches
- Keep everything in sync

![prstack-sync](https://github.com/user-attachments/assets/bd4a589d-a35e-4fc6-9c85-6d72431e0208)

Learn more about CLI commands in the [Commands Reference](https://prstack.dev/docs/reference/overview).

## Key Features

- **Dynamic Stack Discovery**: No pre-configuration needed - PRStack figures out your stack on the fly
- **TUI & CLI**: Interactive terminal UI or scriptable commands
- **VCS Agnostic**: Works with Jujutsu (Git support coming soon)
- **Adaptive**: Switch branches and PRStack adapts to your new context

## Installation

**Requirements**: [Babashka](https://babashka.org/), [gum](https://github.com/charmbracelet/gum) and, for GitHub repos, the [GitHub CLI](https://cli.github.com/)

```bash
git clone https://github.com/yannvanhalewyn/prstack.git
cd prstack
chmod +x bin/prstack
ln -s $(pwd)/bin/prstack /usr/local/bin/prstack
```

### Bitbucket

Bitbucket Cloud repos talk to the Bitbucket REST API directly, so there's no extra CLI to install. Set up credentials using one of:

- `BITBUCKET_EMAIL` and `BITBUCKET_API_TOKEN`: your Atlassian account email (listed under **Email aliases** in Bitbucket's personal settings) and an API token with the `read:pullrequest:bitbucket` and `write:pullrequest:bitbucket` scopes. API tokens are managed on your Atlassian account, not in Bitbucket's settings:
  1. Go to [id.atlassian.com/manage-profile/security/api-tokens](https://id.atlassian.com/manage-profile/security/api-tokens) (or **Account settings** → **Security** → **Create and manage API tokens**).
  2. Click **Create API token with scopes**. Classic tokens without scopes don't work with Bitbucket.
  3. Pick a name and expiry date, select **Bitbucket** as the app, then select both pull request scopes.
  4. Copy the token right away, it's only shown once.
- `BITBUCKET_ACCESS_TOKEN`: a repository, project or workspace access token with the `pullrequest:write` scope.

#### Keeping the token secret

Avoid putting the token in a plaintext file (like `.prstack/config.edn`, a `.env` file or your shell config) or exporting it globally, where every program you run can read it. Instead, store it in a secret manager and only pass it to `prstack` when it runs.

On macOS, store your Atlassian email and the API token in the Keychain. Leaving `-w` as the last flag makes `security` prompt for the value, so it doesn't end up in your shell history:

```bash
security add-generic-password -a "$USER" -s prstack-bitbucket-email -w
security add-generic-password -a "$USER" -s prstack-bitbucket-api-token -w
```

Then wrap `prstack` so it reads both from the Keychain on every call.

**fish** (`~/.config/fish/functions/prstack.fish`):

```fish
function prstack --wraps prstack
    if set -q BITBUCKET_API_TOKEN
        command prstack $argv
        return
    end
    set -l email (security find-generic-password -a $USER -s prstack-bitbucket-email -w 2>/dev/null)
    set -l token (security find-generic-password -a $USER -s prstack-bitbucket-api-token -w 2>/dev/null)
    if test -z "$email"; or test -z "$token"
        command prstack $argv
    else
        BITBUCKET_EMAIL=$email BITBUCKET_API_TOKEN=$token command prstack $argv
    end
end
```

**bash / zsh** (`~/.bashrc` or `~/.zshrc`):

```bash
prstack() {
  if [ -n "$BITBUCKET_API_TOKEN" ]; then
    command prstack "$@"
    return
  fi
  local email token
  email=$(security find-generic-password -a "$USER" -s prstack-bitbucket-email -w 2>/dev/null)
  token=$(security find-generic-password -a "$USER" -s prstack-bitbucket-api-token -w 2>/dev/null)
  if [ -z "$email" ] || [ -z "$token" ]; then
    command prstack "$@"
  else
    BITBUCKET_EMAIL="$email" BITBUCKET_API_TOKEN="$token" command prstack "$@"
  fi
}
```

The variables are only set for that single `prstack` process. If either Keychain entry is missing (e.g. on a GitHub-only machine), or `BITBUCKET_API_TOKEN` is already set, `prstack` runs unchanged. The first time, macOS may ask whether `security` can access each item; choose "Always Allow" so it doesn't prompt every time.

If you use 1Password, read the token with the [1Password CLI](https://developer.1password.com/docs/cli/) instead, e.g. `op read "op://Private/Bitbucket/credential"`.

## Documentation

Full documentation is available at: **[your-docs-url-here]**

- [Getting Started](your-docs-url/docs/getting-started/quickstart)
- [Philosophy](your-docs-url/docs/philosophy) - How PRStack differs from Git Machete and Graphite
- [Commands Reference](your-docs-url/docs/commands/sync)
- [Configuration](your-docs-url/docs/configuration)

**Work the way you think. Let PRStack handle the rest.**
