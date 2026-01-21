import { source } from '@/lib/source';
import type { Metadata } from 'next';
import { notFound } from 'next/navigation';

export default async function Page(props: {
  params: Promise<{ slug?: string[] }>;
}) {
  const params = await props.params;
  const page: any = source.getPage(params.slug);
  
  if (!page) notFound();

  const Content = page.body;
  const title = page.title || 'Untitled';
  const description = page.description;

  return (
    <article className="prose prose-invert max-w-none">
      <h1 className="text-slate-100">{title}</h1>
      {description && (
        <p className="text-lg text-slate-400 -mt-4 mb-8">{description}</p>
      )}
      <Content />
    </article>
  );
}

export async function generateStaticParams() {
  return source.generateParams();
}

export async function generateMetadata(props: {
  params: Promise<{ slug?: string[] }>;
}): Promise<Metadata> {
  const params = await props.params;
  const page: any = source.getPage(params.slug);
  if (!page) notFound();

  return {
    title: page.title || 'PrStack Docs',
    description: page.description,
  };
}
