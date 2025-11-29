import { Cookie, Page } from "@playwright/test";
import {
  handleFetchJsonResponse,
  handleFetchWithoutResponse,
} from "../src/utils/queries";
import { TLevel } from "../src/utils/types";
import { backendUrl, logAs } from "./utils";

type featureIdByName = [string, string];
export const DEFAULT_TEST_PASSWORD = "notARealPassword";

export class TestSituationBuilder {
  tenants: TestTenant[] = [];
  users: TestUser[] = [];
  connectedUser?: string;
  static newBuilder(): TestSituationBuilder {
    return new TestSituationBuilder();
  }

  withUser(user: TestUser): TestSituationBuilder {
    this.users.push(user);
    return this;
  }

  withTenant(tenant: TestTenant): TestSituationBuilder {
    this.tenants.push(tenant);
    return this;
  }

  loggedAs(username: string): TestSituationBuilder {
    this.connectedUser = username;
    return this;
  }

  async buildLocalContextHierarchy(
    page: Page,
    tenant: TestTenant,
    project: TestProject,
    context: TestLocalContext,
    url: string,
    parent = ""
  ): Promise<any> {
    const cookie = await this.cookie(page);
    await handleFetchJsonResponse(
      fetch(
        `${url}/api/admin/tenants/${tenant.name}/projects/${
          project.name
        }/contexts${parent.length > 0 ? `/${parent}` : ""}`,
        {
          method: "POST",
          body: JSON.stringify({
            name: context.name,
            protected: context.isProtected,
          }),
          headers: {
            "Content-Type": "application/json",
            cookie: cookie,
          },
        }
      )
    ).catch((err) => console.error("Failed to create context", err));

    Promise.all(
      context.subcontexts.map((c) =>
        this.buildLocalContextHierarchy(
          page,
          tenant,
          project,
          c,
          url,
          `${parent}/${context.name}`
        )
      )
    );
  }

