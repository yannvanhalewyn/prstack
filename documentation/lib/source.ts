import { docs } from '@/.source/server';

// Map file paths to slugs
function pathToSlug(path: string): string[] {
  // Remove .mdx extension and split by /
  const clean = path.replace(/\.mdx$/, '');
  if (clean === 'index') return [];
  return clean.split('/');
}

// Simple manual source implementation
export const source = {
  getPage(slugs?: string[]) {
    const targetSlug = (slugs || []).join('/') || 'index';
    
    return docs.find((doc: any) => {
      // Extract the file path from the collection
      const filePath = doc.info?.path || '';
      const docSlug = pathToSlug(filePath).join('/') || 'index';
      return docSlug === targetSlug;
    });
  },
  
  generateParams() {
    return docs.map((doc: any) => {
      const filePath = doc.info?.path || '';
      const slugArray = pathToSlug(filePath);
      return { slug: slugArray.length > 0 ? slugArray : undefined };
    });
  },
  
  get pageTree() {
    // Organize docs by folder
    const folders: any = {
      root: [],
      'getting-started': [],
      'commands': [],
    };

    docs.forEach((doc: any) => {
      const filePath = doc.info?.path || '';
      const slugs = pathToSlug(filePath);
      const item = {
        type: 'page',
        name: doc.title || 'Untitled',
        url: `/docs${slugs.length > 0 ? '/' + slugs.join('/') : ''}`,
      };

      if (filePath.startsWith('commands/')) {
        folders.commands.push(item);
      } else if (filePath.startsWith('getting-started/')) {
        folders['getting-started'].push(item);
      } else {
        folders.root.push(item);
      }
    });

    return {
      name: 'Documentation',
      children: [
        ...folders.root,
        {
          type: 'folder',
          name: 'Getting Started',
          children: folders['getting-started'],
        },
        {
          type: 'folder',
          name: 'Commands',
          children: folders.commands,
        },
      ].filter(item => item.type !== 'folder' || (item.children && item.children.length > 0)),
    };
  },
};
