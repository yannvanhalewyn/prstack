# Components

Custom React components for the PrStack documentation site.

## CodeExample

A tabbed code example component for displaying Git and Jujutsu alternatives side-by-side.

### Features

- **Persistent preference**: User's VCS choice (Git/Jujutsu) is saved to localStorage and shared across all CodeExample instances
- **Default to Jujutsu**: Since PrStack works best with Jujutsu, it defaults to showing Jujutsu examples first
- **MDX-ready**: Works seamlessly in MDX files

### Usage

#### Simple text examples

```mdx
<CodeExample
  jj={`jj new -m "Add feature"`}
  git={`git checkout -b feature
git commit -m "Add feature"`}
/>
```

#### With code blocks

```mdx
<CodeExample
  jj={
```bash
jj new -m "Add feature"
jj push
```
  }
  git={
```bash
git checkout -b feature
git commit -m "Add feature"
git push
```
  }
/>
```

#### With explanatory text

```mdx
<CodeExample
  jj={
<>
First, create a new change:
```bash
jj new -m "Add feature"
```
Then push it for review:
```bash
prstack push
```
</>
  }
  git={
<>
First, create a new branch:
```bash
git checkout -b feature
git commit -m "Add feature"
```
Then push it:
```bash
git push origin feature
```
</>
  }
/>
```

### Props

| Prop | Type | Description |
|------|------|-------------|
| `jj` | `ReactNode` | Content to display in the Jujutsu tab (strings, code blocks, or JSX) |
| `git` | `ReactNode` | Content to display in the Git tab (strings, code blocks, or JSX) |

### Implementation Details

Built using Fumadocs UI's `<Tabs>` component with:
- `groupId="vcs-preference"` - Shares selection across all CodeExample instances
- `persist` - Saves preference to localStorage
- `defaultIndex={0}` - Defaults to Jujutsu (first tab)

## Mermaid

Component for rendering Mermaid diagrams with custom dark theme styling.

See `Mermaid.tsx` for implementation details.
