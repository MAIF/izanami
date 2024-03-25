import React from 'react';
import ComponentCreator from '@docusaurus/ComponentCreator';

export default [
  {
    path: '/izanami/__docusaurus/debug',
    component: ComponentCreator('/izanami/__docusaurus/debug', 'ab4'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/config',
    component: ComponentCreator('/izanami/__docusaurus/debug/config', '71e'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/content',
    component: ComponentCreator('/izanami/__docusaurus/debug/content', '7bd'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/globalData',
    component: ComponentCreator('/izanami/__docusaurus/debug/globalData', '1a4'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/metadata',
    component: ComponentCreator('/izanami/__docusaurus/debug/metadata', 'd94'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/registry',
    component: ComponentCreator('/izanami/__docusaurus/debug/registry', '49c'),
    exact: true
  },
  {
    path: '/izanami/__docusaurus/debug/routes',
    component: ComponentCreator('/izanami/__docusaurus/debug/routes', '108'),
    exact: true
  },
  {
    path: '/izanami/search',
    component: ComponentCreator('/izanami/search', 'b82'),
    exact: true
  },
  {
    path: '/izanami/docs',
    component: ComponentCreator('/izanami/docs', 'e75'),
    routes: [
      {
        path: '/izanami/docs',
        component: ComponentCreator('/izanami/docs', '29f'),
        routes: [
          {
            path: '/izanami/docs',
            component: ComponentCreator('/izanami/docs', 'd8a'),
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
                path: '/izanami/docs/clients/java/api',
                component: ComponentCreator('/izanami/docs/clients/java/api', 'b59'),
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
                path: '/izanami/docs/guides/bulk-feature-modification',
                component: ComponentCreator('/izanami/docs/guides/bulk-feature-modification', '693'),
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
                path: '/izanami/docs/guides/global-local-contexts',
                component: ComponentCreator('/izanami/docs/guides/global-local-contexts', 'ea4'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/import-from-v1',
                component: ComponentCreator('/izanami/docs/guides/import-from-v1', 'f7a'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/key-configuration',
                component: ComponentCreator('/izanami/docs/guides/key-configuration', '380'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/mailer-configuration',
                component: ComponentCreator('/izanami/docs/guides/mailer-configuration', '182'),
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
                path: '/izanami/docs/guides/query-builder',
                component: ComponentCreator('/izanami/docs/guides/query-builder', 'b4b'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/user-invitation',
                component: ComponentCreator('/izanami/docs/guides/user-invitation', 'f85'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/wasmo-configuration',
                component: ComponentCreator('/izanami/docs/guides/wasmo-configuration', '143'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/guides/wasmo-script',
                component: ComponentCreator('/izanami/docs/guides/wasmo-script', 'b56'),
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
                component: ComponentCreator('/izanami/docs/usages/contexts', 'f0a'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/usages/features',
                component: ComponentCreator('/izanami/docs/usages/features', '33e'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/usages/organisation',
                component: ComponentCreator('/izanami/docs/usages/organisation', '0b2'),
                exact: true,
                sidebar: "tutorialSidebar"
              },
              {
                path: '/izanami/docs/usages/request',
                component: ComponentCreator('/izanami/docs/usages/request', 'd5d'),
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
    path: '/izanami/',
    component: ComponentCreator('/izanami/', '89a'),
    exact: true
  },
  {
    path: '*',
    component: ComponentCreator('*'),
  },
];
