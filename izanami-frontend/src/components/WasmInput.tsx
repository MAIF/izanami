import * as React from "react";
import { customStyles } from "../styles/reactSelect";
import Select from "react-select";
import { useQuery } from "@tanstack/react-query";
import {
  Controller,
  FieldErrors,
  useFormContext,
  useWatch,
} from "react-hook-form";
import { TContextOverload, WasmFeature } from "../utils/types";
import { useParams } from "react-router-dom";
import { ErrorDisplay } from "./FeatureForm";
import { FEATURE_NAME_REGEXP } from "../utils/patterns";
import { IzanamiContext } from "../securityContext";
import { Tooltip } from "./Tooltip";
import { Loader } from "./Loader";

export function WasmInput() {
  const {
    register,
    formState: { errors },
  } = useFormContext();
  return (
    <>
      <label className="mt-3 col-6">
        Script name*
        <Tooltip id="script-name">
          Script name, use something meaningfull
        </Tooltip>
        <input
          className="form-control"
          {...register("wasmConfig.name", {
            required: "Script name must be specified",
            pattern: {
              value: FEATURE_NAME_REGEXP,
              message: `Script name must match ${FEATURE_NAME_REGEXP}`,
            },
          })}
        />
      </label>
      <ErrorDisplay error={(errors?.wasmConfig as any)?.name} />
      <KindOptions />
      <fieldset className="sub_container mt-2">
        <legend>WASM script parameters</legend>
        <div className="row">
          <label className="col-7">
            Wasm function name
            <Tooltip id="function-name">
              Function to call inside your script.
            </Tooltip>
            <input
              className="form-control"
              defaultValue="execute"
              {...register("wasmConfig.functionName")}
            />
          </label>
          <ErrorDisplay error={(errors?.wasmConfig as any)?.functionName} />
          <div className="d-flex col-5">
            <label className="d-flex  flex-column">
              <span>
                Open Policy Agent
                <Tooltip id="opa">
                  Whether your script is an Open Policy Agent script.
                </Tooltip>
              </span>
              <input
                type="checkbox"
                className="izanami-checkbox ms-2"
                defaultChecked={false}
                {...register("wasmConfig.opa")}
              />
            </label>
          </div>
        </div>
      </fieldset>
    </>
  );
}

function KindOptions() {
  const {
    register,
    control,
    getValues,
    setValue,
    formState: { errors },
  } = useFormContext<TContextOverload>();
  const query = useQuery({
    queryKey: ["WASMSCRIPTS"],
    queryFn: () => loadWasmManagerScripts(),
  });
  const { integrations } = React.useContext(IzanamiContext);

  let opts = <></>;

  useWatch({ name: "wasmConfig.source.kind" });
  switch (getValues("wasmConfig.source.kind")) {
    case "Wasmo":
      if (query.error) {
        opts = <div className="error-message">Failed to load wasm scripts</div>;
      } else if (query.data) {
        opts = (
          <>
            <label className="w-100">
              Script path on wasm manager*
              <Controller
                name="wasmConfig.source.path"
                control={control}
                render={({ field: { onChange, value } }) => (
                  <Select
                    value={query.data.find(
                      ({ value: qValue }: { value: string }) => value === qValue
                    )}
                    onChange={(e) => onChange(e?.value)}
                    options={query.data}
                    styles={customStyles}
                  />
                )}
              />
            </label>
            <ErrorDisplay
              error={
                (errors as FieldErrors<WasmFeature>)?.wasmConfig?.source?.path
              }
            />
          </>
        );
      } else {
        opts = <Loader message="Loading..." />;
      }
      break;
    case "Http":
      opts = (
        <>
          <label>
            URL
            <input
              type="text"
              className="form-control"
              {...register("wasmConfig.source.path")}
            />
          </label>
          <label>
            Headers
            <Controller
              name="wasmConfig.source.opts.headers"
              control={control}
              render={({ field: { onChange, value } }) => (
                <ObjectInput
                  value={value ? Object.entries(value) : []}
                  onChange={(arr: [string, string][]) =>
                    onChange(
                      arr.reduce((acc, [key, value]) => {
                        acc[key] = value;
                        return acc;
                      }, {} as { [x: string]: any })
                    )
                  }
                />
              )}
            />
          </label>
          <label>
            Timeout
            <input
              type="number"
              className="form-control"
              {...register("wasmConfig.source.opts.timeout")}
            />
          </label>
          <label>
            Method
            <Controller
              name="wasmConfig.source.opts.method"
              control={control}
              render={({ field: { onChange, value } }) => (
                <Select
                  value={{ value, label: value }}
                  onChange={(e) => onChange(e?.value)}
                  styles={customStyles}
                  options={[
                    "GET",
                    "POST",
                    "PUT",
                    "PATCH",
                    "DELETE",
                    "OPTION",
                    "CONNECT",
                    "HEAD",
                    "TRACE",
                  ].map((v) => ({ label: v, value: v }))}
                />
              )}
            />
          </label>
          <label>
            Follow redirects
            <input
              type="checkbox"
              className="izanami-checkbox"
              {...register("wasmConfig.source.opts.followRedirect")}
            />
          </label>
        </>
      );
      break;
    case "File":
      opts = (
        <label>
          File path
          <input
            type="text"
            className="form-control"
            {...register("wasmConfig.source.path")}
          />
        </label>
      );
      break;
    case "Base64":
      opts = (
        <>
          <label className="w-100">
            Base64 encoded plugin*
            <input
              type="text"
              className="form-control"
              {...register("wasmConfig.source.path")}
            />
          </label>
          <ErrorDisplay
            error={
              (errors as FieldErrors<WasmFeature>)?.wasmConfig?.source?.path
            }
          />
        </>
      );
  }

  return (
    <>
      <div className="row mt-3">
        <label className="col-6">
          Kind*
          <Tooltip id="wasm-kind">
            WASMO will reference a script from a running WASMO instance.
            <br />
            Base64 will embed a Base64 WASM script directly in this Izanami
            instance.
          </Tooltip>
          <Controller
            name="wasmConfig.source.kind"
            control={control}
            rules={{ required: "Wasm script kind must be specified" }}
            render={({ field: { onChange, value } }) => (
              <Select
                value={{ value, label: value }}
                onChange={(e) => {
                  setValue("wasmConfig.source.opts", {});
                  setValue("wasmConfig.source.path", "");
                  onChange(e?.value);
                }}
                styles={customStyles}
                isOptionDisabled={(option: any) =>
                  option.value === "Wasmo" && !integrations?.wasmo
                }
                options={[
                  { label: "Base64", value: "Base64" },
                  {
                    label: `Wasmo ${
                      integrations?.wasmo
                        ? ""
                        : "(unavailable due to an incomplete or missing WASMO configuration)"
                    }`,
                    value: "Wasmo",
                  },
                ]}
              />
            )}
          />
        </label>
        <ErrorDisplay
          error={(errors as FieldErrors<WasmFeature>)?.wasmConfig?.source?.kind}
        />
        <div className="col-6">{opts}</div>
      </div>
    </>
  );
}

