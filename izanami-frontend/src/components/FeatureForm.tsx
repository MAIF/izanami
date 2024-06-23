import * as React from "react";
import {
  ClassicalFeature,
  DAYS,
  SingleConditionFeature,
  TCompleteFeature,
  TCondition,
  TLightFeature,
  isPercentageRule,
  isSingleConditionFeature,
  isSingleCustomerConditionFeature,
  isSingleDateRangeConditionFeature,
  isSingleHourRangeConditionFeature,
  isSingleNoStrategyConditionFeature,
  isSinglePercentageConditionFeature,
  isWasmFeature,
} from "../utils/types";
import {
  useForm,
  Controller,
  FormProvider,
  useFormContext,
  useWatch,
  FieldErrors,
  FieldError,
} from "react-hook-form";
import { ConditionsInput } from "./ConditionInput";
import Select from "react-select";
import { ExistingScript, WasmInput } from "./WasmInput";
import { useState } from "react";
import { customStyles } from "../styles/reactSelect";
import { useQuery } from "react-query";
import { useParams } from "react-router-dom";
import { queryTags, tagsQueryKey, toCompleteFeature } from "../utils/queries";
import { FeatureTestForm } from "./FeatureTable";
import CreatableSelect from "react-select/creatable";
import { FEATURE_NAME_REGEXP, LEGACY_ID_REGEXP } from "../utils/patterns";
import { format, isBefore, parse } from "date-fns";
import { DEFAULT_TIMEZONE, TimeZoneSelect } from "./TimeZoneSelect";
import { Tooltip } from "./Tooltip";
import { Loader } from "./Loader";
import { execFile } from "child_process";

export type LegacyFeature =
  | NoStrategyFeature
  | PercentageFeature
  | ReleaseDateFeature
  | DateRangeFeature
  | HourRangeFeature
  | CustomerListFeature;

export interface LegacyFeatureParent {
  id: string;
  name: string;
  enabled: boolean;
  description: string;
  activationStrategy: TLegacyStrategy;
  project?: string;
  tags?: string[];
}

export interface NoStrategyFeature extends LegacyFeatureParent {
  activationStrategy: "NO_STRATEGY";
}

export interface PercentageFeature extends LegacyFeatureParent {
  activationStrategy: "PERCENTAGE";
  parameters: {
    percentage: number;
  };
}

export interface ReleaseDateFeature extends LegacyFeatureParent {
  activationStrategy: "RELEASE_DATE";
  parameters: {
    date: Date;
    timezone: string;
  };
}

export interface DateRangeFeature extends LegacyFeatureParent {
  activationStrategy: "DATE_RANGE";
  parameters: { from: Date; to: Date; timezone: string };
}

export interface HourRangeFeature extends LegacyFeatureParent {
  activationStrategy: "HOUR_RANGE";
  parameters: {
    startAt: string;
    endAt: string;
    timezone: string;
  };
}

export interface CustomerListFeature extends LegacyFeatureParent {
  activationStrategy: "CUSTOMERS_LIST";
  parameters: {
    customers: string[];
  };
}

const LegacyStrategies = [
  "NO_STRATEGY",
  "PERCENTAGE",
  "RELEASE_DATE",
  "DATE_RANGE",
  "HOUR_RANGE",
  "CUSTOMERS_LIST",
] as const;

type TLegacyStrategy = typeof LegacyStrategies[number];

export function toModernFeature(feature: SingleConditionFeature) {
  if (!feature) {
    return {} as ClassicalFeature;
  }
  const { id, name, description, enabled, tags, project } = feature;
  const base = { id, name, description, enabled, tags, project };
  if (isSingleCustomerConditionFeature(feature)) {
    return {
      ...base,
      conditions: [{ rule: { users: feature.conditions.users } }],
    };
  } else if (isSinglePercentageConditionFeature(feature)) {
    return {
      ...base,
      conditions: [{ rule: { percentage: feature.conditions.percentage } }],
    };
  } else if (isSingleDateRangeConditionFeature(feature)) {
    return {
      ...base,
      conditions: [
        {
          period: {
            begin: feature.conditions.begin,
            end: feature.conditions.end,
            timezone: feature.conditions.timezone,
            activationDays: { days: DAYS },
          },
        },
      ],
    };
  } else if (isSingleHourRangeConditionFeature(feature)) {
    return {
      ...base,
      conditions: [
        {
          period: {
            timezeon: feature.conditions.timezone,
            activationDays: { days: DAYS },
            hourPeriods: [
              {
                startTime: feature.conditions.startTime,
                endTime: feature.conditions.endTime,
              },
            ],
          },
        },
      ],
    };
  } else {
    return base;
  }
}

