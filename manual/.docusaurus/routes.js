import React from 'react';
import ComponentCreator from '@docusaurus/ComponentCreator';

export default [
  {
    path: '/izanami/__docusaurus/debug',
    component: ComponentCreator('/izanami/__docusaurus/debug', '92f'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/config',
    component: ComponentCreator('/izanami/__docusaurus/debug/config', '6f1'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/content',
    component: ComponentCreator('/izanami/__docusaurus/debug/content', 'c4d'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/globalData',
    component: ComponentCreator('/izanami/__docusaurus/debug/globalData', '952'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/metadata',
    component: ComponentCreator('/izanami/__docusaurus/debug/metadata', '933'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/registry',
    component: ComponentCreator('/izanami/__docusaurus/debug/registry', 'fcb'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/routes',
    component: ComponentCreator('/izanami/__docusaurus/debug/routes', '508'),
    exact: true
  },
  {
    path: '/izanami/search',
    component: ComponentCreator('/izanami/search', 'fc4'),
    exact: true
  },
  {
    path: '/izanami/docs',
    component: ComponentCreator('/izanami/docs', '8b6'),
    routes: [
      {
        path: '/izanami/docs',
        component: ComponentCreator('/izanami/docs', '682'),
        routes: [
          {
            path: '/izanami/docs',
            component: ComponentCreator('/izanami/docs', '133'),
            routes: [
              {
                path: '/izanami/docs/clients/',
                component: ComponentCreator('/izanami/docs/clients/', '7b7'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/clients/java/',
                component: ComponentCreator('/izanami/docs/clients/java/', '37a'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/clients/java/cache',
                component: ComponentCreator('/izanami/docs/clients/java/cache', '625'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/clients/java/error-handling',
                component: ComponentCreator('/izanami/docs/clients/java/error-handling', 'd1a'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/clients/java/sse',
                component: ComponentCreator('/izanami/docs/clients/java/sse', '4df'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/concepts/',
                component: ComponentCreator('/izanami/docs/concepts/', '580'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/dev/',
                component: ComponentCreator('/izanami/docs/dev/', '4ea'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/getstarted/',
                component: ComponentCreator('/izanami/docs/getstarted/', '9f8'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/',
                component: ComponentCreator('/izanami/docs/guides/', '7c0'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/audit-logs',
                component: ComponentCreator('/izanami/docs/guides/audit-logs', '9cf'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/bulk-feature-modification',
                component: ComponentCreator('/izanami/docs/guides/bulk-feature-modification', 'f37'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/configuration',
                component: ComponentCreator('/izanami/docs/guides/configuration', '84e'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/env-contexts',
                component: ComponentCreator('/izanami/docs/guides/env-contexts', '3a6'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/http-calls-in-scripts',
                component: ComponentCreator('/izanami/docs/guides/http-calls-in-scripts', 'd9d'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/import-from-v1',
                component: ComponentCreator('/izanami/docs/guides/import-from-v1', 'b45'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/key-configuration',
                component: ComponentCreator('/izanami/docs/guides/key-configuration', 'fb7'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/local-scripts',
                component: ComponentCreator('/izanami/docs/guides/local-scripts', '122'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/mailer-configuration',
                component: ComponentCreator('/izanami/docs/guides/mailer-configuration', 'b6c'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/multi-conditions-feature',
                component: ComponentCreator('/izanami/docs/guides/multi-conditions-feature', '39f'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/non-boolean-features',
                component: ComponentCreator('/izanami/docs/guides/non-boolean-features', 'c1a'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/personnal-access-tokens',
                component: ComponentCreator('/izanami/docs/guides/personnal-access-tokens', '5b1'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/query-builder',
                component: ComponentCreator('/izanami/docs/guides/query-builder', 'b4b'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/remote-script',
                component: ComponentCreator('/izanami/docs/guides/remote-script', 'e46'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/transfer-data',
                component: ComponentCreator('/izanami/docs/guides/transfer-data', '881'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/user-invitation',
                component: ComponentCreator('/izanami/docs/guides/user-invitation', 'af4'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/webhooks',
                component: ComponentCreator('/izanami/docs/guides/webhooks', '821'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/usages/',
                component: ComponentCreator('/izanami/docs/usages/', 'f2d'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/usages/contexts',
                component: ComponentCreator('/izanami/docs/usages/contexts', '7d8'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/usages/features',
                component: ComponentCreator('/izanami/docs/usages/features', '06a'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/usages/organisation',
                component: ComponentCreator('/izanami/docs/usages/organisation', 'd57'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/usages/request',
                component: ComponentCreator('/izanami/docs/usages/request', '944'),
                exact: true,
                sidebar: "tutorialSidebar"
              }
            ]
          }
        ]
      }
    ]
  },
  {
    path: '/izanami/v1',
    component: ComponentCreator('/izanami/v1', '8d3'),
    routes: [
      {
        path: '/izanami/v1',
        component: ComponentCreator('/izanami/v1', '0b2'),
        routes: [
          {
            path: '/izanami/v1',
            component: ComponentCreator('/izanami/v1', '995'),
            routes: [
              {
                path: '/izanami/v1/',
                component: ComponentCreator('/izanami/v1/', '4b6'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/about',
                component: ComponentCreator('/izanami/v1/about', '92c'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/api',
                component: ComponentCreator('/izanami/v1/api', '4d6'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/architecture',
                component: ComponentCreator('/izanami/v1/architecture', '13a'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/authprovider',
                component: ComponentCreator('/izanami/v1/authprovider', '05e'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/clients/',
                component: ComponentCreator('/izanami/v1/clients/', 'b28'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/clients/cli',
                component: ComponentCreator('/izanami/v1/clients/cli', 'f96'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/clients/jvm',
                component: ComponentCreator('/izanami/v1/clients/jvm', '4da'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/clients/node',
                component: ComponentCreator('/izanami/v1/clients/node', 'afe'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/clients/react',
                component: ComponentCreator('/izanami/v1/clients/react', '58a'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/clients/spring-starter',
                component: ComponentCreator('/izanami/v1/clients/spring-starter', 'dfe'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/configs/',
                component: ComponentCreator('/izanami/v1/configs/', 'bf8'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/configs/api',
                component: ComponentCreator('/izanami/v1/configs/api', 'a47'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/configs/springconfig',
                component: ComponentCreator('/izanami/v1/configs/springconfig', '1f0'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/configs/ui',
                component: ComponentCreator('/izanami/v1/configs/ui', '18c'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/developers',
                component: ComponentCreator('/izanami/v1/developers', '811'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/events',
                component: ComponentCreator('/izanami/v1/events', 'f8b'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/experiments/',
                component: ComponentCreator('/izanami/v1/experiments/', '0cf'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/experiments/api',
                component: ComponentCreator('/izanami/v1/experiments/api', '5e6'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/experiments/ui',
                component: ComponentCreator('/izanami/v1/experiments/ui', '22b'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/features',
                component: ComponentCreator('/izanami/v1/features', 'c6a'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/features-flipping/',
                component: ComponentCreator('/izanami/v1/features-flipping/', 'ab5'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/features-flipping/api',
                component: ComponentCreator('/izanami/v1/features-flipping/api', 'e84'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/features-flipping/lock',
                component: ComponentCreator('/izanami/v1/features-flipping/lock', '4e5'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/features-flipping/scripts',
                component: ComponentCreator('/izanami/v1/features-flipping/scripts', '494'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/features-flipping/ui',
                component: ComponentCreator('/izanami/v1/features-flipping/ui', '401'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/getizanami/',
                component: ComponentCreator('/izanami/v1/getizanami/', '68e'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/getizanami/binaries',
                component: ComponentCreator('/izanami/v1/getizanami/binaries', '206'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/getizanami/docker',
                component: ComponentCreator('/izanami/v1/getizanami/docker', '775'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/getizanami/fromsources',
                component: ComponentCreator('/izanami/v1/getizanami/fromsources', '1f1'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/keys',
                component: ComponentCreator('/izanami/v1/keys', 'eb4'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/metrics',
                component: ComponentCreator('/izanami/v1/metrics', '415'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/performances',
                component: ComponentCreator('/izanami/v1/performances', '22b'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/philosophy',
                component: ComponentCreator('/izanami/v1/philosophy', 'e21'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/settings/',
                component: ComponentCreator('/izanami/v1/settings/', '3c2'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/settings/database',
                component: ComponentCreator('/izanami/v1/settings/database', 'f13'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/settings/settings',
                component: ComponentCreator('/izanami/v1/settings/settings', '0b0'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/tutorials/',
                component: ComponentCreator('/izanami/v1/tutorials/', 'd91'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/tutorials/oauth2',
                component: ComponentCreator('/izanami/v1/tutorials/oauth2', '4ba'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/tutorials/spring',
                component: ComponentCreator('/izanami/v1/tutorials/spring', '94e'),
                exact: true,
                sidebar: "defaultSidebar"
              },
              {
                path: '/izanami/v1/ui',
                component: ComponentCreator('/izanami/v1/ui', '95e'),
                exact: true,
                sidebar: "defaultSidebar"
              }
            ]
          }
        ]
      }
    ]
  },
  {
    path: '/izanami/',
    component: ComponentCreator('/izanami/', 'bbb'),
    exact: true
  },
  {
    path: '*',
    component: ComponentCreator('*'),
  },
];
