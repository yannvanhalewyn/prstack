import Link from 'next/link';

export default function HomePage() {
  return (
    <main className="flex h-screen flex-col items-center justify-center text-center px-4">
      <h1 className="mb-4 text-5xl font-bold">PrStack</h1>
      <p className="mb-8 text-xl text-gray-600 max-w-2xl">
        A VCS-agnostic CLI and TUI app for effortless PR stack management that embraces the chaos of day-to-day development.
      </p>
      <div className="flex gap-4">
        <Link
          href="/docs"
          className="rounded-lg bg-blue-600 px-6 py-3 text-white hover:bg-blue-700 font-medium transition"
        >
          Get Started
        </Link>
        <Link
          href="https://github.com/yannvanhalewyn/prstack"
          className="rounded-lg border border-gray-300 px-6 py-3 hover:bg-gray-50 font-medium transition"
        >
          GitHub
        </Link>
      </div>
    </main>
  );
}
