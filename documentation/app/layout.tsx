import './global.css';
import type { ReactNode } from 'react';
import { Lato } from 'next/font/google';

const lato = Lato({
  weight: ['300', '400', '700', '900'],
  subsets: ['latin'],
  display: 'swap',
});

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body className={lato.className}>{children}</body>
    </html>
  );
}
