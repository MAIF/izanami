import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  deleteScript,
  fetchWasmScripts,
  tenantScriptKey,
  updateScript,
} from "../utils/queries";
import { GenericTable } from "../components/GenericTable";
import { NavLink, useSearchParams } from "react-router-dom";
import { TLevel, TUser, TWasmConfig } from "../utils/types";
import { IzanamiContext, hasRightForTenant } from "../securityContext";
import queryClient from "../queryClient";
import { WasmInput } from "../components/WasmInput";
import { useForm, FormProvider } from "react-hook-form";
import { Loader } from "../components/Loader";

export function WasmScripts(props: { tenant: string }) {
  const { tenant } = props;
  const scriptQuery = useQuery({
    queryKey: [tenantScriptKey(tenant)],

    queryFn: () => fetchWasmScripts(tenant),
  });
  const wasmUpdateMutation = useMutation({
    mutationFn: ({
      name,
      newScript,
    }: {
      name: string;
      newScript: TWasmConfig;
    }) => updateScript(tenant!, name, newScript),

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [tenantScriptKey(tenant!)] });
    },
  });
  const [searchParams] = useSearchParams();
  const selectedSearchRow = searchParams.get("filter");
  const { askConfirmation } = React.useContext(IzanamiContext);

  if (scriptQuery.error) {
    return <div>Failed to load wasm scripts</div>;
  } else if (scriptQuery.data) {
    return (
      <>
        <h1>Wasm scripts</h1>
        <GenericTable
          idAccessor={(script) => script.config.name}
          data={scriptQuery.data}
          filters={
            selectedSearchRow ? [{ id: "name", value: selectedSearchRow }] : []
          }
          columns={[
            {
              id: "name",
              accessorFn: (s) => s.config.name,
              header: () => "Script name",
              minSize: 200,
              size: 10,
            },
            {
              id: "source",
              accessorFn: (s) => s.config.source.kind,
              header: () => "Script type",
              minSize: 200,
              size: 10,

              meta: {
                valueType: "discrete",
              },
            },
            {
              id: "features",
              accessorFn: (s) => s.features,
              header: () => "Associated features",
              enableColumnFilter: false,
              minSize: 200,
              cell: (info) => {
                return (
                  <>
                    {(info.getValue() as any).map(
                      ({ name, project, id }: any) => {
                        return (
                          <div key={id}>
                            <NavLink
                              className={() => ""}
                              to={`/tenants/${tenant}/projects/${project}`}
                            >
                              {name}({project})
                            </NavLink>
                          </div>
                        );
                      }
                    )}
                  </>
                );
              },
              size: 10,
            },
          ]}
          customRowActions={{
            edit: {
              icon: (
                <>
                  <i className="bi bi-pencil-square" aria-hidden></i> Edit
                </>
              ),
              hasRight: (user: TUser) => {
                return Boolean(hasRightForTenant(user, tenant, TLevel.Write));
              },
              customForm: (data, cancel) => {
                return (
                  <WasmScriptUpdateForm
                    defaultValue={data.config}
                    cancel={cancel}
                    submit={(newScript) =>
                      wasmUpdateMutation
                        .mutateAsync({
                          name: data.config.name,
                          newScript,
                        })
                        .then(() => cancel())
                    }
                  />
                );
              },
            },
            delete: {
              icon: (
                <>
                  <i className="bi bi-trash" aria-hidden></i> Delete
                </>
              ),
              hasRight: (user: TUser, wasm: { features: any[] }) => {
                return (
                  Boolean(hasRightForTenant(user, tenant, TLevel.Write)) &&
                  wasm.features.length === 0
                );
              },
              action: (wasmConfig: { config: TWasmConfig }) =>
                askConfirmation(
                  `Are you sure you want to delete script ${wasmConfig.config.name} ?`,
                  () => {
                    return deleteScript(tenant, wasmConfig.config.name).then(
                      () =>
                        queryClient.invalidateQueries({
                          queryKey: [tenantScriptKey(tenant)],
                        })
                    );
                  }
                ),
            },
          }}
        />
      </>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}

function WasmScriptUpdateForm(props: {
  defaultValue: TWasmConfig;
  cancel: () => void;
  submit: (script: TWasmConfig) => Promise<any>;
}) {
  const { submit, cancel, defaultValue } = props;

  const methods = useForm<{ wasmConfig: TWasmConfig }>({
    defaultValues: { wasmConfig: defaultValue },
  });

  const { handleSubmit } = methods;
  return (
    <FormProvider {...methods}>
      <form
        onSubmit={handleSubmit((data: { wasmConfig: TWasmConfig }) =>
          submit(data.wasmConfig)
        )}
        className="d-flex flex-column flex-shrink-1"
      >
        <WasmInput />
        <div className="d-flex justify-content-end">
          <button
            type="button"
            className="btn btn-danger m-2"
            onClick={() => cancel()}
          >
            Cancel
          </button>
          <button className="btn btn-primary m-2">Save</button>
        </div>
      </form>
    </FormProvider>
  );
}
