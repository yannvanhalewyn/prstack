import Link from 'next/link';

export default function HomePage() {
  return (
    <main className="flex h-screen flex-col items-center justify-center text-center px-4">
      <h1 className="mb-4 text-4xl font-bold">PrStack</h1>
      <p className="mb-8 text-xl text-fd-muted-foreground max-w-2xl">
        A VCS-agnostic CLI and TUI app for effortless PR stack management that embraces the chaos of day-to-day development.
      </p>
      <div className="flex gap-4">
        <Link
          href="/docs"
          className="rounded-lg bg-fd-primary px-6 py-3 text-fd-primary-foreground hover:bg-fd-primary/90 font-medium"
        >
          Get Started
        </Link>
        <Link
          href="https://github.com/your-username/prstack"
          className="rounded-lg border border-fd-border px-6 py-3 hover:bg-fd-accent font-medium"
        >
          GitHub
        </Link>
      </div>
    </main>
  );
}