export function toLegacyFeatureFormat(
  feature: SingleConditionFeature
): LegacyFeature {
  if (!feature) {
    return { activationStrategy: "NO_STRATEGY" } as LegacyFeature;
  }
  const { id, name, description, enabled, tags, project } = feature;
  const base = { id, name, description, enabled, tags, project };
  if (isSinglePercentageConditionFeature(feature)) {
    return {
      ...base,
      activationStrategy: "PERCENTAGE",
      parameters: {
        percentage: feature.conditions.percentage,
      },
    };
  } else if (isSingleCustomerConditionFeature(feature)) {
    return {
      ...base,
      activationStrategy: "CUSTOMERS_LIST",
      parameters: {
        customers: feature.conditions.users,
      },
    };
  } else if (isSingleHourRangeConditionFeature(feature)) {
    return {
      ...base,
      activationStrategy: "HOUR_RANGE",
      parameters: {
        startAt: feature.conditions.startTime,
        endAt: feature.conditions.endTime,
        timezone: feature.conditions.timezone,
      },
    };
  } else if (isSingleDateRangeConditionFeature(feature)) {
    if (feature.conditions.end === undefined) {
      return {
        ...base,
        activationStrategy: "RELEASE_DATE",
        parameters: {
          date: feature.conditions.begin,
          timezone: feature.conditions.timezone,
        },
      };
    } else {
      return {
        ...base,
        activationStrategy: "DATE_RANGE",
        parameters: {
          from: feature.conditions.begin,
          to: feature.conditions.end,
          timezone: feature.conditions.timezone,
        },
      };
    }
  } else if (isSingleNoStrategyConditionFeature(feature)) {
    return {
      ...base,
      activationStrategy: "NO_STRATEGY",
    };
  } else {
    throw new Error(
      "Failed to convert SingleConditionFeature to legacy feature"
    );
  }
}

function toSingleConditionFeatureFormat(
  feature: LegacyFeature
): SingleConditionFeature {
  const { id, name, description, enabled, tags, project } = feature;
  const base = { id, name, description, enabled, tags, project };
  if (feature.activationStrategy === "CUSTOMERS_LIST") {
    return {
      ...base,
      tags: tags ?? [],
      conditions: {
        users: feature.parameters.customers,
      },
    };
  } else if (feature.activationStrategy === "PERCENTAGE") {
    return {
      ...base,
      tags: tags ?? [],
      conditions: {
        percentage: feature.parameters.percentage,
      },
    };
  } else if (feature.activationStrategy === "RELEASE_DATE") {
    return {
      ...base,
      tags: tags ?? [],
      conditions: {
        begin: feature.parameters.date,
        timezone: feature.parameters.timezone,
      },
    };
  } else if (feature.activationStrategy === "DATE_RANGE") {
    return {
      ...base,
      tags: tags ?? [],
      conditions: {
        begin: feature.parameters.from,
        end: feature.parameters.to,
        timezone: feature.parameters.timezone,
      },
    };
  } else if (feature.activationStrategy === "HOUR_RANGE") {
    return {
      ...base,
      tags: tags ?? [],
      conditions: {
        startTime: feature.parameters.startAt,
        endTime: feature.parameters.endAt,
        timezone: feature.parameters.timezone,
      },
    };
  } else if (feature.activationStrategy === "NO_STRATEGY") {
    return {
      ...base,
      tags: tags ?? [],
      conditions: {},
    };
  }
  throw new Error("TODO");
}

export function ErrorDisplay({ error }: { error?: FieldError }) {
  if (!error) {
    return <></>;
  } else {
    return (
      <div className="error-message">
        <i className="fa-solid fa-circle-exclamation" aria-hidden />
        &nbsp;
        {error.message || "Incorrect value"}
      </div>
    );
  }
}

