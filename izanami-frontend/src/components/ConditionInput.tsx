import * as React from "react";
import {
  DAYS,
  TClassicalCondition,
  TClassicalContextOverload,
  THourPeriod,
  ValuedFeature,
} from "../utils/types";
import Select from "react-select";
import CreatableSelect from "react-select/creatable";
import { customStyles } from "../styles/reactSelect";
import { Rule, Strategy, Period as PeriodDetails } from "./FeatureTable";
import { useEffect } from "react";
import {
  useFieldArray,
  Controller,
  useFormContext,
  FieldErrors,
} from "react-hook-form";
import { format, parse, startOfDay } from "date-fns";
import { TimeZoneSelect } from "./TimeZoneSelect";
import { ErrorDisplay } from "./FeatureForm";
import { Tooltip } from "./Tooltip";
import { DEFAULT_TIMEZONE } from "../utils/datetimeUtils";

export function ConditionsInput(props: { folded: boolean }) {
  const { control, watch } = useFormContext();

  const { fields, append, remove } = useFieldArray({
    control,
    name: "conditions",
  });

  const resultType = watch("resultType");
  const conditions = watch("conditions");
  return (
    <>
      {fields.length > 0 ? (
        resultType === "boolean" ? (
          <h5 className="mt-4">Activation conditions</h5>
        ) : (
          <h5 className="mt-4">Alternative values</h5>
        )
      ) : null}
      {fields.map(({ id }, index) => {
        const condition = conditions?.[index];
        const emptyCondition =
          Object.entries(condition).filter(([, value]) => value !== "")
            .length === 0;

        return (
          <div
            className="accordion accordion-dark"
            id={`${id}-accordion`}
            key={id}
          >
            <div className="accordion-item mt-3">
              <h3 className="accordion-header">
                <button
                  className={`accordion-button ${
                    props.folded ? "collapsed" : ""
                  }`}
                  type="button"
                  data-bs-toggle="collapse"
                  data-bs-target={`#${id}-accordion-collapse`}
                  aria-expanded={!props.folded}
                  aria-controls={`${id}-accordion-collapse`}
                >
                  <div className="d-flex align-items-center justify-content-between w-100">
                    <div className="d-flex flex-row align-items-center">
                      <div>
                        {resultType === "boolean" ? (
                          <>
                            <span className="fw-bold">
                              Activation condition #{index}
                            </span>
                            {emptyCondition ? (
                              <>&nbsp;{"<Empty condition>"}</>
                            ) : (
                              ""
                            )}
                          </>
                        ) : emptyCondition ? (
                          "<No value specified>"
                        ) : (
                          <>
                            Alternative value{" "}
                            <span className="fw-bold">{condition?.value}</span>
                          </>
                        )}
                        &nbsp;
                      </div>
                      {condition && !emptyCondition ? (
                        <>
                          <ConditionSummary condition={condition} />
                        </>
                      ) : null}
                    </div>
                    <button
                      className="btn btn-danger btn-sm"
                      type="button"
                      onClick={() => {
                        remove(index);
                      }}
                    >
                      Delete
                    </button>
                  </div>
                </button>
              </h3>
              <div
                className={`accordion-collapse collapse ${
                  props.folded ? "" : "show"
                }`}
                aria-labelledby="headingOne"
                data-bs-parent={`#${id}-accordion`}
                id={`${id}-accordion-collapse`}
              >
                <div className="accordion-body">
                  <ConditionInput index={index} />
                </div>
              </div>
            </div>
          </div>
        );
      })}
      <div className="d-flex align-items-center justify-content-end mb-2 ms-3 mt-3">
        <button
          className="btn btn-secondary btn-sm"
          type="button"
          onClick={() => {
            append({});
            // This is super dirty, however bootstrap accordion state is really hard to control programatically
            // the need is to have existing accordions closed by default (on edition) but to have new one opened

            if (props.folded) {
              setTimeout(() => {
                const buttons = document.querySelectorAll(
                  "button.accordion-button"
                );
                const last = buttons?.[buttons.length - 1] as HTMLButtonElement;
                last.click();
              }, 0);
            }
          }}
        >
          {resultType === "boolean"
            ? fields.length > 0
              ? "Add condition (OR)"
              : "Add condition"
            : "Add alternative value"}
        </button>
        <label>
          <Tooltip id="add-condition">
            {resultType === "boolean" ? (
              <>
                Add an activation condition.
                <br />
                One active condition is sufficient to activate the feature.
              </>
            ) : (
              <>
                Add an alterative value
                <br />
                Feature value will be its firts matching alternative value.
              </>
            )}
          </Tooltip>
        </label>
      </div>
    </>
  );
}

function ConditionSummary(props: { condition: TClassicalCondition }) {
  const { period, rule } = props.condition;

  return (
    <div
      className="d-flex flex-column border-start border-2"
      style={{
        paddingLeft: "8px",
      }}
    >
      {period && <PeriodDetails period={period as any} />}
      {rule && <Rule rule={rule} />}
    </div>
  );
}

