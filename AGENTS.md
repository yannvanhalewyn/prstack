# AGENTS.md - Coding Guidelines for prstack

## Project Overview
This is a Clojure CLI tool built with Babashka for managing PR stacks. The main entry point is `bin/prstack`.

## Build/Test/Lint Commands
- **Run the tool**: `./bin/prstack <command>`
- **Format code**: `cljfmt fix` (uses cljfmt.edn config)
- **Lint code**: `clj-kondo --lint src` (uses .clj-kondo/config.edn)
- **No test framework detected** - check with maintainer for test commands

## Code Style Guidelines
- **Language**: Clojure with Babashka
- **Formatting**: Use cljfmt with Cursive-style function argument indentation
- **Imports**: Use `:require` with aliases (e.g., `clojure.string :as str`)
- **Naming**: kebab-case for functions/vars, consistent namespace aliases
- **Error handling**: Use System/exit for CLI errors, colorized output via utils/colorize
- **File structure**: Commands in `src/prstack/commands/`, utilities in `src/prstack/utils.clj`
- **Linting**: Follow clj-kondo rules, use `:lsp/allow-unused` meta for unused public vars
- **CLI patterns**: Commands defined as maps with `:name`, `:description`, `:exec` keys
- **Process execution**: Use `babashka.process` via utils/run-cmd and utils/shell-out
- **Output**: Use colorized terminal output for user feedback