function LegacyFeatureForm(props: {
  cancel: () => void;
  submit: (feature: LegacyFeature) => void;
  defaultValue?: LegacyFeature;
}) {
  const methods = useForm<LegacyFeature>({
    defaultValues: props?.defaultValue,
  });

  const { tenant } = useParams();

  const {
    control,
    register,
    handleSubmit,
    watch,
    formState: { errors },
    setError,
  } = methods;

  const tagQuery = useQuery(tagsQueryKey(tenant!), () => queryTags(tenant!));

  const strategy = watch("activationStrategy");

  if (tagQuery.data) {
    return (
      <FormProvider {...methods}>
        <div className="d-flex justify-content-between">
          <form
            onSubmit={handleSubmit((data) => {
              if (data.activationStrategy === "DATE_RANGE") {
                if (isBefore(data.parameters.to, data.parameters.from)) {
                  setError("parameters.to", {
                    type: "custom",
                    message: "End date can't be before begin date",
                  });
                  return;
                }
              } else if (data.activationStrategy === "HOUR_RANGE") {
                const startNumber = Number(
                  data.parameters.startAt.replaceAll(":", "")
                );
                const endNumber = Number(
                  data.parameters.endAt.replaceAll(":", "")
                );
                if (endNumber < startNumber) {
                  setError("parameters.endAt", {
                    type: "custom",
                    message: "End hour can't be before start hour",
                  });
                  return;
                }
              }
              props.submit(data);
            })}
            className="d-flex flex-column col-8 flex-shrink-1"
            style={{ marginRight: "32px" }}
          >
            <label>
              ID*
              <Tooltip id="legacy-id">
                Legacy features let you define id.
                <br />
                This id will be used in call URL.
                <br />
                Changing it after feature creation may break client calls.
              </Tooltip>
              <input
                autoFocus={true}
                className="form-control"
                type="text"
                {...register("id", {
                  required: "ID is required",
                  pattern: {
                    value: LEGACY_ID_REGEXP,
                    message: `ID must match ${LEGACY_ID_REGEXP}`,
                  },
                })}
              />
              <ErrorDisplay error={errors.id} />
            </label>
            <label className="mt-3">
              Name*
              <Tooltip id="legacy-name">
                Feature name, use something meaningfull, it can be modified
                later without impacts.
              </Tooltip>
              <input
                className="form-control"
                type="text"
                {...register("name", { required: "Name is required" })}
              />
              <ErrorDisplay error={errors.name} />
            </label>
            <label className="mt-3">
              Tags
              <Tooltip id="legacy-tags">
                Tags are a way to regroup related features even if they are in
                different projects.
              </Tooltip>
              <Controller
                name="tags"
                control={control}
                render={({ field: { onChange, value } }) => (
                  <CreatableSelect
                    value={value?.map((t) => ({ label: t, value: t }))}
                    onChange={(values) => {
                      onChange(values.map((v) => v.value));
                    }}
                    options={tagQuery.data.map((t) => ({
                      label: t.name,
                      value: t.name,
                    }))}
                    styles={customStyles}
                    isMulti
                    isClearable
                  />
                )}
              />
            </label>
            <label className="mt-3">
              Enabled
              <Tooltip id="legacy-enabled">
                Whether feature is enabled or disabled. The disabled feature is
                an inactive event if its conditions match.
              </Tooltip>
              <input
                type="checkbox"
                className="izanami-checkbox"
                {...register("enabled")}
              />
            </label>
            <label className="mt-3">
              Description
              <input
                type="text"
                className="form-control"
                {...register("description")}
              />
            </label>
            <label className="mt-3">
              Strategy
              <Tooltip id="legacy-strategy">
                Activation strategy for this feature.
                <br />
                Strategy will only be evaluated if feature is enabled.
                <br /> See documentation for more details on each strategy.
              </Tooltip>
              <Controller
                name="activationStrategy"
                control={control}
                render={({ field: { onChange, value } }) => (
                  <Select
                    value={value ? { label: value, value } : undefined}
                    onChange={(values) => onChange(values?.value)}
                    options={LegacyStrategies.map((l) => ({
                      label: l,
                      value: l,
                    }))}
                    styles={customStyles}
                  />
                )}
              />
            </label>
            {strategy === "PERCENTAGE" && (
              <label className="mt-3">
                Percentage of users that should activate feature*
                <input
                  type="number"
                  className="form-control"
                  {...register("parameters.percentage", {
                    valueAsNumber: true,
                    required: "Percentage must be defined",
                    min: {
                      value: 0,
                      message: "Percentage can't be negative",
                    },
                    max: {
                      value: 100,
                      message: "Percentage can't be greater than 100",
                    },
                  })}
                />
                <ErrorDisplay
                  error={
                    (errors as FieldErrors<PercentageFeature>)?.parameters
                      ?.percentage
                  }
                />
              </label>
            )}
            {strategy === "CUSTOMERS_LIST" && (
              <label className="mt-3">
                Users that should activate feature
                <Controller
                  name="parameters.customers"
                  control={control}
                  render={({ field: { onChange, value } }) => (
                    <CreatableSelect
                      value={value?.map((v) => ({ value: v, label: v }))}
                      onChange={(e) => {
                        onChange(e.map((v) => v.value));
                      }}
                      styles={customStyles}
                      isMulti
                      isClearable
                    />
                  )}
                />
              </label>
            )}
            {strategy === "RELEASE_DATE" && (
              <>
                <label className="mt-3">
                  Release date*
                  <Controller
                    name="parameters.date"
                    control={control}
                    rules={{
                      required: "Release date must be defined",
                    }}
                    render={({ field: { onChange, value } }) => {
                      return (
                        <input
                          value={
                            value && !isNaN(value.getTime())
                              ? format(value, "yyyy-MM-dd'T'HH:mm")
                              : ""
                          }
                          onChange={(e) => {
                            onChange(
                              parse(
                                e.target.value,
                                "yyyy-MM-dd'T'HH:mm",
                                new Date()
                              )
                            );
                          }}
                          className="form-control"
                          type="datetime-local"
                        />
                      );
                    }}
                  />
                </label>
                <ErrorDisplay
                  error={
                    (errors as FieldErrors<ReleaseDateFeature>)?.parameters
                      ?.date
                  }
                />
                <label className="mt-3">
                  Timezone
                  <Controller
                    name="parameters.timezone"
                    defaultValue={DEFAULT_TIMEZONE}
                    control={control}
                    render={({ field: { onChange, value } }) => (
                      <TimeZoneSelect onChange={onChange} value={value} />
                    )}
                  />
                </label>
              </>
            )}
            {strategy === "DATE_RANGE" && (
              <>
                <div className="mt-3">
                  <label>
                    Start date*
                    <Controller
                      name="parameters.from"
                      control={control}
                      rules={{
                        required: "Start date must be defined",
                      }}
                      render={({ field: { onChange, value } }) => {
                        return (
                          <input
                            value={
                              value && !isNaN(value.getTime())
                                ? format(value, "yyyy-MM-dd'T'HH:mm")
                                : ""
                            }
                            onChange={(e) => {
                              onChange(
                                parse(
                                  e.target.value,
                                  "yyyy-MM-dd'T'HH:mm",
                                  new Date()
                                )
                              );
                            }}
                            className="form-control"
                            type="datetime-local"
                          />
                        );
                      }}
                    />
                  </label>
                  <label className="mx-2">
                    End date*
                    <Controller
                      name="parameters.to"
                      control={control}
                      rules={{
                        required: "End date must be defined",
                      }}
                      render={({ field: { onChange, value } }) => {
                        return (
                          <input
                            value={
                              value && !isNaN(value.getTime())
                                ? format(value, "yyyy-MM-dd'T'HH:mm")
                                : ""
                            }
                            onChange={(e) => {
                              onChange(
                                parse(
                                  e.target.value,
                                  "yyyy-MM-dd'T'HH:mm",
                                  new Date()
                                )
                              );
                            }}
                            className="form-control"
                            type="datetime-local"
                          />
                        );
                      }}
                    />
                  </label>
                </div>
                <ErrorDisplay
                  error={
                    (errors as FieldErrors<DateRangeFeature>)?.parameters?.from
                  }
                />
                <ErrorDisplay
                  error={
                    (errors as FieldErrors<DateRangeFeature>)?.parameters?.to
                  }
                />
                <label className="mt-3">
                  Timezone
                  <Controller
                    name="parameters.timezone"
                    defaultValue={DEFAULT_TIMEZONE}
                    control={control}
                    render={({ field: { onChange, value } }) => (
                      <TimeZoneSelect onChange={onChange} value={value} />
                    )}
                  />
                </label>
              </>
            )}
            {strategy === "HOUR_RANGE" && (
              <>
                <div className="mt-3">
                  <label className="me-2">
                    From*
                    <Controller
                      name="parameters.startAt"
                      control={control}
                      rules={{
                        required: "Start time must be defined",
                      }}
                      render={({ field: { onChange, value } }) => {
                        return (
                          <input
                            className="form-control"
                            type="time"
                            value={
                              value
                                ? format(
                                    parse(value, "HH:mm:ss", new Date()),
                                    "HH:mm"
                                  )
                                : ""
                            }
                            onChange={(e) => {
                              onChange(
                                format(
                                  parse(e.target.value, "HH:mm", new Date()),
                                  "HH:mm:ss"
                                )
                              );
                            }}
                          />
                        );
                      }}
                    />
                  </label>
                  <label className="mx-2">
                    To*
                    <Controller
                      name="parameters.endAt"
                      control={control}
                      rules={{
                        required: "End time must be defined",
                      }}
                      render={({ field: { onChange, value } }) => {
                        return (
                          <input
                            className="form-control"
                            type="time"
                            value={
                              value
                                ? format(
                                    parse(value, "HH:mm:ss", new Date()),
                                    "HH:mm"
                                  )
                                : ""
                            }
                            onChange={(e) => {
                              onChange(
                                format(
                                  parse(e.target.value, "HH:mm", new Date()),
                                  "HH:mm:ss"
                                )
                              );
                            }}
                          />
                        );
                      }}
                    />
                  </label>
                </div>
                <ErrorDisplay
                  error={
                    (errors as FieldErrors<HourRangeFeature>)?.parameters
                      ?.startAt
                  }
                />
                <ErrorDisplay
                  error={
                    (errors as FieldErrors<HourRangeFeature>)?.parameters?.endAt
                  }
                />
                <label className="mt-3">
                  Timezone
                  <Controller
                    name="parameters.timezone"
                    defaultValue={DEFAULT_TIMEZONE}
                    control={control}
                    render={({ field: { onChange, value } }) => (
                      <TimeZoneSelect onChange={onChange} value={value} />
                    )}
                  />
                </label>
              </>
            )}
            <div className="d-flex justify-content-end mt-3">
              <button
                type="button"
                className="btn btn-danger m-2"
                onClick={() => props.cancel()}
              >
                Cancel
              </button>
              <button className="btn btn-success m-2" type="submit">
                Save
              </button>
            </div>
          </form>
        </div>
      </FormProvider>
    );
  } else if (tagQuery.isLoading) {
    return <Loader message="Loading tags..." />;
  } else {
    return <div>Failed to fetch tags</div>;
  }
}

