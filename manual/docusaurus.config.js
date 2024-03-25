// @ts-check
// `@type` JSDoc annotations allow editor autocompletion and type checking
// (when paired with `@ts-check`).
// There are various equivalent ways to declare your Docusaurus config.
// See: https://docusaurus.io/docs/api/docusaurus-config

import { themes as prismThemes } from "prism-react-renderer";

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: "Izanami",
  tagline: "イザナミ",
  favicon: "img/_izanami-mini.svg",

  // Set the production url of your site here
  url: "https://maif.github.io",
  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: "/izanami/",

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: "MAIF", // Usually your GitHub org/user name.
  projectName: "Daikoku", // Usually your repo name.

  onBrokenLinks: "throw",
  onBrokenMarkdownLinks: "warn",

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: "en",
    locales: ["en"],
  },

  presets: [
    [
      "classic",
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: "./sidebars.js",
          // Please change this to your repo.
          // Remove this to remove the "edit this page" links.
          versions: {
            current: {
              label: `2.x.x`,
            },
          },
        },
        blog: {
          showReadingTime: true,
          // Please change this to your repo.
          // Remove this to remove the "edit this page" links.
        },
        theme: {
          customCss: "./src/css/custom.css",
        },
      }),
    ],
  ],
  plugins: [
    [
      "@docusaurus/plugin-content-docs",
      {
        id: "v1",
        path: "v1",
        routeBasePath: "v1",
        //sidebarPath: "./sidebarsv1.js",
        // ... other options
      },
    ],
  ],
  themes: [
    [
      /** @type {import("@easyops-cn/docusaurus-search-local").PluginOptions} */
      "@easyops-cn/docusaurus-search-local",
      {
        hashed: true,
        language: ["en", "zh"],
        highlightSearchTermsOnTargetPage: true,
        explicitSearchResultPath: true,
      },
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      // Replace with your project's social card
      image: "img/izanami-mini.jpg",
      navbar: {
        title: "Izanami",
        logo: {
          alt: "Izanami logo",
          className: "header-logo",
          src: "img/izanami-mini-dark.svg",
          srcDark: "img/izanami-mini-light.svg",
        },
        items: [
          {
            type: "docSidebar",
            sidebarId: "tutorialSidebar",
            position: "left",
            label: "Documentation",
          },
          //{ to: "/blog", label: "Blog", position: "left" },
          {
            type: "docsVersionDropdown",
            position: "right",
            dropdownActiveClassDisabled: true,
            dropdownItemsAfter: [
              {
                type: "html",
                value: '<hr class="dropdown-separator">',
              },
              {
                type: "html",
                className: "dropdown-archived-versions",
                value: "<b>V1 doc</b>",
              },
              {
                href: "/v1",
                label: "1.11.0",
              },
              {
                type: "html",
                value: '<hr class="dropdown-separator">',
              },
            ],
          },
          {
            href: "https://github.com/maif/izanami",
            position: "right",
            className: "header-github-link",
            "aria-label": "GitHub repository",
          },
        ],
      },
      footer: {
        style: "dark",
        links: [
          {
            title: "MAIF",
            items: [
              {
                label: "OSS by MAIF",
                to: "https://maif.github.io/",
              },
            ],
          },
          {
            title: "Community",
            items: [
              {
                label: "Discord",
                href: "https://discord.gg/vUP2eKYu",
              },
              {
                label: "Gitter",
                href: "https://app.gitter.im/#/room/#MAIF_izanami:gitter.im",
              },
            ],
          },
          {
            title: "More",
            items: [
              {
                label: "GitHub",
                href: "https://github.com/maif/izanami",
              },
              {
                label: "Download",
                href: "https://github.com/MAIF/izanami/releases/latest",
              },
            ],
          },
        ],
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ["json", "java", "rego", "yaml", "scala", "gradle", "bash"],
      },
    }),
};

export default config;
