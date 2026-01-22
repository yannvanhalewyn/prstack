import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      title: 'PrStack',
      transparentMode: 'top',
    },
    githubUrl: 'https://github.com/yannvanhalewyn/prstack',
    links: [
      {
        text: 'Documentation',
        url: '/docs',
      },
    ],
    themeSwitch: {
      enabled: false,
    },
    searchToggle: {
      enabled: false,
    },
  };
}

