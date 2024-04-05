import * as React from "react";
import { FeatureContexts } from "../components/FeatureContexts";
import { useMutation, useQuery } from "react-query";
import {
  createContext,
  deleteContext,
  projectContextKey,
  projectQueryKey,
  queryContextsForProject,
  queryProject,
  updateFeatureActivationForContext,
} from "../utils/queries";
import queryClient from "../queryClient";
import {
  TCondition,
  TContext,
  TContextOverload,
  TLevel,
  TWasmConfig,
} from "../utils/types";
import { useProjectRight } from "../securityContext";
import { OverloadTable } from "../components/FeatureTable";
import { OverloadCreationForm } from "../components/OverloadCreationForm";
import { useParams } from "react-router-dom";
import { useState } from "react";
import { Loader } from "../components/Loader";

export function ProjectContexts(props: {
  tenant: string;
  project: string;
  open?: string;
}) {
  const { tenant, project, open } = props;

  const deleteContextMutation = useMutation(
    (data: { tenant: string; project: string; path: string }) => {
      const { tenant, project, path } = data;
      return deleteContext(tenant, project, path);
    },
    {
      onSuccess: () => {
        queryClient.invalidateQueries(projectContextKey(tenant, project));
      },
    }
  );

  const createContextMutation = useMutation(
    (data: { tenant: string; project: string; path: string; name: string }) => {
      const { tenant, project, path, name } = data;
      return createContext(tenant, project, path, name);
    },
    {
      onSuccess: () =>
        queryClient.invalidateQueries(projectContextKey(tenant, project)),
    }
  );

  const modificationRight = useProjectRight(tenant, project, TLevel.Write);

  return (
    <>
      <FeatureContexts
        allowGlobalContextDelete={false}
        open={open ? JSON.parse(open) : []}
        deleteContext={(path) =>
          deleteContextMutation.mutateAsync({ tenant, project, path })
        }
        createSubContext={(path, name) => {
          return createContextMutation.mutateAsync({
            tenant,
            project,
            path,
            name,
          });
        }}
        fetchContexts={() => queryContextsForProject(tenant, project)}
        refreshKey={projectContextKey(tenant, project)}
        modificationRight={modificationRight}
        overloadRender={(context, parents, path, modificationRight) => (
          <ProjectOverloadTable
            tenant={tenant}
            overloads={context.overloads}
            path={path}
            modificationRight={modificationRight}
            parents={parents}
          />
        )}
      />
    </>
  );
}

function ProjectOverloadTable({
  tenant,
  overloads,
  path,
  modificationRight,
  parents,
}: {
  tenant: string;
  overloads: TContextOverload[];
  path: string;
  modificationRight: boolean;
  parents: TContext[];
}) {
  const { project } = useParams();
  const projectQuery = useQuery(projectQueryKey(tenant, project!), () =>
    queryProject(tenant, project!)
  );
  const updateOverload = useMutation(
    (data: {
      project: string;
      name: string;
      enabled: boolean;
      conditions: TCondition[];
      wasm: TWasmConfig;
    }) => {
      const { project, name, enabled, conditions, wasm } = data;
      return updateFeatureActivationForContext(
        tenant,
        project,
        path,
        name,
        enabled,
        conditions,
        wasm
      );
    }
  );

  const [creating, setCreating] = React.useState(false);
  const [displayingAllFeatures, displayAllFeatures] = useState(false);
  const editionRight = useProjectRight(tenant, project, TLevel.Write);
  if (projectQuery.data) {
    return (
      <>
        <h4>
          Feature overloads{" "}
          {modificationRight && (
            <button
              className="btn btn-primary btn-sm mb-2 ms-3"
              type="button"
              onClick={() => setCreating(true)}
            >
              Create new overload
            </button>
          )}
        </h4>
        {creating && (
          <OverloadCreationForm
            excluded={overloads.map((o) => o.name)}
            project={project!}
            cancel={() => setCreating(false)}
            submit={({ name, enabled, conditions, wasmConfig }) => {
              updateOverload.mutateAsync(
                {
                  project: project!,
                  name,
                  enabled,
                  conditions: conditions ?? [],
                  wasm: wasmConfig!,
                },
                {
                  onSuccess: () => {
                    setCreating(false);
                    queryClient.invalidateQueries(
                      projectContextKey(tenant, project!)
                    );
                  },
                }
              );
            }}
          />
        )}
        <label className="d-flex align-items-center">
          <input
            type="checkbox"
            className="izanami-checkbox"
            onChange={(e) => displayAllFeatures(e.target.checked)}
          />
          <span className="mt-3 ms-2">
            Display all features strategy for this context
          </span>
        </label>
        <OverloadTable
          project={project!}
          actions={(o) =>
            editionRight && o.path === path.substring(1)
              ? ["edit", "test", "delete"]
              : []
          }
          fields={["path", "name", "enabled", "details"]}
          refresh={() => {
            queryClient.invalidateQueries(projectQueryKey(tenant, project!));
            queryClient.invalidateQueries(projectContextKey(tenant, project!));
          }}
          overloads={
            displayingAllFeatures
              ? allFeatureStrategies(overloads, [
                  {
                    name: "default",
                    id: "default",
                    overloads: projectQuery.data.features as TContextOverload[],
                    children: [],
                  } as any, // FIXME TS
                  ...parents,
                ])
              : overloads.map((o) => ({ ...o, path: path.substring(1) }))
          }
        />
      </>
    );
  } else if (projectQuery.error) {
    return <div>Failed to fetch project</div>;
  } else {
    return <Loader message="Loading project" />;
  }
}

function allFeatureStrategies(
  overloads: TContextOverload[],
  parents: TContext[]
): TContextOverload[] {
  let result: {
    overloads: { [x: string]: TContextOverload };
    path: string[];
  } = parents.reduce(
    (
      acc: { overloads: { [x: string]: TContextOverload }; path: string[] },
      context
    ) => {
      context.overloads.forEach((overload) => {
        acc.overloads[overload.name] = {
          ...overload,
          path:
            acc.path?.[0] === "default"
              ? [...acc.path.slice(1), context.name].join("/")
              : [...acc.path, context.name].join("/"),
        };
      });
      return {
        overloads: acc.overloads,
        path: [...acc.path, context.name],
      };
    },
    { overloads: {}, path: [] }
  );

  const path = parents
    .slice(1)
    .map((p) => p.name)
    .join("/");

  overloads.forEach(
    (overload) => (result.overloads[overload.name] = { ...overload, path })
  );

  return Object.values(result.overloads);
}
