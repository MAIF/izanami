import { LegacyFeature } from "../components/FeatureForm";
import {
  ClassicalFeature,
  DAYS,
  isSingleCustomerConditionFeature,
  isSingleDateRangeConditionFeature,
  isSingleHourRangeConditionFeature,
  isSingleNoStrategyConditionFeature,
  isSinglePercentageConditionFeature,
  SingleConditionFeature,
} from "./types";

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
