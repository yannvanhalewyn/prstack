import { createMDX } from 'fumadocs-mdx/next';
import sourceConfig from './source.config.ts';

const withMDX = createMDX(sourceConfig);

/** @type {import('next').NextConfig} */
const config = {
  reactStrictMode: true,
  output: 'export',
  trailingSlash: true,
  images: {
    unoptimized: true,
  },
};

export default withMDX(config);
