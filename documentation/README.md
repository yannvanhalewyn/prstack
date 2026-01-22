# PrStack Documentation

This is the documentation website for PrStack, built with [Fumadocs](https://fumadocs.vercel.app/) and Next.js.

## Development

This project uses **Bun** as the package manager.

```bash
bun install
bun dev
```

Visit http://localhost:3000 to see the docs.

## Building

```bash
bun build
```

This generates a static site in the `out/` directory.

## Preview Static Build

To test the production build locally (important for verifying routing works correctly):

```bash
bun build
bun serve
```

This serves the static build at http://localhost:3000 with proper HTML file resolution.

## Content Structure

- `content/docs/` - All documentation content in MDX format
  - `getting-started/` - Quickstart and basic workflow guides
  - `commands/` - CLI command reference
  - Root level files for installation, philosophy, configuration, etc.

## Deployment

The site is configured with `trailingSlash: true` to generate proper static HTML files (`/route/index.html` structure) that work on all static hosts.

### Option 1: Vercel (Recommended)
1. Connect your GitHub repo to Vercel
2. Set root directory to `documentation`
3. Install command: `bun install`
4. Build command: `bun build`
5. Output directory: `out`
6. Deploy automatically on every push

### Option 2: GitHub Pages
1. Build the site: `bun build`
2. Push the `out/` directory to `gh-pages` branch
3. Enable GitHub Pages in repo settings

### Option 3: Cloudflare Pages
1. Connect GitHub repo
2. Build command: `cd documentation && bun install && bun build`
3. Output directory: `documentation/out`

### Option 4: Netlify
1. Connect GitHub repo
2. Base directory: `documentation`
3. Build command: `bun build`
4. Publish directory: `out`

## Adding New Pages

1. Create a new `.mdx` file in `content/docs/`
2. Add frontmatter with `title` and `description`
3. Write content in MDX
4. Run `npx fumadocs-mdx` to regenerate the source files
5. Build and deploy!

Example:

```mdx
---
title: My New Page
description: A description of my page
---

# My New Page

Content goes here...
```
