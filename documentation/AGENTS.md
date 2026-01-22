# AGENTS.md - Coding Guidelines for prstack Documentation

## Project Overview
This is a Next.js 16 documentation site for PrStack, built with TypeScript, Fumadocs (MDX documentation framework), and TailwindCSS 4. The site uses static export for simple deployment and is managed with Bun as the package manager.

**Main Project:** https://github.com/yannvanhalewyn/prstack (Clojure/Babashka CLI tool)  
**This Repository:** Documentation site for the PrStack tool

## Build/Dev/Test Commands

### Package Manager
Use **Bun** for all package management operations:
- `bun install` - Install dependencies
- `bun add <package>` - Add a new dependency
- `bun add -d <package>` - Add a dev dependency
- `bun remove <package>` - Remove a dependency

### Development Commands
- `bun dev` - Start development server (http://localhost:3000)
- `bun build` - Build for production (outputs to `./out/` directory)
- `bun serve` - Serve static export locally (uses `serve` package on port 3000)

### Build Artifacts
- **Development:** `.next/` directory (gitignored)
- **Production:** `./out/` directory (static export)
- **Generated:** `.source/` directory (Fumadocs MDX compilation output)

### Testing
**No testing framework is currently configured.** If tests are needed:
- Consider adding Vitest for unit/component tests
- Consider Playwright for E2E testing
- Add test scripts to package.json: `"test": "vitest"` or similar

### Linting & Formatting
**No linting/formatting tools are currently configured.** If needed:
- Consider adding ESLint: `bun add -d eslint eslint-config-next`
- Consider adding Prettier: `bun add -d prettier`
- Add scripts: `"lint": "eslint ."` and `"format": "prettier --write ."`

## Code Style Guidelines

### TypeScript Configuration
- **Strict mode enabled** - All TypeScript strict checks are enforced
- **Target:** ES2020 with ESNext modules
- **Module resolution:** Bundler (Next.js optimized)
- **Path aliases:** Use `@/*` to reference root directory files
  ```typescript
  import { source } from '@/lib/source';  // ✅ Good
  import { source } from '../lib/source'; // ❌ Avoid relative paths from root
  ```

### Import Style
1. **Separate type imports** using `import type`:
   ```typescript
   import type { ReactNode, Metadata } from 'react';
   import { useState } from 'react';
   ```

2. **Import order** (recommended):
   - Type imports first
   - External dependencies (React, Next.js, etc.)
   - Internal imports using `@/*` alias
   - Relative imports (if unavoidable)

3. **Use ES modules** exclusively (no CommonJS):
   ```typescript
   export default function Component() {}  // ✅ Good
   module.exports = Component;             // ❌ Never use
   ```

### Naming Conventions
- **Components:** PascalCase - `Layout`, `DocsPage`, `NavItem`
- **Files:**
  - Component files: PascalCase or lowercase Next.js conventions - `layout.tsx`, `page.tsx`
  - Utility files: lowercase kebab-case - `source.ts`, `utils.ts`
  - MDX content: lowercase kebab-case - `basic-workflow.mdx`, `quick-start.mdx`
  - Config files: lowercase - `next.config.mjs`, `tailwind.config.js`
- **Variables/Functions:** camelCase - `pageTree`, `generateMetadata`, `getPageData`
- **Constants:** UPPER_SNAKE_CASE for true constants - `API_URL`, `MAX_ITEMS`
- **Types/Interfaces:** PascalCase - `DocsLayoutProps`, `PageParams`, `Metadata`

### Component Patterns
1. **Use functional components** with TypeScript:
   ```typescript
   export default function Layout({ children }: { children: ReactNode }) {
     return <div>{children}</div>;
   }
   ```

2. **Prefer Server Components** (Next.js App Router default):
   ```typescript
   // No 'use client' directive = Server Component ✅
   export default async function Page() {
     const data = await fetchData();
     return <div>{data}</div>;
   }
   ```

3. **Use 'use client' only when necessary** (for interactivity, hooks, browser APIs):
   ```typescript
   'use client';
   
   import { useState } from 'react';
   
   export default function InteractiveComponent() {
     const [state, setState] = useState(0);
     return <button onClick={() => setState(s => s + 1)}>{state}</button>;
   }
   ```

4. **Inline prop types for simple components**:
   ```typescript
   function Button({ label, onClick }: { label: string; onClick: () => void }) {
     return <button onClick={onClick}>{label}</button>;
   }
   ```

5. **Named interfaces for complex props**:
   ```typescript
   interface ComplexComponentProps {
     title: string;
     items: Array<{ id: string; name: string }>;
     onSelect: (id: string) => void;
     className?: string;
   }
   
   function ComplexComponent(props: ComplexComponentProps) {
     // ...
   }
   ```

### Next.js Conventions
1. **File-based routing** in `app/` directory:
   - `page.tsx` - Route page component
   - `layout.tsx` - Shared layout component
   - `[[...slug]]` - Optional catch-all dynamic route

2. **Async components for data fetching**:
   ```typescript
   export default async function Page({ params }: { params: { slug: string[] } }) {
     const data = await fetchData(params.slug);
     return <article>{data.content}</article>;
   }
   ```

3. **Export metadata** for SEO:
   ```typescript
   export const metadata: Metadata = {
     title: 'Page Title',
     description: 'Page description',
   };
   ```

4. **Generate static params** for dynamic routes:
   ```typescript
   export function generateStaticParams() {
     return [{ slug: ['page-1'] }, { slug: ['page-2'] }];
   }
   ```

### Styling Guidelines
1. **Use Tailwind utility classes** as the primary styling method:
   ```typescript
   <div className="flex min-h-screen bg-white dark:bg-gray-900">
     <aside className="w-64 border-r p-4">...</aside>
   </div>
   ```

2. **Tailwind v4 CSS-based configuration** - All configuration is in `app/global.css`:
   ```css
   @import "tailwindcss";
   @import "@tailwindcss/typography";
   
   @source "../../app/**/*.{ts,tsx}";
   @source "../../content/**/*.{md,mdx}";
   
   @theme {
     /* Custom theme configuration goes here */
   }
   ```
   - **No `tailwind.config.js` file** - v4 uses CSS-based configuration
   - Use `@source` directives to specify content paths
   - Use `@theme` blocks for custom design tokens

3. **Fumadocs CSS variables** for theming (when using Fumadocs components):
   - `fd-primary` - Primary color
   - `fd-border` - Border color
   - `fd-background` - Background color

4. **Typography plugin** for prose content:
   ```typescript
   <article className="prose prose-lg max-w-none">
     {content}
   </article>
   ```

5. **Avoid inline styles** unless absolutely necessary for dynamic values

### MDX Content Guidelines
1. **Frontmatter structure**:
   ```yaml
   ---
   title: Page Title
   description: Brief description for SEO and navigation
   ---
   ```

2. **Content organization**:
   - Place all docs in `content/docs/` directory
   - Use folders for logical groupings (`getting-started/`, `commands/`)
   - Create `meta.json` files to control navigation order

3. **Navigation metadata** (`meta.json`):
   ```json
   {
     "title": "Section Title",
     "pages": ["page-1", "page-2"],
     "---": "Separator"
   }
   ```

4. **File naming**: Use lowercase kebab-case for all MDX files

### Error Handling
1. **Type-safe error handling**:
   ```typescript
   try {
     const data = await fetchData();
   } catch (error) {
     if (error instanceof Error) {
       console.error('Failed to fetch:', error.message);
     }
   }
   ```

2. **Next.js error boundaries**: Create `error.tsx` files for error UI
3. **Graceful fallbacks**: Use optional chaining and nullish coalescing
   ```typescript
   const items = data?.items ?? [];
   const name = user?.profile?.name || 'Anonymous';
   ```

### Code Organization
```
documentation/
├── app/                      # Next.js App Router
│   ├── layout.tsx           # Root layout (HTML, providers)
│   ├── page.tsx             # Home/landing page
│   ├── global.css           # Global styles (Tailwind imports)
│   └── docs/
│       ├── layout.tsx       # Docs layout with sidebar
│       └── [[...slug]]/
│           └── page.tsx     # Dynamic doc page renderer
├── content/
│   └── docs/                # MDX documentation content
│       ├── *.mdx            # Top-level pages
│       ├── meta.json        # Navigation metadata
│       └── */               # Subsections with own meta.json
├── lib/                     # Shared utilities and configurations
│   └── source.ts           # Fumadocs source configuration
├── .source/                 # Generated (gitignored or auto-generated)
└── public/                  # Static assets (images, fonts, etc.)
```

### Best Practices
1. **Avoid `any` types** - Use `unknown` and type guards instead:
   ```typescript
   // ❌ Bad
   const data: any = fetchData();
   
   // ✅ Good
   const data: unknown = fetchData();
   if (typeof data === 'object' && data !== null) {
     // Safe to use data
   }
   ```

2. **Prefer const over let** - Use `const` by default, `let` only when reassignment is needed
3. **Async/await over promises** - More readable asynchronous code
4. **Destructure props** for clarity:
   ```typescript
   function Component({ title, description }: Props) { // ✅ Good
     return <div>{title}</div>;
   }
   ```

5. **Keep components small** - Single responsibility, extract reusable parts
6. **Co-locate related code** - Keep components, types, and utilities together when possible

### Static Export Configuration
This site is configured for static export (`output: 'export'` in `next.config.mjs`):
- **No server-side runtime** - All pages must be statically generated
- **Images must be unoptimized** - `unoptimized: true` for next/image
- **API routes not supported** - Use external APIs or build-time data fetching
- **Trailing slash enabled** - `trailingSlash: true` generates `/route/index.html` structure
  - This ensures proper routing when deployed to static hosts
  - Routes like `/docs` will generate `out/docs/index.html` instead of `out/docs.html`
  - Most static servers (Netlify, Vercel, GitHub Pages, etc.) handle this structure correctly
- **Deploy anywhere** - Output can be served by any static host

## Documentation Workflow
1. **Add new documentation pages**:
   - Create MDX file in `content/docs/` (or subdirectory)
   - Add frontmatter with title and description
   - Update `meta.json` to include page in navigation

2. **Preview changes**:
   - Run `bun dev` and navigate to http://localhost:3000
   - Hot reload will reflect MDX changes immediately

3. **Build for production**:
   - Run `bun build` to generate static site in `./out/`
   - Verify build completes without errors
   - Deploy `./out/` directory to static host

## Related Files & Configurations
- **Next.js:** `next.config.mjs` (static export, MDX setup)
- **TypeScript:** `tsconfig.json` (strict mode, path aliases)
- **Tailwind:** `app/global.css` (v4 CSS-based config with @source and @theme)
- **Fumadocs:** `source.config.ts` (docs directory configuration)
- **PostCSS:** `postcss.config.mjs` (Tailwind v4 processing)
