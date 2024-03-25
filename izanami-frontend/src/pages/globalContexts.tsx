import * as React from "react";
import {
  IzanamiContext,
  hasRightForProject,
  useTenantRight,
} from "../securityContext";
import { TContext, TContextOverload, TLevel } from "../utils/types";
import { useMutation, useQuery } from "react-query";
import {
  createGlobalContext,
  deleteGlobalContext,
  globalContextKey,
  queryGlobalContexts,
} from "../utils/queries";
import { FeatureContexts } from "../components/FeatureContexts";
import queryClient from "../queryClient";
import { OverloadTable } from "../components/FeatureTable";

export function GlobalContexts(props: { tenant: string }) {
  const { tenant } = props;

  return <GlobalFeatureContexts tenant={tenant} />;
}

function GlobalFeatureContexts(props: { tenant: string }) {
  const { tenant } = props;

  const modificationRight = useTenantRight(tenant, TLevel.Write);
  const contextQuery = useQuery(globalContextKey(tenant), () =>
    queryGlobalContexts(tenant)
  );

  const deleteContextMutation = useMutation(
    (data: { tenant: string; path: string }) => {
      const { tenant, path } = data;
      return deleteGlobalContext(tenant, path);
    },
    {
      onSuccess: () => {
        queryClient.invalidateQueries(globalContextKey(tenant));
      },
    }
  );

  const createGlobalSubContextMutation = useMutation(
    (data: { tenant: string; path: string; name: string }) => {
      const { tenant, path, name } = data;
      return createGlobalContext(tenant, path, name);
    },
    {
      onSuccess: () => {
        queryClient.invalidateQueries(globalContextKey(tenant));
      },
    }
  );

  if (contextQuery.isLoading) {
    return <div>Loading...</div>;
  } else if (contextQuery.data) {
    // handle opening
    return (
      <FeatureContexts
        allowGlobalContextDelete={true}
        open={[]}
        modificationRight={modificationRight || false}
        deleteContext={(path: string) =>
          deleteContextMutation.mutateAsync({ tenant, path })
        }
        createSubContext={(path: string, name: string) =>
          createGlobalSubContextMutation.mutateAsync({ tenant, path, name })
        }
        fetchContexts={() => queryGlobalContexts(tenant)}
        refreshKey={globalContextKey(tenant)}
        overloadRender={(
          context: TContext,
          parents: TContext[],
          path: string
        ) => {
          return (
            <GlobalContextOverloadTable
              overloads={context.overloads}
              path={path}
              tenant={tenant}
            />
          );
        }}
      />
    );
  } else {
    return <div>Failed to fetch global contexts</div>;
  }
}

function GlobalContextOverloadTable({
  tenant,
  overloads,
  path,
}: {
  tenant: string;
  overloads: TContextOverload[];
  path: string;
}) {
  const { user } = React.useContext(IzanamiContext);
  return (
    <>
      <h4>Feature overloads </h4>
      <OverloadTable
        overloads={overloads.map((o) => ({ ...o, path: path.slice(1) }))}
        fields={["name", "enabled", "details"]}
        actions={(o) => {
          if (hasRightForProject(user!, TLevel.Write, o.project!, tenant)) {
            return ["edit", "test", "delete"];
          } else {
            return [];
          }
        }}
        refresh={() => {
          queryClient.invalidateQueries(globalContextKey(tenant));
        }}
      />
    </>
  );
}