function ConditionInput(props: { index: number }) {
  const [specificPeriods, setSpecificPeriods] = React.useState(false);
  const { index } = props;
  const {
    register,
    control,
    getValues,
    setValue,
    watch,
    formState: { errors },
  } = useFormContext<TClassicalContextOverload>();
  useEffect(() => {
    const defaultPeriod = getValues(`conditions.${index}.period`);
    const hasSpecificPeriod =
      Boolean(defaultPeriod?.begin) ||
      Boolean(defaultPeriod?.end) ||
      (defaultPeriod?.hourPeriods?.length ?? 0) > 0 ||
      (defaultPeriod?.activationDays?.days?.length ?? 0) > 0;

    setSpecificPeriods(hasSpecificPeriod);
  }, []);

  const users = watch(`conditions.${index}.rule.users`);
  const percentage = watch(`conditions.${index}.rule.percentage`);
  const resultType = watch("resultType");

  const strategy =
    percentage !== undefined
      ? Strategy.percentage.id
      : users !== undefined
      ? Strategy.userList.id
      : Strategy.all.id;

  return (
    <>
      <fieldset style={{ border: "none" }}>
        {resultType !== "boolean" && (
          <>
            <legend>
              <h5>Result value</h5>
            </legend>
            <label className="mb-4">
              Alternative value
              {resultType === "string" ? (
                <input
                  type="text"
                  className="form-control"
                  {...register(`conditions.${index}.value`)}
                />
              ) : (
                <input
                  type="number"
                  step="any"
                  className="form-control"
                  {...register(`conditions.${index}.value`)}
                />
              )}
              <ErrorDisplay
                error={
                  (errors as FieldErrors<ValuedFeature>)?.conditions?.[index]
                    ?.value as any
                }
              />
            </label>
          </>
        )}
        <legend>
          <h5>Time rule</h5>
        </legend>
        <label>
          Active only on specific periods
          <Tooltip id="specific-period">
            Check this to add time activation rules for your feature.
            <br />
            Both time rule and user rule must be active to activate this
            condition.
          </Tooltip>
          <input
            type="checkbox"
            className="izanami-checkbox"
            checked={specificPeriods}
            onChange={(e) => {
              if (e.target.checked) {
                const period = getValues(`conditions.${index}.period`);
                if (!period || JSON.stringify(period) === "{}") {
                  setValue(
                    `conditions.${index}.period`,
                    {
                      begin: startOfDay(new Date()),
                      activationDays: {
                        days: DAYS.slice(),
                      },
                      hourPeriods: [],
                      timezone:
                        Intl.DateTimeFormat().resolvedOptions().timeZone,
                      end: null as any,
                    },
                    {
                      shouldValidate: true,
                    }
                  );
                }
              } else {
                setValue(`conditions.${index}.period`, undefined);
              }
              setSpecificPeriods(e.target.checked);
            }}
          />
        </label>
        {specificPeriods && <Period index={index} />}
      </fieldset>
      <fieldset style={{ border: "none" }} className="mt-3">
        <legend>
          <h5>User rule</h5>
        </legend>
        <label>
          Strategy to use
          <Tooltip id="user-strategy">
            All - activate this rule for every user.
            <br />
            Percentage - activate this rule only for the given percentage of
            users.
            <br />
            User list - activate this rule only for specified users.
            <br />
            Both time rule and user rule must be active to activate this
            condition.
          </Tooltip>
          <Select
            styles={customStyles}
            options={Object.values(Strategy).map(({ label, id }) => ({
              value: id,
              label: label,
            }))}
            value={{
              value: strategy,
              label: Object.values(Strategy).filter(({ id }) => {
                return id === strategy;
              })?.[0]?.label,
            }}
            onChange={(e) => {
              if (e?.value === Strategy.percentage.id) {
                setValue(`conditions.${index}.rule.users`, undefined as any);
                setValue(`conditions.${index}.rule.percentage`, 50);
              } else if (e?.value === Strategy.userList.id) {
                setValue(
                  `conditions.${index}.rule.percentage`,
                  undefined as any
                );
                setValue(`conditions.${index}.rule.users`, []);
              } else {
                setValue(`conditions.${index}.rule.users`, undefined as any);
                setValue(
                  `conditions.${index}.rule.percentage`,
                  undefined as any
                );
              }
            }}
          />
        </label>
        {strategy === Strategy.percentage.id && (
          <>
            <label className="mt-3">
              Percentage of users that should activate feature
              <input
                type="number"
                className="form-control"
                {...register(`conditions.${index}.rule.percentage`, {
                  required: "Percentage must be defined",
                  min: {
                    value: 0,
                    message: "Percentage can't be negative",
                  },
                  max: {
                    value: 100,
                    message: "Percentage can't be greater than 100",
                  },
                  valueAsNumber: true,
                })}
              />
            </label>
            <ErrorDisplay
              error={(errors?.conditions?.[index]?.rule as any)?.percentage}
            />
          </>
        )}
        {strategy === Strategy.userList.id && (
          <label className="mt-3">
            Users that should activate feature
            <Controller
              rules={{
                required: "At least one user should be specified",
                minLength: 1,
              }}
              name={`conditions.${index}.rule.users`}
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
            <ErrorDisplay
              error={(errors?.conditions?.[index]?.rule as any)?.users}
            />
          </label>
        )}
      </fieldset>
    </>
  );
}

