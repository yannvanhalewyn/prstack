# Mermaid Diagram Color Scheme

The PrStack documentation uses a consistent color scheme for git branch diagrams that makes it easy to distinguish between different types of branches.

## Color Mapping

### Branch Type Colors

| Branch Order | Color | Hex | Typical Use |
|-------------|-------|-----|-------------|
| git0 (1st) | **Gray** | `#8484a8` | **Trunk/Main** - The main production branch |
| git1 (2nd) | **Amber/Yellow** | `#fbbf24` | **Feature Base** - Large feature containers |
| git2 (3rd) | **Purple** | `#c084fc` | Feature branches |
| git3 (4th) | **Cyan** | `#22d3ee` | Feature branches |
| git4 (5th) | **Pink/Magenta** | `#ec4899` | Feature branches |
| git5 (6th) | **Deep Purple** | `#a855f7` | Feature branches |
| git6 (7th) | **Green** | `#10b981` | Feature branches |
| git7 (8th) | **Orange** | `#f59e0b` | Feature branches |

## Visual Example

In a typical feature-base diagram:

```
main (gray) ──────────────────────────
  │
  └─ add-user-management (yellow) ────  ← Feature base
       │
       ├─ api-auth (purple) ──────────  ← Feature branch
       │    │
       │    └─ api-users (cyan) ───────  ← Stacked feature
       │
       ├─ redesign-header (pink) ─────  ← Parallel feature
       │
       └─ redesign-footer (deep purple) ← Parallel feature
```

## Branch Order Guidelines

When creating diagrams, structure them so that:

1. **Main/trunk is always first** - This ensures it gets the gray color
2. **Feature bases are second** - This ensures they get the amber/yellow color
3. **Feature branches follow** - These get the vibrant purple/cyan/pink/green colors

### Example Structure

```mermaid
gitGraph
  commit id: "Initial"        # On main (gray)
  branch add-user-management  # Feature base (yellow)
  commit id: "Setup"
  branch api-auth            # Feature 1 (purple)
  commit id: "Auth"
  branch api-users           # Feature 2 stacked (cyan)
  commit id: "Users"
```

## Tips for Consistent Colors

1. **Branch in order**: Create branches in the logical order (main → feature-base → features)
2. **Checkout strategically**: Use `checkout` commands to return to the base before branching
3. **Test colors**: Render diagrams to verify colors match expectations

## Updating Colors

To change the color scheme, edit:
- `mermaid-config.json` - For CLI rendering (git0-7 values)
- `components/Mermaid.tsx` - For browser rendering (git0-7 in themeVariables)

Both files must have matching color values for consistency.