  async createUser(page: Page, user: TestUser, baseUrl: string): Promise<any> {
    const cookie = await this.cookie(page);
    const email = `${user.name}@imaginarymail.com`;
    return fetch(`${baseUrl}/api/admin/invitation`, {
      body: JSON.stringify({
        email,
        admin: user.admin,
        rights: { tenants: user.rights },
      }),
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        cookie: cookie,
      },
    })
      .then((resp) => {
        return resp;
      })
      .then((userResponse) => userResponse.json())
      .then((json) => {
        console.log("json", json);
        const url = json.invitationUrl;
        const token = url.split("token=")[1];
        return fetch(`${baseUrl}/api/admin/users`, {
          body: JSON.stringify({
            username: user.name,
            password: DEFAULT_TEST_PASSWORD,
            token,
          }),
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
        });
      });
  }

  async buildContextHierarchy(
    page: Page,
    tenant: TestTenant,
    context: TestGlobalContext,
    url: string,
    parent = ""
  ): Promise<any> {
    const cookie = await this.cookie(page);
    await handleFetchJsonResponse(
      fetch(
        `${url}/api/admin/tenants/${tenant.name}/contexts${
          parent.length > 0 ? `${parent}` : ""
        }`,
        {
          method: "POST",
          body: JSON.stringify({
            name: context.name,
            protected: context.isProtected,
          }),
          headers: {
            "Content-Type": "application/json",
            cookie: cookie,
          },
        }
      )
    ).catch((err) => console.error("Failed to create context", err));

    Promise.all(
      context.subcontexts.map((c) =>
        this.buildContextHierarchy(
          page,
          tenant,
          c,
          url,
          `${parent}/${context.name}`
        )
      )
    );
  }

  async cookie(page: Page): Promise<string> {
    const cookies = await page.context().cookies();
    return `${cookies[0].name}=${cookies[0].value}`;
  }

  async createTenant(
    tenant: TestTenant,
    cookie: Cookie,
    url: string
  ): Promise<any> {
    return handleFetchJsonResponse(
      fetch(`${url}/api/admin/tenants`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          cookie: `${cookie.name}=${cookie.value}`,
        },
        body: JSON.stringify(tenant),
      })
    );
  }

  async createTag(
    tenant: TestTenant,
    tag: TestTag,
    cookie: Cookie,
    url: string
  ): Promise<any> {
    return handleFetchJsonResponse(
      fetch(`${url}/api/admin/tenants/${tenant.name}/tags`, {
        method: "POST",
        body: JSON.stringify(tag),
        headers: {
          "Content-Type": "application/json",
          cookie: `${cookie.name}=${cookie.value}`,
        },
      })
    );
  }

  async createProject(
    tenant: TestTenant,
    project: TestProject,
    cookie: Cookie,
    url: string
  ): Promise<any> {
    return handleFetchJsonResponse(
      fetch(`${url}/api/admin/tenants/${tenant.name}/projects`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          cookie: `${cookie.name}=${cookie.value}`,
        },
        body: JSON.stringify(project),
      })
    );
  }

  async createFeature(
    tenant: TestTenant,
    project: TestProject,
    feature: TestFeature,
    cookie: Cookie,
    url: string
  ): Promise<any> {
    return handleFetchJsonResponse(
      fetch(
        `${url}/api/admin/tenants/${tenant.name}/projects/${project.name}/features`,
        {
          method: "POST",
          body: JSON.stringify(feature),
          headers: {
            "Content-Type": "application/json",
            cookie: `${cookie.name}=${cookie.value}`,
          },
        }
      )
    );
  }

  async createKey(
    tenant: TestTenant,
    key: TestKey,
    cookie: Cookie,
    url: string
  ): Promise<any> {
    return handleFetchJsonResponse(
      fetch(`${url}/api/admin/tenants/${tenant.name}/keys`, {
        method: "POST",
        body: JSON.stringify(key),
        headers: {
          "Content-Type": "application/json",
          cookie: `${cookie.name}=${cookie.value}`,
        },
      })
    );
  }

  async createWebhook(
    tenant: TestTenant,
    webhook: TestWebhook,
    cookie: Cookie,
    url: string
  ): Promise<any> {
    return handleFetchJsonResponse(
      fetch(`${url}/api/admin/tenants/${tenant.name}/webhooks`, {
        method: "POST",
        body: JSON.stringify(webhook),
        headers: {
          "Content-Type": "application/json",
          cookie: `${cookie.name}=${cookie.value}`,
        },
      })
    );
  }

  async createOverload(
    tenant: TestTenant,
    project: TestProject,
    feature: TestFeature,
    overload: TestOverload,
    cookie: Cookie,
    url: string
  ): Promise<any> {
    const { contextPath, ...rest } = overload;

    return handleFetchWithoutResponse(
      fetch(
        `${url}/api/admin/tenants/${tenant.name}/projects/${
          project.name
        }/contexts${contextPath ? `/${contextPath}` : ""}/features/${
          feature.name
        }`,
        {
          method: "PUT",
          body: JSON.stringify(rest),
          headers: {
            "Content-Type": "application/json",
            cookie: `${cookie.name}=${cookie.value}`,
          },
        }
      )
    );
  }

  async build(page: Page): Promise<TestSituation> {
    const cookie = (await page.context().cookies())[0];
    const url = backendUrl();
    return Promise.all(
      this.tenants.map((t) =>
        this.createTenant(t, cookie, url)
          .catch((err) => {
            console.error("Failed to create tenant", err);
            throw err;
          })
          .then(() => {
            Promise.all(
              t.contexts.map((c) => {
                return this.buildContextHierarchy(page, t, c, url);
              })
            ).catch((err) => {
              console.error("Failed to create context hierarchy", err);
              throw err;
            });
          })
          .then(() => {
            return Promise.all(
              t.webhooks.map((webhook) =>
                this.createWebhook(t, webhook, cookie, url)
              )
            ).catch((err) => {
              console.error("Failed to create webhooks", err);
              throw err;
            });
          })
          .then(() =>
            Promise.all(
              t.tags.map((tag) => this.createTag(t, tag, cookie, url))
            ).catch((err) => {
              console.error("Failed to create tags", err);
              throw err;
            })
          )
          .then(() => {
            return Promise.all(
              t.keys.map((key) => this.createKey(t, key, cookie, url))
            ).catch((err) => {
              console.error("Failed to create keys", err);
              throw err;
            });
          })
          .then(() =>
            Promise.all(
              t.projects.map((p) =>
                this.createProject(t, p, cookie, url)
                  .catch((err) => {
                    console.error("Failed to create project", err);
                    throw err;
                  })
                  .then(() =>
                    Promise.all(
                      p.contexts.map((c) =>
                        this.buildLocalContextHierarchy(page, t, p, c, url)
                      )
                    ).catch((err) => {
                      console.error(
                        "Failed to build local context hierarchy",
                        err
                      );
                      throw err;
                    })
                  )
                  .then(() =>
                    Promise.all(
                      p.features.map((f) =>
                        this.createFeature(t, p, f, cookie, url)
                          .catch((err) => {
                            console.error("Failed to create feature", err);
                            throw err;
                          })
                          .then(
                            ({ id, name }) => [name, id!] as featureIdByName
                          )
                          .then((featureIds) => {
                            return Promise.all(
                              f.overloads.map((o) =>
                                this.createOverload(t, p, f, o, cookie, url)
                              )
                            )
                              .catch((err) => {
                                console.error(
                                  "Failed to create overloads",
                                  err
                                );
                                throw err;
                              })
                              .then(() => featureIds);
                          })
                      )
                    )
                  )
              )
            )
          )
      )
    )
      .then((pairs) => {
        return Promise.all(
          this.users.map((user) => this.createUser(page, user, url))
        )
          .catch((err) => {
            console.error("Failed to create users", err);
            throw err;
          })
          .then(() => pairs);
      })
      .then((pairs) => {
        if (this.connectedUser) {
          return logAs(page, this.connectedUser)
            .catch((err) => {
              console.error("Failed to login as asked user", err);
              throw err;
            })
            .then(() => pairs);
        }
        return pairs;
      })
      .then((pairs) => new Map(pairs.flat().flat()))
      .then((m) => new TestSituation(m))
      .catch((err) => {
        console.log("err", err);
        throw err;
      });
  }
}

