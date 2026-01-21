import { docs } from '@/.source/server';

// Simple manual source implementation
export const source = {
  getPage(slugs?: string[]) {
    const slug = slugs?.join('/') || 'index';
    return docs.find((doc: any) => {
      const docSlug = doc.slugs?.join('/') || '';
      return doc.url === `/docs/${slug}` || docSlug === slug;
    });
  },
  
  generateParams() {
    return docs.map((doc: any) => ({ slug: doc.slugs || [] }));
  },
  
  get pageTree() {
    // Return a simple tree structure
    return {
      name: 'Documentation',
      children: docs.map((doc: any) => {
        const data = doc.data || {};
        return {
          type: 'page',
          name: data.title || 'Untitled',
          url: doc.url || '#',
        };
      }).filter(Boolean),
    };
  },
};
