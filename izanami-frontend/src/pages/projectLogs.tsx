import { useParams } from "react-router-dom";
import { FeatureSelector } from "../components/FeatureSelector";
import { AuditLog } from "../components/AuditLogs";

export function ProjectAudit() {
  const { tenant, project } = useParams();
  return (
    <AuditLog
      eventUrl={`/api/admin/tenants/${tenant}/projects/${project}/logs`}
      eventTypes={["FEATURE_CREATED", "FEATURE_UPDATED", "FEATURE_DELETED"]}
      customSearchFields={(onChange, clear, defaultValue) => {
        return (
          <label className="col-12 col-xl-6 mb-4">
            Features
            <div style={{ width: "100%" }}>
              <FeatureSelector
                defaultValue={defaultValue?.features}
                project={project}
                onChange={(features) => {
                  onChange({ features: features });
                }}
                creatable
              />
            </div>
          </label>
        );
      }}
    />
  );
}