class TestSituation {
  featuresIds: Map<string, string> = new Map();

  constructor(featuresIds: Map<string, string>) {
    this.featuresIds = featuresIds;
  }
}

export function testBuilder(): TestSituationBuilder {
  return new TestSituationBuilder();
}

export function testTenant(name: string): TestTenant {
  return new TestTenant(name);
}

export function testProject(name: string): TestProject {
  return new TestProject(name);
}

export function testWebhook(name: string): TestWebhook {
  return new TestWebhook(name);
}

export function testFeature(name: string): TestFeature {
  return new TestFeature(name);
}

export function testGlobalContext(name: string): TestGlobalContext {
  return new TestGlobalContext(name);
}

export function testLocalContext(name: string): TestLocalContext {
  return new TestLocalContext(name);
}

export function testTag(name: string, description?: string): TestTag {
  return new TestTag(name, description);
}

export function testKey(data: Partial<TestKey> & { name: string }): TestKey {
  return new TestKey(data);
}

export function testUser(name: string, admin: boolean = false) {
  return new TestUser(name, admin);
}

export function testTenantRight(level: TLevel): TestTenantRight {
  return new TestTenantRight(level);
}

export function testOverload(
  contextPath: string,
  enabled = false
): TestOverload {
  return new TestOverload(contextPath, enabled);
}

class TestTag {
  name: string;
  description?: string;

  constructor(name: string, description: string = "") {
    this.name = name;
    this.description = description;
  }
}

class TestKey {
  name: string;
  description?: string;
  enabled: boolean;
  admin: boolean;
  projects?: string[];
  constructor(data: Partial<TestKey> & { name: string }) {
    this.name = data.name;
    this.description = data.description || "";
    this.enabled = data.enabled || true;
    this.admin = data.admin || false;
    this.projects = data.projects || [];
  }
}

class TestProjectRight {
  level: TLevel;
  constructor(level: TLevel) {
    this.level = level;
  }
}

class TestTenantRight {
  level: TLevel;
  projects: { [x: string]: TestProjectRight };

  constructor(level: TLevel) {
    this.level = level;
    this.projects = {};
  }

  withProjectRight(project: string, level: TLevel): TestTenantRight {
    this.projects[project] = new TestProjectRight(level);
    return this;
  }
}

class TestUser {
  name: string;
  admin: boolean;
  rights: { [x: string]: TestTenantRight };

  constructor(name: string, admin: boolean) {
    this.name = name;
    this.admin = admin;
    this.rights = {};
  }

  withTenantRight(name: string, right: TestTenantRight): TestUser {
    this.rights[name] = right;
    return this;
  }
}

