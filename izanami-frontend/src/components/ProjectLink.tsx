import * as React from "react";
import { NavLink } from "react-router-dom";

export function ProjectLink(props: {
  tenant: string;
  project: string;
  className?: string;
}) {
  const { tenant, project, className } = props;
  return (
    <NavLink
      className={() => className ?? ""}
      to={`/tenants/${tenant}/projects/${project}`}
    >
      <i className="fas fa-building" aria-hidden></i>&nbsp;
      {project}
    </NavLink>
  );
}
