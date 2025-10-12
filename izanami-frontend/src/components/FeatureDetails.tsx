import { format } from "date-fns";
import { toLegacyFeatureFormat } from "../utils/featureUtils";
import {
  ClassicalFeature,
  DAYS,
  isLightWasmFeature,
  isPercentageRule,
  isSingleConditionFeature,
  isUserListRule,
  TClassicalCondition,
  TDayOfWeepPeriod,
  TFeaturePeriod,
  TFeatureRule,
  THourPeriod,
  TLightFeature,
  TValuedCondition,
} from "../utils/types";
import { LegacyFeature } from "./FeatureForm";
import React, { JSX } from "react";

export function TextualFeatureDetails({ feature }: { feature: TLightFeature }) {
  if (isLightWasmFeature(feature)) {
    return (
      <>
        {feature.description && <div>{feature.description}</div>}
        <ScriptDetails config={feature.wasmConfig} />
      </>
    );
  } else if (isSingleConditionFeature(feature)) {
    return (
      <>
        {feature.description && <div>{feature.description}</div>}
        <div className="fw-semibold">Active :</div>
        <SingleConditionFeatureDetail
          feature={toLegacyFeatureFormat(feature)}
        />
      </>
    );
  } else {
    const resultDetail =
      feature.resultType === "boolean"
        ? { resultType: "boolean" }
        : { resultType: feature.resultType, value: feature.value };
    return (
      <>
        {feature.description && <div>{feature.description}</div>}
        <ConditionDetails
          conditions={(feature as ClassicalFeature).conditions}
          resultDetail={resultDetail as any}
        />
      </>
    );
  }
}

function ScriptDetails({ config }: { config: string }) {
  return <div>Depends on script {config}</div>;
}

function SingleConditionFeatureDetail({ feature }: { feature: LegacyFeature }) {
  switch (feature.activationStrategy) {
    case "PERCENTAGE":
      return <>For {feature.parameters.percentage}% of users</>;
    case "CUSTOMERS_LIST":
      return <>Only for : {feature.parameters.customers.join(", ")}</>;
    case "NO_STRATEGY":
      return <>For all users</>;
    case "DATE_RANGE":
      return (
        <>
          from {format(feature.parameters.from, "PPP")} to{" "}
          {format(feature.parameters.to, "PPP")}
        </>
      );
    case "HOUR_RANGE":
      return (
        <>
          from {formatHours(feature.parameters.startAt)} to{" "}
          {formatHours(feature.parameters.endAt)}
        </>
      );
    case "RELEASE_DATE":
      return <>from {format(feature.parameters.date, "PPP")}</>;
    default:
      return <></>;
  }
}

function NonBooleanConditionsDetails({
  conditions,
  resultDetail,
}: {
  conditions: TValuedCondition<string | number>[];
  resultDetail: {
    resultType: "number" | "string";
    value: number | string;
  };
}) {
  const { value } = resultDetail;
  return (
    <>
      {conditions.map((cond, idx) => {
        return (
          <div key={idx}>
            <NonBooleanConditionDetails key={idx} condition={cond} />
            <div className="feature-separator">-OR-</div>
          </div>
        );
      })}
      <span className="fw-semibold">
        {conditions.length > 0 ? "Otherwise value is" : "Value is"}
      </span>
      &nbsp;
      <span className="fst-italic">{value}</span>
    </>
  );
}

function NonBooleanConditionDetails({
  condition,
}: {
  condition: TValuedCondition<string | number>;
}) {
  return (
    <div>
      <span className="fw-semibold mt-2">Value is</span>&nbsp;
      <span className="fst-italic">{condition.value}</span>
      <br />
      {condition.rule && <Rule rule={condition.rule} />}{" "}
      {condition.period && <Period period={condition.period} />}
    </div>
  );
}

export function ConditionDetails({
  conditions,
  resultDetail,
}: {
  conditions: TClassicalCondition[];
  resultDetail:
    | {
        resultType: "boolean";
      }
    | {
        resultType: "number" | "string";
        value: number | string;
      };
}) {
  if (resultDetail.resultType === "boolean") {
    return <BooleanConditionsDetails conditions={conditions} />;
  } else {
    return (
      <NonBooleanConditionsDetails
        conditions={conditions as TValuedCondition<any>[]}
        resultDetail={resultDetail}
      />
    );
  }
}

function BooleanConditionsDetails({
  conditions,
}: {
  conditions: TClassicalCondition[];
}) {
  if (conditions.length === 0) {
    return (
      <>
        <div className="fw-semibold">Active :</div>
        For all users
      </>
    );
  }
  return (
    <>
      {conditions.map(({ period, rule }, index, array) => {
        if (index === array.length - 1) {
          return (
            <React.Fragment key={index}>
              {(period || rule) && <div className="fw-semibold">Active : </div>}
              {period && <Period period={period} />}
              {rule && <Rule rule={rule} />}
            </React.Fragment>
          );
        } else {
          return (
            <React.Fragment key={index}>
              {(period || rule) && <div className="fw-semibold">Active : </div>}
              {period && <Period period={period} />}
              {rule && <Rule rule={rule} />}
              <div className="feature-separator">-OR-</div>
            </React.Fragment>
          );
        }
      })}
    </>
  );
}

function formatHours(hours: string): string {
  return hours;
}

function hours(hours: THourPeriod): string {
  return `from ${formatHours(hours.startTime)} to ${formatHours(
    hours.endTime
  )}`;
}

function days(days: TDayOfWeepPeriod): string {
  return `on ${days.days
    .sort((d1, d2) => {
      const index1 = DAYS.indexOf(d1);
      const index2 = DAYS.indexOf(d2);

      return index1 - index2;
    })
    .join(", ")}`;
}

export function PeriodDetails(props: { period: TFeaturePeriod }): JSX.Element {
  const { period } = props;
  if (period.activationDays || period.hourPeriods.length > 0) {
    return (
      <>
        {period.activationDays && period.activationDays.days.length != 7 && (
          <div>{days(period.activationDays)}</div>
        )}
        {period.hourPeriods.map((hour) => {
          const hourDisplay = hours(hour);
          return <div key={hourDisplay}>{hourDisplay}</div>;
        })}
      </>
    );
  } else {
    return <></>;
  }
}

export function Period({ period }: { period: TFeaturePeriod }): JSX.Element {
  let display = "";
  if (period.begin && period.end) {
    display = `from ${format(period.begin, "PPPp")} to ${format(
      period.end,
      "PPPp"
    )}`;
  } else if (period.begin) {
    display = `after ${format(period.begin, "PPPp")}`;
  } else if (period.end) {
    display = `until ${format(period.end, "PPPp")}`;
  }
  return (
    <>
      <div>{display}</div>
      <PeriodDetails period={period} />
    </>
  );
}

export function Rule(props: { rule: TFeatureRule }): JSX.Element {
  const { rule } = props;
  if (isPercentageRule(rule)) {
    return <>for {`${rule.percentage}% of users`}</>;
  } else if (isUserListRule(rule)) {
    return <>{`only for : ${rule.users.join(", ")}`}</>;
  } else {
    return <>for all users</>;
  }
}
