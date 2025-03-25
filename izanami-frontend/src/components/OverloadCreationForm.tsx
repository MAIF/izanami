import * as React from "react";
import {
  FeatureTypeName,
  TContextOverload,
  TStringContextOverload,
} from "../utils/types";
import {
  useForm,
  Controller,
  FormProvider,
  FieldErrors,
} from "react-hook-form";
import { ConditionsInput } from "./ConditionInput";
import Select from "react-select";
import { ExistingScript, WasmInput } from "./WasmInput";
import { useState } from "react";
import { customStyles } from "../styles/reactSelect";
import { useQuery } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { projectQueryKey, queryProject } from "../utils/queries";
import { Loader } from "./Loader";
import { ErrorDisplay } from "./FeatureForm";
import { Tooltip } from "./Tooltip";
import { ResultTypeIcon } from "./ResultTypeIcon";

export function OverloadCreationForm(props: {
  project: string;
  excluded?: string[];
  cancel: () => void;
  submit: (overload: TContextOverload) => void;
  defaultValue?: TContextOverload;
  noName?: boolean;
  additionalFields?: () => JSX.Element;
  resultType?: FeatureTypeName;
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
  const [featureResultType, setFeatureResultType] = useState<
    FeatureTypeName | undefined
  >(props.resultType);
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

  const projectQuery = useQuery({
    queryKey: [projectQueryKey(tenant!, project)],

    queryFn: () => queryProject(tenant!, project),
  });

  if (projectQuery.isError) {
    return <div>Error while fetching project</div>;
  } else if (projectQuery.data) {
    return (
      <FormProvider {...methods}>
        <form
          onSubmit={handleSubmit((data) =>
            submit({
              ...data,
              resultType: featureResultType,
            } as TContextOverload)
          )}
          className="d-flex flex-column anim__rightToLeft p-3 sub_container"
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
                  onChange={(e) => {
                    const selectedFeature = projectQuery.data.features.find(
                      (f) => f.name === e?.value
                    );
                    setFeatureResultType(selectedFeature?.resultType);
                    onChange(e?.value);
                  }}
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
          <label className="mt-3">
            Enabled
            <input
              type="checkbox"
              className="izanami-checkbox ms-2"
              {...register("enabled")}
            />
          </label>
          {additionalFields?.()}
          <div className="row mt-3">
            <label className="col-6 col-lg-5 col-xl-4 col-xxl-3">
              Feature result type
              <Tooltip id="modern-result-type">
                Result type is result that will be returned by feature
                evaluation.
                <br /> It's usually boolean, but for some cases it can be string
                or number.
              </Tooltip>
              <Select
                placeholder="Select a feature to display its result type"
                value={
                  featureResultType
                    ? {
                        label: (
                          <div className="d-flex">
                            <ResultTypeIcon
                              resultType={featureResultType}
                              fontWeight="bold"
                              color="var(--color_level1)"
                            />
                            <span className="ps-1">{featureResultType}</span>
                          </div>
                        ),
                        value: featureResultType,
                      }
                    : null
                }
                isDisabled={true}
                styles={customStyles}
              />
            </label>
            {featureResultType &&
              featureResultType !== "boolean" &&
              type === "Classic" && (
                <label className="col-6 col-lg-7 col-xl-8 col-xxl-9">
                  Base value*
                  {featureResultType === "string" ? (
                    <input
                      type="text"
                      className="form-control"
                      {...register("value")}
                    />
                  ) : (
                    <input
                      type="number"
                      className="form-control"
                      step="any"
                      {...register("value")}
                    />
                  )}
                  <ErrorDisplay
                    error={
                      (errors as FieldErrors<TStringContextOverload>).value
                    }
                  />
                </label>
              )}
          </div>
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
                setValue("wasmConfig", undefined as any);
                setType(e?.value || "");
                if (e?.value === "Existing WASM script") {
                  setValue("wasmConfig.source.kind", "Local");
                }

                if (e?.value !== "Classic") {
                  setValue("value", "");
                }
              }}
            />
          </label>
          {type === "Classic" && <ConditionsInput folded={true} />}
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
            <button className="btn btn-primary m-2">Save</button>
          </div>
        </form>
      </FormProvider>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}
