import * as React from "react";
import { DAYS, TContextOverload, THourPeriod } from "../utils/types";
import Select from "react-select";
import CreatableSelect from "react-select/creatable";
import { customStyles } from "../styles/reactSelect";
import { Strategy } from "./FeatureTable";
import { useEffect } from "react";
import { useFieldArray, Controller, useFormContext } from "react-hook-form";
import { format, parse, startOfDay } from "date-fns";
import { DEFAULT_TIMEZONE, TimeZoneSelect } from "./TimeZoneSelect";
import { ErrorDisplay } from "./FeatureForm";
import { Tooltip } from "./Tooltip";

export function ConditionsInput() {
  const { control } = useFormContext();

  const { fields, append, remove } = useFieldArray({
    control,
    name: "conditions",
  });

  return (
    <>
      {fields.map((condition, index) => (
        <fieldset
          className="mt-2 sub_container sub_container-bglighter anim__popUp"
          key={`condition-${index}`}
        >
          <h5>
            Activation condition #{index}{" "}
            <button
              className="btn btn-danger btn-sm m-2"
              type="button"
              onClick={() => {
                remove(index);
              }}
            >
              Delete
            </button>
          </h5>
          <ConditionInput index={index} />
        </fieldset>
      ))}
      <div
        className="d-flex flex-row justify-content-end"
        style={{ marginTop: "8px" }}
      >
        <button
          className="btn btn-secondary btn-sm mb-2 ms-3"
          type="button"
          onClick={() => append({})}
        >
          {fields.length > 0 ? "Add condition (OR)" : "Add condition"}
        </button>
        <label>
          <Tooltip id="add-condition">
            Add another activation condition.
            <br />
            One active condition is sufficient to activate the feature.
          </Tooltip>
        </label>
      </div>
    </>
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
  } = useFormContext<TContextOverload>();
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
  const strategy =
    percentage !== undefined
      ? Strategy.percentage.id
      : users !== undefined
      ? Strategy.userList.id
      : Strategy.all.id;

  return (
    <>
      <fieldset style={{ border: "none" }}>
        <legend>
          <h6>Time rule</h6>
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
                  setValue(`conditions.${index}.period`, {
                    begin: startOfDay(new Date()),
                    activationDays: {
                      days: DAYS.slice(),
                    },
                    hourPeriods: [],
                    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
                  });
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
          <h6>User rule</h6>
        </legend>
        <label>
          Strategy to use
          <Tooltip id="user-strategy">
            All activate this rule for every user.
            <br />
            Percentage activate this rule only for the given percentage of
            users.
            <br />
            User list activates this rule only for specified users.
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
  } = useFormContext<TContextOverload>();

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
                    onChange(
                      parse(e.target.value, "yyyy-MM-dd'T'HH:mm", new Date())
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
                    onChange(
                      parse(e.target.value, "yyyy-MM-dd'T'HH:mm", new Date())
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
              value={value?.days.map((v) => ({ value: v, label: v }))}
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
      </label>

      <HourRanges index={index} />
    </>
  );
}

function HourRanges(props: { index: number }) {
  const {
    control,
    formState: { errors },
  } = useFormContext<TContextOverload>();
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
