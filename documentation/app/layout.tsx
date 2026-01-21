import './global.css';
import type { ReactNode } from 'react';
import { Kanit } from 'next/font/google';

const kanit = Kanit({
  weight: ['300', '400', '500', '600', '700'],
  subsets: ['latin'],
  display: 'swap',
});

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body className={kanit.className}>{children}</body>
    </html>
  );
}
