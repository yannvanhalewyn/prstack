# PrStack Documentation

This is the documentation website for PrStack, built with [Fumadocs](https://fumadocs.vercel.app/) and Next.js.

## Development

```bash
npm install
npm run dev
```

Visit http://localhost:3000 to see the docs.

## Building

```bash
npm run build
```

This generates a static site in the `out/` directory.

## Preview Build

```bash
npm run build
npx serve out
```

## Content Structure

- `content/docs/` - All documentation content in MDX format
  - `getting-started/` - Quickstart and basic workflow guides
  - `commands/` - CLI command reference
  - Root level files for installation, philosophy, configuration, etc.

## Deployment

### Option 1: Vercel (Recommended)
1. Connect your GitHub repo to Vercel
2. Set root directory to `documentation`
3. Deploy automatically on every push

### Option 2: GitHub Pages
1. Build the site: `npm run build`
2. Push the `out/` directory to `gh-pages` branch
3. Enable GitHub Pages in repo settings

### Option 3: Cloudflare Pages
1. Connect GitHub repo
2. Build command: `cd documentation && npm run build`
3. Output directory: `documentation/out`

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
