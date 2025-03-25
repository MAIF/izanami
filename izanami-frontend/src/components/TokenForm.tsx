import { addYears, format, parse } from "date-fns";
import * as React from "react";
import { Controller, FormProvider, useForm } from "react-hook-form";
import { TOKEN_NAME_REGEXP } from "../utils/patterns";
import { TimeZoneSelect } from "./TimeZoneSelect";
import { ErrorDisplay } from "./FeatureForm";
import { MutationNames, queryTenants } from "../utils/queries";
import { useQuery } from "@tanstack/react-query";
import { Loader } from "./Loader";
import Select from "react-select";

import {
  POSSIBLE_TOKEN_RIGHTS,
  TokenTenantRight,
  TokenTenantRightsArray,
} from "../utils/types";
import { customStyles } from "../styles/reactSelect";
import { hasRightForTenant, IzanamiContext } from "../securityContext";
import { Tooltip } from "./Tooltip";
import { DEFAULT_TIMEZONE } from "../utils/datetimeUtils";

export type PesonnalTokenFormType =
  | LimitedRightsPesonnalTokenFormType
  | AllRightsPesonnalTokenFormType;

export type LimitedRightsPesonnalTokenFormType = {
  name: string;
  expiresAt: Date;
  expirationTimezone: string;
  rights: TokenTenantRightsArray;
  allRights: false;
};

export type AllRightsPesonnalTokenFormType = {
  name: string;
  expiresAt: Date;
  expirationTimezone: string;
  allRights: true;
};

const DEFAULT_RIGHTS_VALUE: () => TokenTenantRightsArray = () => [[null, []]];

