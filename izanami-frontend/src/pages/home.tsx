import { Form, constraints, format, type } from "@maif/react-forms";
import * as React from "react";
import { useMutation, useQuery } from "react-query";
import { Navigate, NavLink, useNavigate } from "react-router-dom";
import { createTenant, MutationNames, queryTenants } from "../utils/queries";
import queryClient from "../queryClient";
import { TenantInCreationType } from "../utils/types";
import { IzanamiContext, useAdmin } from "../securityContext";
import { TENANT_NAME_REGEXP } from "../utils/patterns";

export function HomePage() {
  const tenantQuery = useQuery(MutationNames.TENANTS, () => queryTenants());
  const tenantCreationMutation = useMutation(
    (data: TenantInCreationType) => createTenant(data),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(MutationNames.TENANTS);
      },
    }
  );

  const { user } = React.useContext(IzanamiContext);

  const [creating, setCreating] = React.useState<boolean>(false);
  const navigate = useNavigate();

  const isAdmin = useAdmin();
  if (tenantQuery.isSuccess) {
    if (!isAdmin && tenantQuery.data.length === 1) {
      return <Navigate to={`/tenants/${tenantQuery.data[0].name}`} />;
    }
    const empty = !creating && tenantQuery.data.length === 0;
    return (
      <>
        <div className="d-flex align-items-center">
          <h1>Welcome to Izanami</h1>
          {isAdmin && !empty && !creating && (
            <button
              type="button"
              className="btn btn-primary btn-sm mb-2 ms-3"
              onClick={() => setCreating(true)}
            >
              Create new tenant
            </button>
          )}
        </div>
        <div className="container mt-4">
          {empty && (
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                justifyContent: "center",
                alignItems: "center",
                marginTop: "10%",
              }}
            >
              {user?.admin ? (
                <>
                  <div style={{ fontSize: "21px", marginBottom: "24px" }}>
                    Izanami doesn't have any tenant yet.
                  </div>
                  <button
                    type="button"
                    className="btn btn-primary btn-lg"
                    onClick={() => setCreating(true)}
                  >
                    Create new tenant
                  </button>
                </>
              ) : (
                <div style={{ fontSize: "21px" }}>
                  You don't have rights on any tenant.
                </div>
              )}
            </div>
          )}
          <div className="row row-cols-1 row-cols-sm-2 row-cols-md-3 g-3 nav">
            {creating && (
              <div className="col" key="new-tenant">
                <div className="card shadow-sm anim__popUp">
                  <div className="card-body">
                    <Form
                      schema={tenantCreationSchema}
                      onSubmit={(tenant: TenantInCreationType) => {
                        tenantCreationMutation
                          .mutateAsync(tenant)
                          .then(() => setCreating(false))
                          .then(() => navigate(`/tenants/${tenant.name}`));
                      }}
                      footer={({ valid }: { valid: () => void }) => {
                        return (
                          <div className="d-flex justify-content-end pt-3">
                            <button
                              type="button"
                              className="btn btn-danger"
                              onClick={() => setCreating(false)}
                            >
                              Cancel
                            </button>
                            <button
                              className="btn btn-success ms-2"
                              onClick={valid}
                            >
                              Save
                            </button>
                          </div>
                        );
                      }}
                    />
                  </div>
                </div>
              </div>
            )}
            {tenantQuery.data.map(({ name, description }) => (
              <div className="col" key={name}>
                <div className="card shadow-sm card-tenant">
                  <div className="card-body position-relative">
                    <h2>
                      <NavLink
                        className={() => "card-title stretched-link"}
                        to={`/tenants/${name}`}
                      >
                        {name}
                      </NavLink>
                    </h2>
                    {description || ""}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </>
    );
  } else if (tenantQuery.isLoading) {
    return <div>Loading...</div>;
  } else {
    return <div>Error while fetching tenants</div>;
  }
}

const tenantCreationSchema = {
  name: {
    type: type.string,
    label: "Tenant name",
    props: {
      autoFocus: true,
    },
    constraints: [
      constraints.required("Tenant name is required"),
      constraints.matches(
        TENANT_NAME_REGEXP,
        `Tenant name must match ${TENANT_NAME_REGEXP} regex`
      ),
    ],
  },
  description: {
    type: type.string,
    format: format.textarea,
    label: "Description",
  },
};
