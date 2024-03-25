import { Page } from "@playwright/test";
import { handleFetchJsonResponse } from "../src/utils/queries";

type featureIdByName = [string, string];

export class TestSituationBuilder {
  tenants: TestTenant[] = [];
  static newBuilder(): TestSituationBuilder {
    return new TestSituationBuilder();
  }

  withTenant(tenant: TestTenant): TestSituationBuilder {
    this.tenants.push(tenant);
    return this;
  }

  async buildContextHierarchy(
    page: Page,
    tenant: TestTenant,
    context: TestGlobalContext,
    parent = ""
  ): Promise<any> {
    const cookie = await this.cookie(page);
    await handleFetchJsonResponse(
      fetch(
        `http://localhost:9000/api/admin/tenants/${tenant.name}/contexts${
          parent.length > 0 ? `${parent}` : ""
        }`,
        {
          method: "POST",
          body: JSON.stringify({ name: context.name }),
          headers: {
            "Content-Type": "application/json",
            cookie: cookie,
          },
        }
      )
    ).catch((err) => console.error("Failed to create context", err));

    Promise.all(
      context.subcontexts.map((c) =>
        this.buildContextHierarchy(page, tenant, c, `${parent}/${context.name}`)
      )
    );
  }

  async cookie(page: Page): Promise<string> {
    const cookies = await page.context().cookies();
    return `${cookies[0].name}=${cookies[0].value}`;
  }

  async build(page: Page): Promise<TestSituation> {
    const cookies = await page.context().cookies();
    return Promise.all(
      this.tenants.map((t) =>
        handleFetchJsonResponse(
          fetch(`http://localhost:9000/api/admin/tenants`, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              cookie: `${cookies[0].name}=${cookies[0].value}`,
            },
            body: JSON.stringify(t),
          })
        )
          .then((tenantResult) => {
            return Promise.all(
              this.tenants.flatMap((t) =>
                t.contexts.map((c) => {
                  this.buildContextHierarchy(page, t, c);
                  /*handleFetchJsonResponse(
                    fetch(
                      `http://localhost:9000/api/admin/tenants/${t.name}/contexts`,
                      {
                        method: "POST",
                        body: JSON.stringify({ name: c.name }),
                        headers: {
                          "Content-Type": "application/json",
                          cookie: `${cookies[0].name}=${cookies[0].value}`,
                        },
                      }
                    )
                  ).catch((err) =>
                    console.error("Failed to create context", err)
                  );*/
                })
              )
            );
          })
          .then((contextResult) =>
            Promise.all(
              t.projects.map((p) =>
                handleFetchJsonResponse(
                  fetch(
                    `http://localhost:9000/api/admin/tenants/${t.name}/projects`,
                    {
                      method: "POST",
                      headers: {
                        "Content-Type": "application/json",
                        cookie: `${cookies[0].name}=${cookies[0].value}`,
                      },
                      body: JSON.stringify(p),
                    }
                  )
                ).then((projectResult) =>
                  Promise.all(
                    p.features.map((f) =>
                      handleFetchJsonResponse(
                        fetch(
                          `http://localhost:9000/api/admin/tenants/${t.name}/projects/${p.name}/features`,
                          {
                            method: "POST",
                            body: JSON.stringify(f),
                            headers: {
                              "Content-Type": "application/json",
                              cookie: `${cookies[0].name}=${cookies[0].value}`,
                            },
                          }
                        )
                      ).then(({ id, name }) => [name, id!] as featureIdByName)
                    )
                  )
                )
              )
            )
          )
      )
    )
      .then((pairs) => new Map(pairs.flat().flat()))
      .then((m) => new TestSituation(m))
      .catch((err) => {
        console.log(err);
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

export function testproject(name: string): TestProject {
  return new TestProject(name);
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

class TestTenant {
  name: string;
  projects: TestProject[] = [];
  contexts: TestGlobalContext[] = [];

  constructor(tenant: string) {
    this.name = tenant;
  }

  withProject(project: TestProject): TestTenant {
    this.projects.push(project);
    return this;
  }

  withContext(context: TestGlobalContext): TestTenant {
    this.contexts.push(context);
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
  constructor(name: string) {
    this.name = name;
  }

  withEnableStatus(): TestFeature {
    this.enabled = true;
    return this;
  }

  withDisableStatus(): TestFeature {
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
}

class TestLocalContext implements TestContext {
  name: string;
  subcontexts: TestLocalContext[] = [];

  constructor(name: string) {
    this.name = name;
  }

  withSubContext(context: TestLocalContext): TestLocalContext {
    this.subcontexts.push(context);
    return this;
  }
}
