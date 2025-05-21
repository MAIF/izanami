import { useParams } from "react-router-dom";
import { AuditLog } from "../components/AuditLogs";
import AsyncCreatableSelect from "react-select/async-creatable";
import {
  fetchFeature,
  queryProjectById,
  searchEntitiesByTenant,
} from "../utils/queries";
import { customStyles } from "../styles/reactSelect";
import { JSX, useEffect, useState } from "react";
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
              <i className="fas fa-rocket" aria-hidden />
              &nbsp;{name} (<i className="fas fa-building" aria-hidden />{" "}
              {rest.path?.[0]?.name})
            </span>
          );
        } else if (type === "project") {
          label = (
            <span>
              <i className="fas fa-building" aria-hidden />
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
  defaultValue?: {
    features?: string[];
    projects?: string[];
    unknownIds?: string[];
  };
}) {
  const [ready, setReady] = useState<
    | {
        ready: true;
        data: { label: string | JSX.Element; value: string }[];
      }
    | { ready: false }
  >({ ready: false });
  useEffect(() => {
    const featureToFetch = props?.defaultValue?.features || [];
    const projectTofetch = props?.defaultValue?.projects || [];
    const unknwown = props?.defaultValue?.unknownIds || [];
    const unknownOptions = unknwown.map((p: any) => ({
      label: (
        <span>
          <i className="fas fa-question" aria-hidden />
          &nbsp;{p}
        </span>
      ),
      value: p,
      type: "unknownIds",
    }));
    if (featureToFetch.length === 0 && projectTofetch.length === 0) {
      setReady({ ready: true, data: unknownOptions });
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
        return (features as any[])
          .filter((f) => Boolean(f))
          .map((f: any) => ({
            label: (
              <span>
                <i className="fas fa-rocket" aria-hidden />
                &nbsp;{f.name} (<i className="fas fa-building" aria-hidden />{" "}
                {f.project})
              </span>
            ),
            value: f.id,
            type: "feature",
          }))
          .concat(
            projects.map((p: any) => ({
              label: (
                <span>
                  <i className="fas fa-building" aria-hidden />
                  &nbsp;{p.name}
                </span>
              ),
              value: p.id,
              type: "project",
            }))
          )
          .concat(unknownOptions);
      })
      .then((options) => {
        setReady({ ready: true, data: options });
      });
  }, []);

  return !ready.ready ? (
    <Loader message="Loading..." />
  ) : (
    <AsyncCreatableSelect
      aria-label="Select entity to search for"
      loadOptions={loadEntities(props.tenant) as any}
      styles={customStyles}
      defaultValue={ready.data}
      isMulti
      onChange={(selected) => {
        const value = selected.reduce((acc: any, { type, value }: any) => {
          let typeNameForQuery: string = type;
          switch (type) {
            case "feature":
              typeNameForQuery = "features";
              break;
            case "project":
              typeNameForQuery = "projects";
              break;
            default:
              typeNameForQuery = "unknownIds";
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