function Period(props: { index: number }) {
  const { index } = props;
  const {
    control,
    formState: { errors },
  } = useFormContext<TClassicalContextOverload>();

  return (
    <>
      <div>
        <label className="mt-3">
          Date range from&nbsp;
          <Controller
            name={`conditions.${index}.period.begin`}
            control={control}
            render={({ field: { onChange, value } }) => {
              return (
                <input
                  value={
                    value && !isNaN(value.getTime())
                      ? format(value, "yyyy-MM-dd'T'HH:mm")
                      : ""
                  }
                  onChange={(e) => {
                    const value = e.target?.value;
                    onChange(
                      value
                        ? parse(value, "yyyy-MM-dd'T'HH:mm", new Date())
                        : null
                    );
                  }}
                  type="datetime-local"
                  aria-label="date-range-from"
                />
              );
            }}
          />
        </label>
        <label>
          &nbsp;to&nbsp;
          <Controller
            name={`conditions.${index}.period.end`}
            control={control}
            render={({ field: { onChange, value } }) => {
              return (
                <input
                  value={
                    value && !isNaN(value.getTime())
                      ? format(value, "yyyy-MM-dd'T'HH:mm")
                      : ""
                  }
                  onChange={(e) => {
                    const value = e.target?.value;
                    onChange(
                      value
                        ? parse(
                            e.target.value,
                            "yyyy-MM-dd'T'HH:mm",
                            new Date()
                          )
                        : null
                    );
                  }}
                  type="datetime-local"
                  aria-label="date-range-to"
                />
              );
            }}
          />
        </label>
        <ErrorDisplay error={errors?.conditions?.[index]?.period?.end} />
      </div>
      <label className="mt-3">
        Timezone
        <Controller
          name={`conditions.${index}.period.timezone`}
          defaultValue={DEFAULT_TIMEZONE}
          control={control}
          render={({ field: { onChange, value } }) => (
            <TimeZoneSelect onChange={onChange} value={value} />
          )}
        />
      </label>
      <label className="mt-3">
        Activation days
        <Controller
          name={`conditions.${index}.period.activationDays`}
          control={control}
          render={({ field: { onChange, value } }) => (
            <Select
              value={value?.days
                .sort((v1, v2) => {
                  return DAYS.indexOf(v1) - DAYS.indexOf(v2);
                })
                .map((v) => ({ value: v, label: v }))}
              onChange={(e) => {
                onChange({
                  days: e.map((v) => v.value),
                  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
                });
              }}
              styles={customStyles}
              options={DAYS.map((d) => ({
                label: d,
                value: d,
              }))}
              isMulti
              isClearable
            />
          )}
        />
        <ErrorDisplay
          name={`conditions.[${index}].period.activationDays`}
          errors={errors}
        />
      </label>

      <HourRanges index={index} />
    </>
  );
}

function HourRanges(props: { index: number }) {
  const {
    control,
    formState: { errors },
  } = useFormContext<TClassicalContextOverload>();
  const { index: parentIndex } = props;
  const { fields, append, remove } = useFieldArray({
    control,
    name: `conditions.${parentIndex}.period.hourPeriods`,
  });

  return (
    <>
      <div className="mt-3 d-flex align-items-center">
        <label className="">
          Time range
          <Tooltip id="time-range">
            Specify time ranges on which feature will be active (if matching all
            other rules).
          </Tooltip>
        </label>
        <button
          className="btn btn-primary btn-sm ms-3"
          type="button"
          onClick={() => append({} as THourPeriod)}
        >
          Add
        </button>
      </div>
      <div
        style={{ display: "flex", flexDirection: "column", flexWrap: "wrap" }}
      >
        {fields.map((hourRange, index) => (
          <fieldset
            key={`period-${index}`}
            style={{ marginRight: "24px", marginLeft: "10px", border: "none" }}
          >
            <div className="d-flex align-items-center">
              <label className="me-2" htmlFor={`date-range-start-${index}`}>
                From
              </label>
              <Controller
                name={`conditions.${parentIndex}.period.hourPeriods.${index}.startTime`}
                control={control}
                render={({ field: { onChange, value } }) => {
                  return (
                    <input
                      id={`date-range-start-${index}`}
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
              <label className="mx-2" htmlFor={`date-range-end-${index}`}>
                to
              </label>
              <Controller
                name={`conditions.${parentIndex}.period.hourPeriods.${index}.endTime`}
                control={control}
                render={({ field: { onChange, value } }) => {
                  return (
                    <input
                      id={`date-range-end-${index}`}
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

              <button
                className="btn btn-danger btn-sm ms-3"
                type="button"
                onClick={() => remove(index)}
              >
                Delete
              </button>
            </div>
            <ErrorDisplay
              error={
                errors?.conditions?.[parentIndex]?.period?.hourPeriods?.[index]
                  ?.endTime
              }
            />
          </fieldset>
        ))}
      </div>
    </>
  );
}
