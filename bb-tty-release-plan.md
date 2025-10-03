# bb-tty Release Plan

## Overview
Extract the bb-tty library from prstack and release it as a standalone Babashka terminal library with simple CLI utilities and a reactive TUI framework.

## Phase 1: Extraction & Repository Setup

### 1.1 Create New Repository
```bash
mkdir bb-tty
cd bb-tty
git init
```

### 1.2 Repository Structure
```
bb-tty/
├── README.md
├── bb.edn                  # Babashka project file
├── deps.edn               # Clojure deps for broader compatibility
├── CHANGELOG.md
├── LICENSE                # Consider MIT or EPL-2.0
├── .gitignore
├── src/bb_tty/
│   ├── ansi.clj          # ANSI codes, colors, styling
│   ├── tty.clj           # Terminal operations, CLI utilities
│   └── tui.clj           # Reactive TUI framework
├── examples/
│   ├── hello_world.clj   # Basic CLI colors/prompts
│   ├── progress_bar.clj  # CLI progress indication
│   ├── simple_menu.clj   # Basic TUI menu
│   ├── counter_app.clj   # Reactive TUI example
│   └── file_browser.clj  # Advanced TUI example
├── test/bb_tty/
│   ├── ansi_test.clj
│   ├── tty_test.clj
│   └── tui_test.clj
└── docs/
    ├── api.md
    ├── cli-guide.md
    └── tui-guide.md
```

### 1.3 Extract Files from prstack
1. Copy `src/bb_tty/` directory
2. Remove prstack-specific dependencies:
   - Replace `[prstack.utils :as u]` with inline implementations
   - Extract needed utility functions (shell, vectorize, etc.)
3. Clean up namespace dependencies
4. Add proper docstrings to all public functions

### 1.4 Configuration Files

**bb.edn:**
```clojure
{:deps {org.clojure/clojure {:mvn/version "1.11.1"}}
 :paths ["src"]
 :tasks
 {:requires ([babashka.fs :as fs])
  test {:doc "Run tests"
        :task (shell "clojure -M:test")}
  examples {:doc "Run examples"
            :task (shell "bb examples/hello_world.clj")}}}
```

**deps.edn:**
```clojure
{:deps {org.clojure/clojure {:mvn/version "1.11.1"}}
 :paths ["src"]
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps {lambdaisland/kaocha {:mvn/version "1.87.1366"}}}
  :build {:deps {io.github.clojure/tools.build {:mvn/version "0.9.6"}}
          :ns-default build}}}
```

## Phase 2: Documentation & Examples

### 2.1 README.md Structure
```markdown
# bb-tty

A comprehensive Babashka terminal library providing both simple CLI utilities and a reactive TUI framework.

## Features
- 🎨 ANSI colors and styling
- 💬 Interactive CLI prompts
- 📱 Full-screen reactive TUI framework
- 🔧 Terminal utilities (dimensions, raw mode)
- 📦 Zero external dependencies

## Quick Start - CLI
[CLI examples]

## Quick Start - TUI
[TUI examples]

## Installation
[Clojars coordinates]

## Documentation
- [CLI Guide](docs/cli-guide.md)
- [TUI Guide](docs/tui-guide.md)
- [API Reference](docs/api.md)
```

### 2.2 Example Files Priority
1. **hello_world.clj** - Colors and basic prompts
2. **counter_app.clj** - Showcase reactive TUI
3. **simple_menu.clj** - Navigation example
4. **progress_bar.clj** - CLI utility demo
5. **file_browser.clj** - Advanced TUI showcase

### 2.3 Key Documentation Points
- Emphasize the reactive approach as a differentiator
- Clear separation between CLI utilities vs TUI framework
- Performance characteristics (works with large codebases)
- Comparison with other libraries (blessed, Rich, etc.)

## Phase 3: Testing & Quality

### 3.1 Test Coverage
- Unit tests for all public functions
- Integration tests for TUI components
- Terminal compatibility tests
- Examples as executable tests

### 3.2 Code Quality
- Add comprehensive docstrings
- Clean up any remaining prstack dependencies
- Consistent error handling
- Performance optimization for TUI rendering

## Phase 4: Release Process

### 4.1 Version Strategy
- `0.1.0` - Initial release (CLI utilities stable)
- `0.2.0` - TUI framework stable
- `0.3.0` - Advanced TUI features
- `1.0.0` - Stable API, production ready

### 4.2 Clojars Deployment
1. Create Clojars account
2. Set up deployment credentials
3. Create `pom.xml` or use tools.build
4. Deploy with proper versioning

### 4.3 Release Checklist
- [ ] All tests passing
- [ ] Examples working
- [ ] Documentation complete
- [ ] CHANGELOG updated
- [ ] Version tagged in git
- [ ] Deployed to Clojars
- [ ] README updated with coordinates

## Phase 5: Community & Adoption

### 5.1 Announcement Strategy
1. **Babashka Slack** - Primary audience
2. **ClojureVerse** - Community discussion
3. **Reddit r/Clojure** - Broader Clojure community
4. **Twitter/X** - Tag @borkdude and Clojure community
5. **Clojure Weekly** - Newsletter submission

### 5.2 Initial Marketing Points
- "First comprehensive TUI library for Babashka"
- "Reactive approach to terminal interfaces"
- "Zero dependency, works anywhere Babashka runs"
- "Both simple CLI utilities and full TUI framework"

### 5.3 Future Development
- Gather community feedback
- Add requested features
- Improve performance
- Consider advanced TUI components (tables, forms, etc.)
- Documentation site with interactive examples

## Dependencies to Extract from prstack

### From prstack.utils
- `vectorize` function
- `shell` and `shell-out` functions
- `indexed` function
- `consecutive-pairs` function
- `find-first` function
