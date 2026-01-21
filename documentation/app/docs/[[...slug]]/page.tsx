import { source } from '@/lib/source';
import type { Metadata } from 'next';
import { notFound } from 'next/navigation';

export default async function Page(props: {
  params: Promise<{ slug?: string[] }>;
}) {
  const params = await props.params;
  const page: any = source.getPage(params.slug);
  if (!page) notFound();

  const Content = page.data.exports.default;

  return (
    <article className="prose prose-gray max-w-none">
      <h1>{page.data.title}</h1>
      {page.data.description && (
        <p className="text-lg text-gray-600 -mt-4 mb-8">{page.data.description}</p>
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
    title: page.data.title,
    description: page.data.description,
  };
}
