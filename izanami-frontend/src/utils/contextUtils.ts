import { hasRightForProject } from "../securityContext";
import {
  TContext,
  TContextOverload,
  TContextWithPath,
  TProjectLevel,
  TUser,
} from "./types";

/**
 * return all possible paths extracted from a context list
 * @param contexts context list
 * @returns an array of objects containing path and associated context (associated context is "leaf" contetxt)
 */
export function possiblePaths(
  contexts: TContext[]
): { path: string; context: TContext }[] {
  return possiblePathsRec(contexts);
}

function possiblePathsRec(
  contexts: TContext[],
  path = ""
): { path: string; context: TContext }[] {
  return contexts.flatMap((ctx) => {
    if (ctx.children) {
      return [
        ...possiblePathsRec(ctx.children, path + "/" + ctx.name),
        { context: ctx, path: path + "/" + ctx.name },
      ];
    } else {
      return [];
    }
  });
}

/**
 * Given context roots, find children of context that matches given path
 * @param contexts context roots
 * @param path patch to match
 * @returns children of context matching given path (if any)
 */
export function getSubtreeMatchingPath(contexts: TContext[], path: string[]) {
  return path.reduce((acc: TContext[], nextPath: string) => {
    const maybeChild = acc.find((c) => c.name === nextPath);

    if (maybeChild) {
      return maybeChild.children;
    } else {
      return [];
    }
  }, contexts);
}

/**
 * Process context tree & extract contexts matching a given predicate
 * @param contexts roots of context tree to explore
 * @param predicate condition to look for, context matching this prediction will be returned
 * @param stopOnMatch if true, prevent going deeper in the context tree when on a context matching predicate. Default is false
 * @param stopCondition if given, this condition will prevent going deeper in the tree when a context match this condition. Context matching this condition WON'T be returned.
 * @param prefix search only among contexts with these prefixes
 * @returns all context matching predicate parameter
 */
export function extractContextsMatching(
  contexts: TContext[],
  predicate: (ctx: TContext) => boolean,
  stopOnMatch: boolean = true,
  stopCondition?: (ctx: TContext) => boolean,
  prefix: string[] = []
): (TContext & { parent: string[] })[] {
  return contexts.flatMap((context) => {
    const res = [];
    if (predicate(context)) {
      if (stopOnMatch) {
        return [{ ...context, parent: prefix }];
      } else {
        res.push({ ...context, parent: prefix });
      }
    } else if (stopCondition?.(context)) {
      return [];
    }

    return res.concat(
      extractContextsMatching(
        context.children,
        predicate,
        stopOnMatch,
        stopCondition,
        prefix.concat(context.name)
      )
    );
  });
}

/**
 * Find all protected contexts that would be impacted by a given feature update.
 * @param contexts all contexts for the project
 * @param featureId id of updated feature
 * @param project feature project
 * @param from path to start from : should be empty if base strategy has been updated, otherwise should contain path of context that has been updated
 * @rootOnly whether only "root" impacted context should be returned, or their children as well.
 * @returns all protected contexts (as a flat array) that would be impacted by the update.
 */
export function findImpactedProtectedContexts({
  contexts,
  featureId,
  project,
  from,
  rootOnly,
}: {
  contexts: TContext[];
  featureId: string;
  project: string;
  from?: string;
  rootOnly?: boolean;
}): TContextWithPath[] {
  const fromTouse = from
    ? from.split("/").filter((part) => part.length > 0)
    : [];

  const contextsToUse = getSubtreeMatchingPath(contexts, fromTouse ?? []);

  return extractContextsMatching(
    contextsToUse,
    (c) => {
      return (
        c.protected &&
        (!c.overloads ||
          c.overloads.length === 0 ||
          c.overloads.every((o) => o.id !== featureId))
      );
    },
    rootOnly,
    (ctx) => {
      return (
        ctx.overloads.some((o) => o.id === featureId) ||
        (ctx.project !== undefined && ctx.project === project)
      );
    }
  ).map((ctx) => {
    return { ...ctx, parent: fromTouse.concat(ctx.parent) };
  });
}

export function intersection(s1: string[], s2: string[]): string[] {
  if (!s1 || !s2) {
    return [];
  }

  return s1.filter((ss1) => s2.includes(ss1));
}

/**
 * Find the context that match given path
 * @param path path (in format "/base/subpath/subsubpath")
 * @param contexts list of contexts
 * @returns the context that matches given path if it exists, null otherwise
 */
export function findContextForPath(
  path: string,
  contexts: TContext[]
): TContext | null {
  const strippedPath = path.startsWith("/") ? path.substring(1) : path;
  const firstSlashIndex = path.indexOf("/");
  const firstPart =
    firstSlashIndex !== -1
      ? strippedPath.substring(0, firstSlashIndex)
      : strippedPath;
  const nextContext = contexts.find((ctx) => ctx.name == firstPart);

  if (nextContext && firstSlashIndex == -1) {
    return nextContext;
  } else if (nextContext) {
    const nextPath = strippedPath.substring(firstSlashIndex + 1);
    return findContextForPath(nextPath, nextContext.children);
  } else {
    return null;
  }
}

