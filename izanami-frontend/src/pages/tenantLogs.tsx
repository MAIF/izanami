import { useParams } from "react-router-dom";
import { AuditLog } from "../components/AuditLogs";

export function TenantAudit() {
  const { tenant } = useParams();
  return <AuditLog eventUrl={`/api/admin/tenants/${tenant!}/logs`} />;
}