function loadWasmManagerScripts() {
  return fetch("/api/admin/plugins", {
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
  })
    .then((r) => r.json())
    .then((plugins) => {
      return (
        plugins
          .map(
            (plugin: { versions: object[]; pluginId: string }) =>
              plugin.versions?.map((version) => ({
                ...version,
                id: plugin.pluginId,
              })) || []
          )
          .flat()
          // FIXME TS
          .map((plugin: any) => {
            const wasmName = (isAString(plugin) ? plugin : plugin.name) || "";
            const parts = wasmName.split(".wasm");
            return {
              label: `${parts[0]} - ${
                parts[0].endsWith("-dev") ? "[DEV]" : "[RELEASE]"
              }`,
              value: wasmName,
            };
          })
      );
    })
    .then((foo) => {
      return foo;
    });
}

function isAString(variable: any) {
  return typeof variable === "string" || variable instanceof String;
}

function ObjectInput(props: {
  value: [string, string][];
  onChange: (value: [string, string][]) => void;
}) {
  const { value: fieldValue, onChange } = props;
  return (
    <>
      {fieldValue.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Key</th>
              <th>Value</th>
            </tr>
          </thead>
          <tbody>
            {(fieldValue ?? []).map(([key, value], index) => {
              return (
                <tr key={index}>
                  <td>
                    <label>
                      <input
                        type="text"
                        className="form-control"
                        value={key ?? ""}
                        onChange={(e) => {
                          onChange([
                            ...fieldValue.slice(0, index),
                            [e.target.value, fieldValue[index]?.[1]],
                            ...fieldValue.slice(index + 1),
                          ]);
                        }}
                      />
                    </label>
                  </td>
                  <td>
                    <label>
                      <input
                        type="text"
                        className="form-control"
                        value={value ?? ""}
                        onChange={(e) => {
                          onChange([
                            ...fieldValue.slice(0, index),
                            [fieldValue[index]?.[0], e.target.value],
                            ...fieldValue.slice(index + 1),
                          ]);
                        }}
                      />
                    </label>
                  </td>
                  <td>
                    <button
                      className="btn btn-danger m-2"
                      type="button"
                      onClick={() => {
                        onChange([
                          ...fieldValue.slice(0, index),
                          ...fieldValue.slice(index + 1),
                        ]);
                      }}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <button
        className="btn btn-primary btn-sm mb-2 ms-3"
        type="button"
        onClick={() => onChange([...fieldValue, ["", ""]])}
      >
        Add header
      </button>
    </>
  );
}

export function ExistingScript() {
  const { tenant } = useParams();
  const {
    control,
    setValue,
    formState: { errors },
  } = useFormContext<TContextOverload>();

  const query = useQuery({
    queryKey: ["LOCAL_SCRIPTS"],

    queryFn: () =>
      fetch(`/api/admin/tenants/${tenant}/local-scripts`)
        .then((resp) => resp.json())
        .then((ws) =>
          ws.map(({ name }: { name: string }) => ({ label: name, value: name }))
        ),
  });

  if (query.error) {
    return <div className="error-message">Failed to fetch local scripts</div>;
  } else if (query.data) {
    return (
      <>
        <label className="mt-3">
          Existing script name
          <Controller
            name="wasmConfig.name"
            control={control}
            rules={{ required: "Script is missing" }}
            render={({ field: { onChange, value } }) => (
              <Select
                value={{ value, label: value }}
                onChange={(e) => {
                  setValue("wasmConfig.name", e?.value || "");
                  onChange(e?.value);
                }}
                styles={customStyles}
                options={query.data}
              />
            )}
          />
        </label>
        <ErrorDisplay
          error={(errors as FieldErrors<WasmFeature>)?.wasmConfig?.name}
        />
      </>
    );
  } else {
    return <Loader message="Loading local scripts ..." />;
  }
}
