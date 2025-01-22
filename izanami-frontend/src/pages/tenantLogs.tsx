import { useParams } from "react-router-dom";
import { AuditLog } from "../components/AuditLogs";
import { Controller, useFormContext } from "react-hook-form";
import AsyncCreatableSelect from "react-select/async-creatable";
import { searchEntitiesByTenant } from "../utils/queries";
import { customStyles } from "../styles/reactSelect";

export function TenantAudit() {
  const { tenant } = useParams();
  return (
    <AuditLog
      eventUrl={`/api/admin/tenants/${tenant!}/logs`}
      customSearchFields={(onChange, clear) => (
        <div className="col-12 col-xl-6 mb-4">
          <label>Entities</label>
          <EntitySelector
            tenant={tenant!}
            onChange={(values) => onChange(values)}
            clear={clear}
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
}) {
  return (
    <AsyncCreatableSelect
      loadOptions={loadEntities(props.tenant)}
      styles={customStyles}
      isMulti
      onChange={(selected) => {
        const value = selected.reduce((acc: any, { type, value }) => {
          if (!acc[type]) {
            acc[type] = [];
          }
          acc[type].push(value);

          return acc;
        }, {});
        props?.onChange(value);
      }}
    />
  );
}
