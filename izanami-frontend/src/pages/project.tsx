import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { FeatureTable } from "../components/FeatureTable";
import {
  createFeature,
  projectContextKey,
  projectQueryKey,
  queryProject,
} from "../utils/queries";
import { TProjectLevel } from "../utils/types";
import { useProjectRight } from "../securityContext";
import queryClient from "../queryClient";
import { FeatureForm } from "../components/FeatureForm";
import { useSearchParams } from "react-router-dom";

export function Project({
  project,
  tenant,
}: {
  project: string;
  tenant: string;
}) {
  const [searchParams] = useSearchParams();
  const selectedSearchRow = searchParams.get("filter") ?? "";
  const [error, setError] = useState<string>("");
  const clearError = () => setError("");
  const queryKey = projectQueryKey(tenant, project);

  const projectQuery = useQuery({
    queryKey: [queryKey],

    queryFn: () => queryProject(tenant, project),
  });
  const [creating, setCreating] = useState(false);

  const hasCreationRight = useProjectRight(
    tenant,
    project,
    TProjectLevel.Write
  );
  const hasUpdateRight = useProjectRight(tenant, project, TProjectLevel.Update);

  const featureCreateMutation = useMutation({
    mutationFn: (data: { project: string; feature: any }) =>
      createFeature(tenant, data.project, data.feature),

    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: [projectQueryKey(tenant, project)],
      });
    },
  });

  if (projectQuery.isLoading) {
    return (
      <div className="spinner-border" role="status">
        <span className="visually-hidden">Loading...</span>
      </div>
    );
  } else if (projectQuery.isSuccess) {
    const projectData = projectQuery.data;
    return (
      <>
        <div className="d-flex flex-column flex-sm-row">
          <div className="d-flex align-items-center">
            <h1>Features</h1>
          </div>
          {hasCreationRight && !creating && projectData.features.length > 0 && (
            <button
              className="btn btn-secondary btn-sm mt-sm-2 ms-sm-2 align-self-start align-self-sm-baseline"
              type="button"
              onClick={() => setCreating(true)}
            >
              Create new feature
            </button>
          )}
        </div>
        {/* TODO centralize this */}
        {error && (
          <div
            className="alert alert-danger alert-dismissible fade show"
            role="alert"
          >
            {error}
            <button
              type="button"
              className="btn-close"
              data-bs-dismiss="alert"
              aria-label="Close"
              onClick={clearError}
            ></button>
          </div>
        )}
        {creating && (
          <div className="sub_container anim__rightToLeft">
            <h4
              style={{
                marginBottom: "0px",
                paddingBottom: ".5rem",
                paddingTop: ".5rem",
                paddingRight: ".5rem",
                display: "inline-block",
              }}
            >
              Create a new feature
            </h4>
            <br />
            <FeatureForm
              submit={(feature) => {
                return featureCreateMutation
                  .mutateAsync({
                    feature,
                    project: project,
                  })
                  .then(() => setCreating(false));
              }}
              cancel={() => setCreating(false)}
              displayId={true}
            />
          </div>
        )}
        {projectData.features.length === 0 ? (
          !creating && (
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                justifyContent: "center",
                alignItems: "center",
                marginTop: "10%",
              }}
            >
              <div className="item-text">
                There is no feature for this project.
              </div>
              <button
                type="button"
                className="btn btn-primary btn-lg"
                onClick={() => setCreating(true)}
              >
                Create new feature
              </button>
            </div>
          )
        ) : (
          <FeatureTable
            features={projectData.features}
            selectedSearchRow={selectedSearchRow}
            fields={[
              "id",
              "name",
              "enabled",
              "details",
              "tags",
              "overloadCount",
            ]}
            actions={() =>
              hasCreationRight
                ? [
                    "edit",
                    "delete",
                    "test",
                    "duplicate",
                    "transfer",
                    "overloads",
                    "url",
                  ]
                : hasUpdateRight
                ? ["edit", "test", "overloads", "url"]
                : ["test", "overloads", "url"]
            }
            refresh={() =>
              Promise.all([
                queryClient.invalidateQueries({
                  queryKey: [projectQueryKey(tenant, project)],
                }),
                queryClient.invalidateQueries({
                  queryKey: [projectContextKey(tenant, project)],
                }),
              ])
            }
          />
        )}
      </>
    );
  }
  return <></>;
}
