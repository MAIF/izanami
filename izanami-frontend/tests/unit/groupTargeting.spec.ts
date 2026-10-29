import { afterEach, describe, expect, it, vi } from "vitest";
import { isUserListRule, TCompleteFeature } from "../../src/utils/types";
import { testExistingFeature, testFeature } from "../../src/utils/queries";

afterEach(() => vi.unstubAllGlobals());

describe("group targeting", () => {
  it("recognizes group-only and existing user list rules", () => {
    expect(isUserListRule({ groups: ["beta"] })).toBe(true);
    expect(isUserListRule({ users: ["alice"] })).toBe(true);
    expect(isUserListRule({ percentage: 50 })).toBe(false);
    expect(isUserListRule({})).toBe(false);
  });

  it("encodes repeated groups separately from the user in both test endpoints", async () => {
    const fetch = vi.fn().mockImplementation(() =>
      Promise.resolve(Response.json({ active: true })),
    );
    vi.stubGlobal("fetch", fetch);
    const feature: TCompleteFeature = {
      name: "flag",
      description: "",
      enabled: true,
      tags: [],
      resultType: "boolean",
      conditions: [{ id: "condition", rule: { groups: ["beta,+&"] } }],
    };
    const date = new Date("2026-01-01T10:00:00Z");
    const groups = ["beta,+&", "", " spaced "];
    await testFeature("tenant", feature, "alice&bob", date, {}, groups);
    await testExistingFeature("tenant", "id", date, "test", "alice&bob", "{}", groups);

    expect(fetch).toHaveBeenCalledTimes(2);
    for (const [url] of fetch.mock.calls) {
      const parsed = new URL(url, "http://localhost");
      expect(parsed.searchParams.get("user")).toBe("alice&bob");
      expect(parsed.searchParams.getAll("groups")).toEqual(groups);
    }
    expect(fetch.mock.calls[0][1].body).toContain('"groups":["beta,+&"]');
  });

  it("keeps groups optional for existing feature test callers", async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ active: false }));
    vi.stubGlobal("fetch", fetch);
    await testExistingFeature("tenant", "id", new Date(), "", "alice");
    const url = new URL(fetch.mock.calls[0][0], "http://localhost");
    expect(url.searchParams.get("user")).toBe("alice");
    expect(url.searchParams.has("groups")).toBe(false);
  });
});
