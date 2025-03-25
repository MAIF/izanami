import { TContext } from "./types";

/**
 * return all possible paths extracted from a context list
 * @param contexts context list
 * @param path bas path, used only for recursion
 * @returns an array of objects containing path and associated context (associated context is "leaf" contetxt)
 */
export function possiblePaths(
  contexts: TContext[],
  path = ""
): { path: string; context: TContext }[] {
  return contexts.flatMap((ctx) => {
    if (ctx.children) {
      return [
        ...possiblePaths(ctx.children, path + "/" + ctx.name),
        { context: ctx, path: path + "/" + ctx.name },
      ];
    } else {
      return [];
    }
  });
}
