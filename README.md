# PrStack

A VCS-agnostic CLI tool for effortless PR stack management that adapts to your development workflow.

## Philosophy: Freedom from Fixed Stacks

Unlike traditional PR stack tools like [Git Machete](https://github.com/VirtusLab/git-machete) and [Graphite](https://graphite.dev/), PrStack doesn't require you to pre-define or maintain a fixed stack structure. Instead, it **dynamically discovers your current stack** by tracing from your current position back to the trunk branch.

### Key Differences

| Feature | PrStack | Git Machete | Graphite |
|---------|---------|-------------|----------|
| **Stack Definition** | Dynamic discovery | Pre-defined in `.git/machete` | Explicit stack management |
| **Branching Freedom** | Branch anywhere, anytime | Must follow defined structure | Structured workflow required |
| **Stack Adaptation** | Automatic based on current position | Manual updates needed | Manual stack operations |
| **VCS Support** | Git + Jujutsu | Git only | Git only |

## How It Works

PrStack embraces a **branch-anywhere** philosophy:

1. **Work naturally**: Create branches wherever you need them in your development tree
2. **Dynamic discovery**: Run `prstack sync` from any branch to automatically discover the stack from your current position to trunk
3. **Adaptive management**: Switch to any other branch and run sync again - PrStack adapts to the new context
4. **Automatic PR creation**: Missing PRs are identified and can be created with a single prompt

```bash
# Branch off main for feature A
jj new -m "Feature A"
jj bookmark create feature-a

# Branch off feature-a for feature B
jj new -m "Feature B"
jj bookmark create feature-b

# Branch off feature-b for hotfix
jj new -m "Critical hotfix"
jj bookmark create hotfix

# From hotfix: sync discovers hotfix → feature-b → feature-a → main
prstack sync

# Switch to feature-a and branch off for feature C
jj edit feature-a
jj new -m "Feature C"
jj bookmark create feature-c

# From feature-c: sync discovers feature-c → feature-a → main
prstack sync
```

## VCS Agnostic Design

PrStack supports multiple version control systems:

- **[Jujutsu](https://github.com/martinvonz/jj)**: First-class support for the branchless VCS
- **Git**: Traditional Git workflow support (coming soon)

Since [Jujutsu](https://github.com/martinvonz/jj) is branchless by design, PrStack continues this philosophy by constantly adapting to the current shape of your development tree, rather than forcing you into rigid branch structures.

## Commands

### `prstack sync`
Syncs your current stack with the remote:
- Fetches latest changes from remote
- Updates local trunk to match remote trunk
- Discovers current stack from your position to trunk
- Optionally rebases stack on updated trunk (only if trunk changed)
- Pushes tracked branches
- Offers to create missing PRs

### `prstack list`
Lists the current PR stack from your position to trunk:
```bash
prstack list --include-prs  # Also shows associated PR URLs
```

### `prstack create`
Creates missing PRs for the current stack:
- Discovers stack from current position to trunk
- Identifies missing PRs between adjacent branches
- Prompts to create each missing PR

### `prstack machete`
Exports current stack to `.git/machete` format for compatibility with Git Machete tools.

## Installation

```bash
# Clone the repository
git clone https://github.com/your-username/prstack.git
cd prstack

# Make executable
chmod +x bin/prstack

# Add to PATH or create symlink
ln -s $(pwd)/bin/prstack /usr/local/bin/prstack
```

## Requirements

- [Babashka](https://babashka.org/) - Clojure runtime for the CLI
- [GitHub CLI](https://cli.github.com/) - For PR creation and management

## Why PrStack?

Traditional PR stack tools require upfront planning and rigid adherence to predefined structures. PrStack recognizes that development is organic - you branch where needed, pivot when requirements change, and explore multiple approaches simultaneously.

By dynamically discovering your stack based on your current context, PrStack eliminates the friction of stack management while maintaining the benefits of organized, reviewable changes.

**Work the way you think. Let PrStack handle the rest.**