export function FeatureForm(props: {
  cancel: () => void;
  submit: (overload: TCompleteFeature) => void;
  defaultValue?: TLightFeature;
  additionalFields?: () => JSX.Element;
}) {
  const { tenant } = useParams();
  const { defaultValue, submit, ...rest } = props;

  const completeFeatureQuery = useQuery(
    defaultValue ? JSON.stringify(defaultValue) : "",
    () => toCompleteFeature(tenant!, defaultValue!),
    { enabled: !!defaultValue }
  );

  const [legacy, setLegacy] = useState<boolean>(
    defaultValue !== undefined && isSingleConditionFeature(defaultValue)
  );

  const [convertedValue, setConvertedValue] = useState<
    ClassicalFeature | undefined
  >(undefined);

  if (completeFeatureQuery.data || completeFeatureQuery.isIdle) {
    const defaultValue = completeFeatureQuery.data;
    let form = undefined;
    if (legacy) {
      form = (
        <LegacyFeatureForm
          defaultValue={toLegacyFeatureFormat(
            defaultValue as SingleConditionFeature
          )}
          submit={(f) => {
            submit(toSingleConditionFeatureFormat(f));
          }}
          {...rest}
        />
      );
    } else {
      const { defaultValue: df, ...rest } = props;
      form = (
        <V2FeatureForm
          {...rest}
          defaultValue={convertedValue ? convertedValue : defaultValue}
        />
      );
    }

    return (
      <>
        {defaultValue && legacy ? (
          <label>
            <button
              className="btn btn-secondary"
              onClick={() => {
                setConvertedValue(
                  toModernFeature(
                    defaultValue as SingleConditionFeature
                  ) as ClassicalFeature
                );
                setLegacy(false);
              }}
            >
              Convert to modern feature
            </button>
            <Tooltip id="modern-conversion">
              Convert this feature from modern to legacy.
              <br />
              ⚠️ This will beak v1 client calls.
            </Tooltip>
          </label>
        ) : !defaultValue ? (
          <label
            style={{
              display: "flex",
              alignItems: "center",
              marginTop: "10px",
              marginBottom: "10px",
            }}
          >
            Create legacy feature&nbsp;
            <input
              type="checkbox"
              className="izanami-checkbox"
              style={{ display: "inline-flex", marginTop: 0 }}
              onChange={(e) => {
                setLegacy(e.target.checked);
              }}
            />
          </label>
        ) : (
          <></>
        )}
        <hr />
        {form}
      </>
    );
  } else if (completeFeatureQuery.isError) {
    return <div>Failed to load feature details</div>;
  } else {
    return <div>Loading...</div>;
  }
}

