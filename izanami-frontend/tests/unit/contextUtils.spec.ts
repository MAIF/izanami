/*import { describe, expect, it, test } from "vitest";
import {
  findImpactedProtectedContexts,
  intersection,
  possiblePaths,
} from "../../src/utils/contextUtils";

describe("intersection function", () => {
  it.concurrent(
    "should return an empty result if one of the array is null / undefined",
    () => {
      expect(intersection(null as any, ["a", "b", "c"])).toHaveLength(0);
      expect(intersection(["a", "b", "c"], undefined as any)).toHaveLength(0);
      expect(intersection(undefined as any, null as any)).toHaveLength(0);
    }
  );

  it.concurrent(
    "should return elements present in first and second set",
    () => {
      expect(intersection(["a", "b", "d"], ["a", "b", "c"]).sort()).toEqual(
        ["a", "b"].sort()
      );
    }
  );

  it.concurrent(
    "should return an empty array if there is no intersection",
    () => {
      expect(intersection(["a", "b", "c"], ["d", "e", "f"])).toHaveLength(0);
    }
  );
});

describe("findImpactedProtectedContexts", () => {
  const contexts = [
    {
      name: "prod",
      id: "prod",
      global: false,
      protected: true,
      project: "foo",
      overloads: [],
      children: [
        {
          name: "subprod",
          id: "subprod",
          global: false,
          protected: true,
          project: "foo",
          children: [],
          overloads: [],
        },
      ],
    },
    {
      name: "dev",
      id: "dev",
      global: false,
      protected: false,
      project: "foo",
      overloads: [],
      children: [
        {
          name: "protecteddev",
          id: "protecteddev",
          global: false,
          protected: true,
          project: "foo",
          children: [],
          overloads: [],
        },
      ],
    },
    {
      name: "withoverloads",
      id: "withoverloads",
      global: true,
      protected: true,
      overloads: [
        {
          name: "foo",
          id: "foo",
          enabled: true,
          conditions: [],
          path: "/withoverloads2",
          project: "foo",
          resultType: "boolean",
        },
      ],
      children: [
        {
          name: "subwithoverloads",
          id: "subwithoverloads",
          global: true,
          protected: true,
          overloads: [],
          children: [],
        },
      ],
    },
    {
      name: "withoverloads2",
      id: "withoverloads2",
      global: true,
      protected: true,
      overloads: [
        {
          name: "foo",
          id: "foo",
          enabled: true,
          conditions: [],
          path: "/withoverloads2",
          project: "foo",
          resultType: "boolean",
        },
        {
          name: "foo2",
          id: "foo2",
          enabled: true,
          conditions: [],
          path: "/withoverloads2",
          project: "foo",
          resultType: "boolean",
        },
      ],
      children: [
        {
          name: "subwithoverloads",
          id: "subwithoverloads",
          global: true,
          protected: true,
          overloads: [],
          children: [],
        },
      ],
    },
  ];
  it.concurrent("should return only root protected contexts by default", () => {
    const result = findImpactedProtectedContexts({
      contexts: contexts,
      featureId: "foo",
    });

    expect(result.map((c) => c.name).sort()).toEqual(
      ["prod", "protecteddev"].sort()
    );
  });

  it.concurrent(
    "should return all impacted protected contexts if asked",
    () => {
      const result = findImpactedProtectedContexts({
        contexts: contexts,
        featureId: "foo",
        rootOnly: false,
      });

      expect(result.map((c) => c.name).sort()).toEqual(
        ["prod", "protecteddev", "subprod"].sort()
      );
    }
  );

  it.concurrent("should return impacted context only for given feature", () => {
    const result = findImpactedProtectedContexts({
      contexts: contexts,
      featureId: "foo2",
    });

    expect(result.map((c) => c.name).sort()).toEqual(
      ["prod", "protecteddev", "withoverloads"].sort()
    );
  });

  it.concurrent(
    "should return impacted context starting from given path",
    () => {
      const ctxs = [
        {
          name: "prod",
          id: "prod",
          global: false,
          protected: true,
          project: "foo",
          overloads: [],
          children: [
            {
              name: "subprod",
              id: "subprod",
              global: false,
              protected: true,
              project: "foo",
              overloads: [],
              children: [
                {
                  name: "subsubprod",
                  id: "subsubprod",
                  global: false,
                  protected: true,
                  project: "foo",
                  children: [],
                  overloads: [],
                },
              ],
            },
          ],
        },
      ];
      const result = findImpactedProtectedContexts({
        contexts: ctxs,
        featureId: "bar",
        from: "/prod",
        rootOnly: false,
      });

      expect(result.map((c) => c.name).sort()).toEqual(
        ["subprod", "subsubprod"].sort()
      );
    }
  );
});

describe("possiblePaths", () => {
  const contexts = [
    {
      name: "prod",
      id: "prod",
      global: false,
      protected: true,
      project: "foo",
      overloads: [],
      children: [
        {
          name: "subprod",
          id: "subprod",
          global: false,
          protected: true,
          project: "foo",
          children: [],
          overloads: [],
        },
        {
          name: "anothersubprod",
          id: "anothersubprod",
          global: false,
          protected: true,
          project: "foo",
          children: [],
          overloads: [],
        },
      ],
    },
    {
      name: "dev",
      id: "dev",
      global: false,
      protected: false,
      project: "foo",
      overloads: [],
      children: [
        {
          name: "protecteddev",
          id: "protecteddev",
          global: false,
          protected: true,
          project: "foo",
          children: [],
          overloads: [],
        },
        {
          name: "devchild",
          id: "devchild",
          global: false,
          protected: true,
          project: "foo",
          children: [],
          overloads: [],
        },
      ],
    },
  ];
  it("should return all possibile paths from given context tree", () => {
    const pathes = possiblePaths(contexts);
    expect(pathes.map((c) => c.path).sort()).toEqual(
      [
        "/prod",
        "/prod/subprod",
        "/prod/anothersubprod",
        "/dev",
        "/dev/protecteddev",
        "/dev/devchild",
      ].sort()
    );
  });
});
*/
