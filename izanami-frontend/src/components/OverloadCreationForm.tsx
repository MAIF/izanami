import * as React from "react";
import { TContextOverload, isWasmFeature } from "../utils/types";
import { useForm, Controller, FormProvider } from "react-hook-form";
import { ConditionsInput } from "./ConditionInput";
import Select from "react-select";
import { ExistingScript, WasmInput } from "./WasmInput";
import { useState } from "react";
import { customStyles } from "../styles/reactSelect";
import { useQuery } from "react-query";
import { useParams } from "react-router-dom";
import { projectQueryKey, queryProject } from "../utils/queries";
import { Loader } from "./Loader";

export function OverloadCreationForm(props: {
  project: string;
  excluded?: string[];
  cancel: () => void;
  submit: (overload: TContextOverload) => void;
  defaultValue?: TContextOverload;
  noName?: boolean;
  additionalFields?: () => JSX.Element;
}) {
  const {
    cancel,
    submit,
    project,
    excluded,
    defaultValue,
    noName,
    additionalFields,
  } = props;
  const methods = useForm<TContextOverload>({ defaultValues: defaultValue });
  const { tenant } = useParams();
  const isWasm = defaultValue && "wasmConfig" in defaultValue;
  const [type, setType] = useState(isWasm ? "Existing WASM script" : "Classic");
  React.useEffect(() => {
    if (type === "Existing WASM script") {
      setValue("wasmConfig.source.kind", "Local");
    }
  }, [type]);
  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
    setValue,
  } = methods;

  const projectQuery = useQuery(projectQueryKey(tenant!, project), () =>
    queryProject(tenant!, project)
  );

  if (projectQuery.isError) {
    return <div>Error while fetching project</div>;
  } else if (projectQuery.data) {
    return (
      <FormProvider {...methods}>
        <form
          onSubmit={handleSubmit((data) => submit(data))}
          className="d-flex flex-column anim__rightToLeft p-3"
          style={{ backgroundColor: "#42423f" }}
        >
          <label>
            Name
            <Controller
              name="name"
              control={control}
              render={({ field: { onChange, value } }) => (
                <Select
                  isDisabled={noName}
                  value={{ value, label: value }}
                  onChange={(e) => onChange(e?.value)}
                  styles={customStyles}
                  options={projectQuery.data.features
                    .filter((f) => !excluded || !excluded.includes(f.name))
                    .map((f) => ({
                      label: f.name,
                      value: f.name,
                    }))}
                />
              )}
            />
            {errors.name && <p className="error-message">name is required</p>}
          </label>
          {additionalFields?.()}
          <label className="mt-3 d-flex">
            <span className="mt-2">Enabled</span>
            <input
              type="checkbox"
              className="izanami-checkbox ms-2"
              {...register("enabled")}
            />
          </label>
          <label className="mt-3">
            Feature type
            <Select
              styles={customStyles}
              options={[
                { label: "Classic", value: "Classic" },
                { label: "New WASM script", value: "New WASM script" },
                {
                  label: "Existing WASM script",
                  value: "Existing WASM script",
                },
              ]}
              value={{ label: type, value: type }}
              onChange={(e) => {
                setValue("conditions", []);
                setValue("wasmConfig", undefined);
                setType(e?.value || "");
                if (e?.value === "Existing WASM script") {
                  setValue("wasmConfig.source.kind", "Local");
                }
              }}
            />
          </label>
          {type === "Classic" && <ConditionsInput />}
          {type === "Existing WASM script" && <ExistingScript />}
          {type === "New WASM script" && <WasmInput />}
          <div className="d-flex justify-content-end">
            <button
              type="button"
              className="btn btn-danger m-2"
              onClick={() => cancel()}
            >
              Cancel
            </button>
            <button className="btn btn-success m-2">Save</button>
          </div>
        </form>
      </FormProvider>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}
