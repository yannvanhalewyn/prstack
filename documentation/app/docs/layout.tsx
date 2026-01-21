import type { ReactNode } from 'react';
import { source } from '@/lib/source';
import Link from 'next/link';

export default function Layout({ children }: { children: ReactNode }) {
  const tree = source.pageTree;
  const items = (tree as any).children || [];
  
  return (
    <div className="flex min-h-screen bg-slate-900">
      <aside className="w-64 border-r border-slate-700 bg-slate-800 p-4">
        <Link href="/" className="text-xl font-bold mb-4 block text-blue-400 hover:text-blue-300">PrStack</Link>
        <nav>
          {items.map((item: any) => (
            <div key={item.name} className="mb-6">
              {item.type === 'folder' ? (
                <div>
                  <div className="font-semibold text-sm uppercase text-slate-400 mb-1">
                    {item.name}
                  </div>
                  {item.children?.map((child: any) => (
                    <Link
                      key={child.url}
                      href={child.url}
                      className="block py-1 px-2 text-sm text-slate-300 hover:bg-slate-700 hover:text-white rounded"
                    >
                      {child.name}
                    </Link>
                  ))}
                </div>
              ) : (
                <Link
                  href={item.url}
                  className="block py-1 px-2 text-sm text-slate-300 hover:bg-slate-700 hover:text-white rounded"
                >
                  {item.name}
                </Link>
              )}
            </div>
          ))}
        </nav>
      </aside>
      <main className="flex-1 p-8 max-w-4xl mx-auto bg-slate-900">
        {children}
      </main>
    </div>
  );
}