/**
 * find overload for given feature among given contexts
 * @param name feature name
 * @param contexts context list into wich overload should be searched
 * @param filter callback function that can be used to filter resulting contexts
 * @returns overloads of given feature among given contexts
 */
export function findOverloadsForFeature(
  name: string,
  contexts: TContext[],
  filter: (ctx: TContext) => boolean = () => true
): TContextOverload[] {
  return findOverloadsForFeatureRec(name, contexts, [], filter);
}

function findOverloadsForFeatureRec(
  name: string,
  contexts: TContext[],
  path: string[],
  filter: (ctx: TContext) => boolean
): TContextOverload[] {
  return contexts.flatMap((ctx) => {
    const maybeOverload = ctx.overloads
      .filter((o) => o.name === name && filter(ctx))
      .map((o) => ({ ...o, path: [...path, ctx.name].join("/") }));
    const childOverloads = findOverloadsForFeatureRec(
      name,
      ctx.children,
      [...path, ctx.name],
      filter
    );

    if (maybeOverload) {
      return [...maybeOverload, ...childOverloads];
    } else {
      return childOverloads;
    }
  });
}

/**
 * return contexts with overloads for the given feature name
 * @param name name of the feature
 * @param contexts context list
 * @returns name of all contexts containing overload for given feature
 */
export function findContextWithOverloadsForFeature(
  name: string,
  contexts: TContext[]
): string[] {
  return findContextWithOverloadsForFeatureRec(name, contexts);
}

function findContextWithOverloadsForFeatureRec(
  name: string,
  contexts: TContext[],
  path = ""
): string[] {
  return contexts.flatMap((ctx) => {
    const hasOverload = ctx.overloads.some((o) => o.name === name);
    const childOverloadsCtx = findContextWithOverloadsForFeatureRec(
      name,
      ctx.children,
      path + "/" + ctx.name
    );

    if (hasOverload) {
      return [path + "/" + ctx.name, ...childOverloadsCtx];
    } else {
      return childOverloadsCtx;
    }
  });
}

export type ImpactAnalysisResult =
  | {
      impactedProtectedContexts: [];
      impactedRootProtectedContexts: [];
      unprotectedUpdateAllowed: true;
    }
  | {
      impactedProtectedContexts: [TContextWithPath, ...TContextWithPath[]];
      impactedRootProtectedContexts: [TContextWithPath, ...TContextWithPath[]];
      unprotectedUpdateAllowed: boolean;
    };

/**
 * Analyse impact of one update to detect one of the following :
 * - update change strategy of protected context, update requires duplicating old strategy
 * - update change strategy of protected context, user is project admin => update requires confirmation
 *
 */
export function analyzeUpdateImpact({
  updatedFeatureId,
  project,
  tenant,
  updatedContext,
  contexts,
  user,
}: {
  updatedFeatureId: string;
  project: string;
  tenant: string;
  updatedContext?: string;
  contexts: TContext[];
  user: TUser;
}): ImpactAnalysisResult {
  const impactedProtectedContexts = findImpactedProtectedContexts({
    contexts: contexts,
    featureId: updatedFeatureId,
    project: project,
    rootOnly: false,
    from: updatedContext,
  });

  if (impactedProtectedContexts.length === 0) {
    return {
      impactedProtectedContexts: [],
      impactedRootProtectedContexts: [],
      unprotectedUpdateAllowed: true,
    };
  }

  const impactedProtectedRootContexts = findImpactedProtectedContexts({
    contexts: contexts,
    featureId: updatedFeatureId,
    project: project,
    rootOnly: true,
    from: updatedContext,
  });

  const isAdmin = hasRightForProject(
    user,
    TProjectLevel.Admin,
    project,
    tenant
  );

  return {
    impactedProtectedContexts: impactedProtectedContexts as [
      TContextWithPath,
      ...TContextWithPath[]
    ],
    unprotectedUpdateAllowed: isAdmin,
    impactedRootProtectedContexts: impactedProtectedRootContexts as [
      TContextWithPath,
      ...TContextWithPath[]
    ],
  };
}

/*export function findStrategyForContext(
  featureId: string,
  context: string[],
  contexts: TContext[]
): TContextOverload | undefined {
  const contextHierarchy = generateContextHierarchyForPath(contexts, context);
  return contextHierarchy
    .find((ctx) => ctx.overloads.find((o) => o.id === featureId))
    ?.overloads.find((o) => o.id === featureId);
}

function generateContextHierarchyForPath(
  contexts: TContext[],
  path: string[]
): TContext[] {
  if (path.length === 0) {
    return [];
  }
  const [pathPart, ...otherParts] = path[0];
  const targetContext = contexts.find((c) => c.name === pathPart);
  if (!targetContext) {
    return [];
  }

  return generateContextHierarchyForPath(
    targetContext.children,
    otherParts
  ).concat(targetContext);
}
*/