class TestTenant {
  name: string;
  projects: TestProject[] = [];
  contexts: TestGlobalContext[] = [];
  webhooks: TestWebhook[] = [];
  tags: TestTag[] = [];
  keys: TestKey[] = [];

  constructor(tenant: string) {
    this.name = tenant;
  }

  withTag(tag: TestTag): TestTenant {
    this.tags.push(tag);
    return this;
  }

  withKey(key: TestKey): TestTenant {
    this.keys.push(key);
    return this;
  }

  withProject(project: TestProject): TestTenant {
    this.projects.push(project);
    return this;
  }

  withContext(context: TestGlobalContext): TestTenant {
    this.contexts.push(context);
    return this;
  }

  withWebhook(webhook: TestWebhook): TestTenant {
    this.webhooks.push(webhook);
    return this;
  }
}

class TestWebhook {
  name: string;
  url: string = "http://localhost:9999";
  enabled: boolean = false;
  global: boolean = true;
  features: string[] = [];
  projects: string[] = [];
  user: string = "";

  constructor(name: string) {
    this.name = name;
  }

  withUrl(url: string): TestWebhook {
    this.url = url;
    return this;
  }

  withEnableStatus(): TestWebhook {
    this.enabled = true;
    return this;
  }

  withDisableStatus(): TestWebhook {
    this.enabled = false;
    return this;
  }

  withGlobalScope(): TestWebhook {
    this.global = true;
    this.features = [];
    this.projects = [];
    return this;
  }

  withFeature(feature: string): TestWebhook {
    this.global = false;
    this.features.push(feature);
    return this;
  }

  withProject(project: string): TestWebhook {
    this.global = false;
    this.projects.push(project);
    return this;
  }

  withUser(user: string): TestWebhook {
    this.user = user;
    return this;
  }
}

class TestProject {
  name: string;
  features: TestFeature[] = [];
  contexts: TestLocalContext[] = [];
  constructor(name: string) {
    this.name = name;
  }

  withFeature(feature: TestFeature): TestProject {
    this.features.push(feature);
    return this;
  }

  withContext(context: TestLocalContext): TestProject {
    this.contexts.push(context);
    return this;
  }
}

class TestFeature {
  name: string;
  enabled = false;
  overloads: TestOverload[] = [];
  resultType: string = "boolean";
  value: any = null;

  constructor(name: string, enabled = false, overloads: TestOverload[] = []) {
    this.name = name;
    this.enabled = enabled;
    this.overloads = overloads;
  }

  withEnableStatus(): TestFeature {
    this.enabled = true;
    return this;
  }

  withDisableStatus(): TestFeature {
    this.enabled = false;
    return this;
  }

  withOverload(overload: TestOverload): TestFeature {
    this.overloads.push(overload);
    return this;
  }
}

class TestOverload {
  contextPath: string;
  enabled = false;
  resultType: string = "boolean";

  constructor(contextPath: string, enabled = false) {
    this.contextPath = contextPath;
    this.enabled = enabled;
  }

  withEnableStatus(): TestOverload {
    this.enabled = true;
    return this;
  }

  withDisableStatus(): TestOverload {
    this.enabled = false;
    return this;
  }
}

interface TestContext {
  name: string;
}

class TestGlobalContext implements TestContext {
  name: string;
  subcontexts: TestGlobalContext[] = [];
  localSubContexts: TestLocalContext[] = [];
  isProtected: boolean;

  constructor(name: string) {
    this.name = name;
  }

  withSubContext(context: TestGlobalContext): TestGlobalContext {
    this.subcontexts.push(context);
    return this;
  }

  withLocalSubContext(context: TestLocalContext): TestGlobalContext {
    this.localSubContexts.push(context);
    return this;
  }

  withProtectedStatus(isProtected: boolean): TestGlobalContext {
    this.isProtected = isProtected;
    return this;
  }
}

class TestLocalContext implements TestContext {
  name: string;
  isProtected: boolean;
  subcontexts: TestLocalContext[] = [];

  constructor(name: string) {
    this.name = name;
  }

  withSubContext(context: TestLocalContext): TestLocalContext {
    this.subcontexts.push(context);
    return this;
  }

  withProtectedStatus(isProtected: boolean): TestLocalContext {
    this.isProtected = isProtected;
    return this;
  }
}
