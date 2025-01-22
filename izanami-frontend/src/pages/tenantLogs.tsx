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
        <div>
          <EntitySelector tenant={tenant!} onChange={onChange} clear={clear} />
        </div>
      )}
    />
  );
}

const loadEntities = (tenant: string) => (input: string) => {
  return searchEntitiesByTenant(tenant, input, ["feature", "project"]).then(
    (resp) =>
      resp.map(({ name, type, ...rest }) => {
        let label = <span>name</span>;
        if (type === "feature") {
          label = (
            <span>
              <i className="fas fa-rocket" />
              &nbsp;name (<i className="fas fa-building" />{" "}
              {rest.path?.[0]?.name})
            </span>
          );
        } else if (type === "project") {
          label = (
            <span>
              <i className="fas fa-building" />
              &nbsp;name
            </span>
          );
        }

        return { label: label, value: name };
      })
  );
};

function EntitySelector(props: {
  tenant: string;
  onChange: (v: any) => void;
  clear: () => void;
}) {
  const { register, control } = useFormContext();

  return (
    <Controller
      name="types"
      control={control}
      render={({ field: { onChange, value } }) => (
        <AsyncCreatableSelect
          loadOptions={loadEntities(props.tenant)}
          styles={customStyles}
          isMulti
          onChange={(selected) => onChange(selected?.map(({ value }) => value))}
        />
      )}
    />
  );
}
/*
value={featureEventTypeOptions.filter((base) =>
            value.includes(base.value)
          )}
*/
