import { useParams } from "react-router-dom";
import { AuditLog } from "../components/AuditLogs";
import { Controller, useFormContext } from "react-hook-form";
import AsyncCreatableSelect from "react-select/async-creatable";
import {
  fetchFeature,
  queryProject,
  queryProjectById,
  searchEntitiesByTenant,
} from "../utils/queries";
import { customStyles } from "../styles/reactSelect";
import { useEffect, useState } from "react";
import { Loader } from "../components/Loader";

export function TenantAudit() {
  const { tenant } = useParams();
  return (
    <AuditLog
      eventUrl={`/api/admin/tenants/${tenant!}/logs`}
      customSearchFields={(onChange, clear, defaultValue) => (
        <div className="col-12 col-xl-6 mb-4">
          <label>Entities</label>
          <EntitySelector
            tenant={tenant!}
            onChange={(values) => onChange(values)}
            clear={clear}
            defaultValue={defaultValue}
          />
        </div>
      )}
    />
  );
}

const loadEntities = (tenant: string) => (input: string) => {
  return searchEntitiesByTenant(tenant, input, ["feature", "project"]).then(
    (resp) =>
      resp.map(({ name, type, id, ...rest }) => {
        let label = <span>name</span>;
        if (type === "feature") {
          label = (
            <span>
              <i className="fas fa-rocket" />
              &nbsp;{name} (<i className="fas fa-building" />{" "}
              {rest.path?.[0]?.name})
            </span>
          );
        } else if (type === "project") {
          label = (
            <span>
              <i className="fas fa-building" />
              &nbsp;{name}
            </span>
          );
        }

        return { label: label, value: id, type: type };
      })
  );
};

function EntitySelector(props: {
  tenant: string;
  onChange: (v: any) => void;
  clear: () => void;
  defaultValue?: { features?: string[]; projects?: string[] };
}) {
  const [ready, setReady] = useState<
    | {
        ready: true;
        data: { label: string; value: string }[];
      }
    | { ready: false }
  >({ ready: false });
  useEffect(() => {
    const featureToFetch = props?.defaultValue?.features || [];
    const projectTofetch = props?.defaultValue?.projects || [];
    if (featureToFetch.length === 0 && projectTofetch.length === 0) {
      setReady({ ready: true, data: [] });
    }

    let promises = [];
    if (featureToFetch) {
      promises.push(
        Promise.all(featureToFetch.map((f) => fetchFeature(props.tenant, f)))
      );
    } else {
      promises.push(Promise.resolve([]));
    }

    if (projectTofetch) {
      promises.push(
        Promise.all(
          projectTofetch.map((p) => queryProjectById(props.tenant, p))
        )
      );
    } else {
      promises.push([]);
    }

    Promise.all(promises)
      .then(([features, projects]) => {
        return features
          .filter((f) => Boolean(f))
          .map((f: any) => ({
            label: (
              <span>
                <i className="fas fa-rocket" />
                &nbsp;{f.name} (<i className="fas fa-building" /> {f.project})
              </span>
            ),
            value: f.id,
          }))
          .concat(
            projects.map((p: any) => ({
              label: (
                <span>
                  <i className="fas fa-building" />
                  &nbsp;{p.name}
                </span>
              ),
              value: p.id,
            }))
          );
      })
      .then((options) => setReady({ ready: true, data: options }));
  }, []);
  return !ready.ready ? (
    <Loader message="Loading..." />
  ) : (
    <AsyncCreatableSelect
      loadOptions={loadEntities(props.tenant)}
      styles={customStyles}
      defaultValue={ready.data}
      isMulti
      onChange={(selected) => {
        const value = selected.reduce((acc: any, { type, value }) => {
          let typeNameForQuery: string = type;
          switch (type) {
            case "feature":
              typeNameForQuery = "features";
              break;
            case "project":
              typeNameForQuery = "projects";
              break;
          }
          if (!acc[typeNameForQuery]) {
            acc[typeNameForQuery] = [];
          }
          acc[typeNameForQuery].push(value);

          return acc;
        }, {});
        props?.onChange(value);
      }}
    />
  );
}
