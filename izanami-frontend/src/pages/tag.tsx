import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { FeatureTable } from "../components/FeatureTable";
import queryClient from "../queryClient";
import {
  IzanamiContext,
  hasRightForProject,
  useAdmin,
} from "../securityContext";
import {
  createFeature,
  featureQueryKey,
  queryTag,
  queryTagFeatures,
  queryTenant,
  tagQueryKey,
  tenantQueryKey,
} from "../utils/queries";
import { TCompleteFeature, TLevel, TLightFeature } from "../utils/types";
import { FeatureForm } from "../components/FeatureForm";
import { useFormContext, Controller } from "react-hook-form";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { useParams } from "react-router-dom";
import { Loader } from "../components/Loader";
import { JSX } from "react";

export function Tag(prop: { tag: string; tenant: string }) {
  const { tag, tenant } = prop;
  const tagQuery = useQuery({
    queryKey: [tagQueryKey(tenant, tag)],

    queryFn: () => queryTag(tenant, tag),
  });
  const [creating, setCreating] = React.useState(false);
  const { user } = React.useContext(IzanamiContext);
  const admin = useAdmin();
  const creationRight =
    admin ||
    Object.values(user?.rights?.tenants?.[tenant]?.projects || {}).some(
      (right) => right.level === TLevel.Write || right.level === TLevel.Admin
    );

  const featureCreateMutation = useMutation({
    mutationFn: (data: { project: string; feature: any }) =>
      createFeature(tenant, data.project, data.feature),

    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: [featureQueryKey(tenant, tag)],
      });
    },
  });

  if (tagQuery.isError) {
    return <div>Failed to load tag</div>;
  } else if (tagQuery.isLoading) {
    return <Loader message="Loading..." />;
  } else if (tagQuery.isSuccess) {
    const { name, description } = tagQuery.data;
    return (
      <>
        <div className="d-flex align-items-center">
          <h1>
            Features with tag{" "}
            <span className="badge bg-warning text-dark btn-sm p-1">
              {name}
            </span>
          </h1>

          {creationRight && (
            <button
              className="btn btn-secondary btn-sm mb-2 ms-3"
              type="button"
              onClick={() => setCreating(true)}
            >
              Create new feature
            </button>
          )}
        </div>
        <span>{description}</span>
        {creating && (
          <FeatureForm
            defaultValue={{ tags: [tag] } as TLightFeature}
            additionalFields={() => <ProjectInput />}
            submit={(feature) =>
              featureCreateMutation
                .mutateAsync({
                  feature,
                  project: feature.project!,
                })
                .then(() => setCreating(false))
            }
            cancel={() => setCreating(false)}
          />
        )}
        <Features tagName={name} tenant={tenant} />
      </>
    );
  }
  return <></>;
}

function ProjectInput() {
  const { tenant } = useParams();
  const tenantQuery = useQuery({
    queryKey: [tenantQueryKey(tenant!)],

    queryFn: () => queryTenant(tenant!),
  });
  const { control } = useFormContext<TCompleteFeature>();

  if (tenantQuery.isError) {
    return <div>Failed to fetch project</div>;
  } else if (tenantQuery.data) {
    return (
      <label>
        Project
        <Controller
          name="project"
          control={control}
          rules={{ required: true }}
          render={({ field: { onChange, value }, fieldState }) => {
            return (
              <>
                <Select
                  value={{ label: value, value }}
                  onChange={(e) => {
                    onChange(e?.value);
                  }}
                  styles={customStyles}
                  options={tenantQuery.data.projects?.map((p) => ({
                    value: p.name,
                    label: p.name,
                  }))}
                />
                {fieldState.invalid && fieldState?.error?.type === "required" && (
                  <div className="error-message">
                    <i className="fa-solid fa-circle-exclamation" aria-hidden />
                    &nbsp;Project is required
                  </div>
                )}
              </>
            );
          }}
        />
      </label>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}

function Features(props: { tagName: string; tenant: string }): JSX.Element {
  const { tagName, tenant } = props;
  const queryKey = featureQueryKey(tenant, tagName);
  const featureQuery = useQuery({
    queryKey: [queryKey],

    queryFn: () => queryTagFeatures(tenant, tagName),
  });
  const { user } = React.useContext(IzanamiContext);

  if (featureQuery.isError) {
    return <div>Failed to load tag features</div>;
  } else if (featureQuery.isLoading) {
    return <Loader message="Loading features..." />;
  } else if (featureQuery.isSuccess) {
    const features = featureQuery.data;
    return (
      <FeatureTable
        features={features}
        refresh={() => queryClient.invalidateQueries({ queryKey: [queryKey] })}
        fields={["name", "tags", "project", "enabled", "details", "project"]}
        actions={(feature) => {
          if (
            hasRightForProject(user!, TLevel.Write, feature.project!, tenant)
          ) {
            return ["delete", "edit", "test", "overloads", "url"];
          } else {
            return ["test", "overloads", "url"];
          }
        }}
      />
    );
  }
  return <></>;
}