export function TokenForm(props: {
  onSubmit: (data: PesonnalTokenFormType) => void;
  onCancel: () => void;
  defaultValue?: PesonnalTokenFormType;
}) {
  const methods = useForm<PesonnalTokenFormType>({
    defaultValues: props.defaultValue ?? {
      expiresAt: addYears(new Date(), 1),
      rights: DEFAULT_RIGHTS_VALUE(),
      allRights: false,
    },
  });
  const {
    handleSubmit,
    control,
    formState: { errors },
    register,
    watch,
    setValue,
    unregister,
  } = methods;

  const allRights = watch("allRights");
  watch("rights");

  return (
    <FormProvider {...methods}>
      <form
        className="d-flex flex-column col-12 col-lg-8"
        onSubmit={handleSubmit((data) => {
          props.onSubmit(data);
        })}
      >
        <label>
          Name*
          <Tooltip id="token-name">
            Token name, use something meaningfull, it can be modified later
            without impacts.
          </Tooltip>
          <input
            autoFocus={true}
            className="form-control"
            type="text"
            {...register("name", {
              required: "Name is required",
              pattern: {
                value: TOKEN_NAME_REGEXP,
                message: `Token name must match ${TOKEN_NAME_REGEXP}`,
              },
            })}
          />
          <ErrorDisplay error={errors.name} />
        </label>
        <label className="mt-3">
          All rights
          <Tooltip id="token-all-rights">
            If this is checked, token will have all user rights.
            <br />
            If this is unchecked, you'll have to specify what token is allowed
            to do.
          </Tooltip>
          <Controller
            name="allRights"
            control={control}
            render={({ field: { onChange, value } }) => {
              return (
                <input
                  type="checkbox"
                  className="izanami-checkbox"
                  checked={value}
                  onChange={(v) => {
                    if (v?.target?.checked) {
                      unregister("rights");
                    } else {
                      setValue("rights", DEFAULT_RIGHTS_VALUE());
                    }
                    onChange(v);
                  }}
                />
              );
            }}
          />
        </label>
        {!allRights && (
          <div className="mt-3">
            <Controller
              name="rights"
              control={control}
              render={({ field: { onChange, value } }) => {
                return (
                  <TenantRightSelector
                    value={value}
                    onChange={(v) => onChange(v)}
                  />
                );
              }}
            />
          </div>
        )}
        <label className="mt-3">
          Expiration
          <Tooltip id="token-expiration">
            Expiration date for token.
            <br />
            Token won't be usable after this date.
          </Tooltip>
          <Controller
            name="expiresAt"
            control={control}
            render={({ field: { onChange, value } }) => {
              return (
                <input
                  className="form-control"
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
        <label className="mt-3">
          Timezone
          <Tooltip id="token-expiration-timezone">
            Timezone for token expiration.
          </Tooltip>
          <Controller
            name="expirationTimezone"
            defaultValue={DEFAULT_TIMEZONE}
            control={control}
            render={({ field: { onChange, value } }) => (
              <TimeZoneSelect onChange={onChange} value={value} />
            )}
          />
        </label>
        <div className="d-flex justify-content-end">
          <button
            type="button"
            className="btn btn-danger-light m-2"
            onClick={() => props.onCancel()}
          >
            Cancel
          </button>
          <button className="btn btn-primary m-2">Save</button>
        </div>
      </form>
    </FormProvider>
  );
}

function TenantRightSelector(props: {
  value: TokenTenantRightsArray;
  onChange: (r: TokenTenantRightsArray) => void;
}) {
  const tenantQuery = useQuery({
    queryKey: [MutationNames.TENANTS],
    queryFn: () => queryTenants(),
  });
  const { value, onChange } = props;
  const { user } = React.useContext(IzanamiContext);

  if (tenantQuery.data) {
    return (
      <>
        {value.map(([tenant, rs], index) => {
          return (
            <div className="d-flex w-100" key={`right-${tenant}`}>
              <div
                className="flex-grow-1"
                style={{
                  marginRight: "12px",
                }}
              >
                <UnitTenantRightSelector
                  key={`rights-${tenant}`}
                  options={tenantQuery.data
                    .map((t) => t.name)
                    .filter(
                      (t) =>
                        hasRightForTenant(user!, t, "Admin") &&
                        !value.find(([v]) => t == v)
                    )}
                  tenant={tenant}
                  rights={rs}
                  onChange={(tenant, rights) => {
                    onChange([
                      ...value.slice(0, index),
                      [tenant, rights],
                      ...value.slice(index + 1),
                    ]);
                  }}
                />
              </div>
              <div className="d-flex justify-content-center align-items-end">
                <button
                  className="btn btn-danger"
                  type="button"
                  onClick={() =>
                    onChange([
                      ...value.slice(0, index),
                      ...value.slice(index + 1),
                    ])
                  }
                >
                  Delete
                </button>
              </div>
            </div>
          );
        })}
        {value.length < tenantQuery.data.length && (
          <div className="d-flex justify-content-end mt-2">
            <button
              className="btn btn-secondary"
              type="button"
              onClick={() => onChange([...value, [null, []]])}
            >
              Add rights
            </button>
          </div>
        )}
      </>
    );
  } else if (tenantQuery.error) {
    return <div className="error-message">Failed to fetch tenants</div>;
  } else {
    return <Loader message="Loading tenants" />;
  }
}

function UnitTenantRightSelector(props: {
  options: string[];
  tenant: string | null;
  rights: TokenTenantRight[];
  onChange: (tenant: string | null, rights: TokenTenantRight[]) => void;
}) {
  const { tenant, rights } = props;
  return (
    <div className="row">
      <label className="col-6 col-md-3">
        Tenant
        <Select
          styles={customStyles}
          value={tenant ? { value: tenant, label: tenant } : null}
          options={props.options
            .filter((v) => v !== tenant)
            .map((v) => ({ label: v, value: v }))}
          onChange={(v) => props?.onChange(v?.value ?? null, [])}
        />
      </label>
      <label className="col-6 col-md-9">
        Rights
        <Select
          styles={customStyles}
          value={rights?.map((r) => ({ value: r, label: r })) ?? []}
          options={POSSIBLE_TOKEN_RIGHTS.filter(
            (v) => !props.rights?.includes(v)
          ).map((v) => ({ label: v, value: v }))}
          onChange={(v) => {
            props?.onChange(
              props.tenant,
              v?.map((v) => v?.value)
            );
          }}
          isMulti
        />
      </label>
    </div>
  );
}
