import React from "react";
import { useMatches } from "react-router-dom";

export function Topbar() {
  return (
    <>
      <Breadcrumbs />
    </>
  );
}

function Breadcrumbs() {
  let matches = useMatches();
  let crumbs = matches
    .filter((match: any) => Boolean(match.handle?.crumb))
    .map((match: any) => {
      return match.handle.crumb(match.params);
    });
  
    if (crumbs.length === 1) {return <nav className="mb-5" />}
  return (
    <nav aria-label="breadcrumb" className="breadcrumb nav flex-column">
      <ol className="breadcrumb">
        {crumbs.map((crumb, index, arr) => (
          <li
            className={`breadcrumb-item ${
              index === arr.length - 1 ? "active" : ""
            }`}
            key={index}
          >
            {crumb}
          </li>
        ))}
      </ol>
    </nav>
  );
}