export function V2FeatureForm(props: {
  cancel: () => void;
  submit: (overload: TCompleteFeature) => void;
  defaultValue?: TCompleteFeature;
  additionalFields?: () => JSX.Element;
}) {
  const { cancel, submit, defaultValue, additionalFields } = props;
  const methods = useForm<TCompleteFeature>({ defaultValues: defaultValue });
  const { tenant } = useParams();
  const isWasm = defaultValue && isWasmFeature(defaultValue);
  const [type, setType] = useState(isWasm ? "Existing WASM script" : "Classic");
  const tagQuery = useQuery(tagsQueryKey(tenant!), () => queryTags(tenant!));

  const {
    control,
    register,
    handleSubmit,
    formState: { errors },
    setValue,
    setError,
  } = methods;

  console.log("errors", errors);

  React.useEffect(() => {
    if (type === "Existing WASM script") {
      setValue("wasmConfig.source.kind", "Local");
    }
  }, [type]);

  if (tagQuery.isError) {
    return <div>Error while fetching tags</div>;
  } else if (tagQuery.data) {
    return (
      <FormProvider {...methods}>
        <div className="d-flex justify-content-between">
          <form
            onSubmit={handleSubmit((data) => {
              console.log("data", data);
              let error = false;

              if (!isWasmFeature(data) && !isSingleConditionFeature(data)) {
                data.conditions.forEach((cond, index) => {
                  if (isPercentageRule(cond?.rule)) {
                    if (cond?.rule.percentage < 0) {
                      setError(`conditions.${index}.rule.percentage`, {
                        type: "custom",
                        message: "Percentage can't be less than 0",
                      });
                      error = error || true;
                    }
                    if (cond?.rule.percentage > 100) {
                      setError(`conditions.${index}.rule.percentage`, {
                        type: "custom",
                        message: "Percentage can't be greater than 100",
                      });
                      error = error || true;
                    }
                  }
                });
                data.conditions.forEach((cond: TCondition, index: number) => {
                  if (
                    cond?.period?.begin &&
                    cond?.period?.end &&
                    isBefore(cond?.period.end, cond?.period.begin)
                  ) {
                    setError(`conditions.${index}.period.end`, {
                      type: "custom",
                      message: "End date can't be before begin date",
                    });
                    error = error || true;
                  }

                  cond?.period?.hourPeriods?.forEach(
                    (hourPeriod, hourPeriodIndex) => {
                      if (hourPeriod?.endTime && hourPeriod?.startTime) {
                        const startNumber = Number(
                          hourPeriod.startTime.replaceAll(":", "")
                        );
                        const endNumber = Number(
                          hourPeriod.endTime.replaceAll(":", "")
                        );

                        if (endNumber < startNumber) {
                          setError(
                            `conditions.${index}.period.hourPeriods.${hourPeriodIndex}.endTime`,
                            {
                              type: "custom",
                              message: "End hour can't be before start hour",
                            }
                          );
                          error = error || true;
                        }
                      }
                    }
                  );
                });
              }

              if (
                isWasmFeature(data) &&
                data?.wasmConfig?.source?.kind !== "Local"
              ) {
                if (!data?.wasmConfig?.source?.path) {
                  setError("wasmConfig.source.path", {
                    type: "custom",
                    message: "Missing script path",
                  });
                  error = error || true;
                }

                if (!data?.wasmConfig?.functionName) {
                  setError("wasmConfig.functionName", {
                    type: "custom",
                    message: "Function name is mandatory",
                  });
                  error = error || true;
                }
              }

              if (error) {
                return;
              }
              submit(data);
            })}
            className="d-flex flex-column col-8 flex-shrink-1"
            style={{ marginRight: "32px" }}
          >
            <label>
              Name*
              <Tooltip id="modern-name">
                Feature name, use something meaningfull, it can be modified
                later without impacts.
              </Tooltip>
              <input
                autoFocus={true}
                className="form-control"
                type="text"
                {...register("name", {
                  required: "Feature name can't be empty",
                  pattern: {
                    value: FEATURE_NAME_REGEXP,
                    message: `Feature name must match ${FEATURE_NAME_REGEXP}`,
                  },
                })}
              />
              <ErrorDisplay error={errors.name} />
            </label>
            {additionalFields && additionalFields()}
            <label className="mt-3">
              Tags
              <Tooltip id="modern-tags">
                Tags are a way to regroup related features even if they are in
                different projects.
              </Tooltip>
              <Controller
                name="tags"
                control={control}
                render={({ field: { onChange, value } }) => (
                  <CreatableSelect
                    value={value?.map((t) => ({ label: t, value: t }))}
                    onChange={(values) => {
                      onChange(values.map((v) => v.value));
                    }}
                    options={tagQuery.data.map((t) => ({
                      label: t.name,
                      value: t.name,
                    }))}
                    styles={customStyles}
                    isMulti
                    isClearable
                  />
                )}
              />
            </label>

            <label className="mt-3">
              Enabled
              <Tooltip id="modern-enabled">
                Whether feature is enabled or disabled. The disabled feature is
                an inactive event if its conditions match.
              </Tooltip>
              <input
                type="checkbox"
                className="izanami-checkbox"
                {...register("enabled")}
              />
            </label>
            <label className="mt-3">
              Description
              <input
                className="form-control"
                type="text"
                {...register("description")}
              />
            </label>
            <label className="mt-3">
              Feature type
              <Tooltip id="feature-type">
                Classic features will allow the use of user and/or time
                conditions.
                <br />
                New WASM script will allow to create a feature using a specified
                WASMO or base64 wasm script.
                <br />
                Existing WASM script will create a feature using an existing
                script (a script that is used by another feature).
              </Tooltip>
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
                }}
              />
            </label>
            {type === "Classic" && <ConditionsInput />}
            {type === "Existing WASM script" && <ExistingScript />}
            {type === "New WASM script" && <WasmInput />}
            <div
              className="d-flex justify-content-end"
              style={{
                position: "sticky",
                bottom: "0px",
                zIndex: "1",
                width: "170px",
                borderRadius: "10px",
                alignSelf: "end",
                backgroundColor: "var(--bg-color_level2)",
              }}
            >
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
          <div className="col-4">
            <CustomTestForm />
          </div>
        </div>
      </FormProvider>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}

function CustomTestForm() {
  const { getValues } = useFormContext<TCompleteFeature>();
  useWatch();
  return (
    <div className="sub_container sub_container-bglighter ">
      <h5>Test it</h5>
      <FeatureTestForm feature={getValues()} noContext />
    </div>
  );
}
