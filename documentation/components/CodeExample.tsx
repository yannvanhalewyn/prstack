import type { ReactNode } from 'react';
import { Tab, Tabs } from 'fumadocs-ui/components/tabs';

export interface CodeExampleProps {
  jj: ReactNode;
  git: ReactNode;
}

/**
 * A component for displaying code examples with Git and Jujutsu alternatives.
 * The user's preference is persisted across the documentation site.
 * 
 * Usage in MDX:
 * ```mdx
 * <CodeExample
 *   jj={`jj new -m "Add user authentication"`}
 *   git={`git checkout -b feature/auth
 * git commit -m "Add user authentication"`}
 * />
 * ```
 */
export function CodeExample({ jj, git }: CodeExampleProps) {
  return (
    <Tabs 
      items={['Jujutsu', 'Git']} 
      groupId="vcs-preference"
      persist
      defaultIndex={0}
    >
      <Tab value="Jujutsu">{jj}</Tab>
      <Tab value="Git">{git}</Tab>
    </Tabs>
  );
}
