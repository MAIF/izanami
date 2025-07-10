import * as React from "react";
import { GenericTable } from "./GenericTable";
import { TLevel, TSingleRightForTenantUser } from "../utils/types";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { isRightAbove, IzanamiContext } from "../securityContext";

export function RightTable(props: {
  data: TSingleRightForTenantUser[];
  onRightChange: (
    datum: TSingleRightForTenantUser,
    level?: TLevel
  ) => Promise<void>;
  canEdit: boolean;
}) {
  const { askConfirmation } = React.useContext(IzanamiContext);
  const { data, onRightChange, canEdit } = props;
  return (
    <>
      <GenericTable
        data={data}
        customRowActions={{
          edit: {
            icon: (
              <>
                <i className="bi bi-pencil-square" aria-hidden></i> Edit
              </>
            ),
            hasRight: (user, datum) =>
              canEdit && datum.username !== user?.username,
            customForm: (data: TSingleRightForTenantUser, cancel) => (
              <RightLevelModification
                submit={(level: TLevel) => {
                  if (!Object.values(TLevel).includes(level)) {
                    return onRightChange(data, undefined);
                  }
                  return onRightChange(data, level);
                }}
                cancel={cancel}
                level={data.right}
                title={`Update right level for ${data.username}`}
              />
            ),
          },
          delete: {
            icon: (
              <>
                <i className="bi bi-trash" aria-hidden></i> Delete
              </>
            ),
            hasRight: (user, datum) =>
              canEdit && datum.username !== user?.username,
            action: (d) =>
              askConfirmation(
                `Are you sure you want to remove ${d.username} rights on this ?`,
                () => onRightChange(d, undefined)
              ),
          },
        }}
        idAccessor={(data) => data.username}
        columns={[
          {
            accessorKey: "username",
            header: () => "Username",
            size: 25,
          },
          {
            accessorKey: "admin",
            header: () => "Admin",
            meta: {
              valueType: "boolean",
            },
            size: 20,
          },
          {
            accessorKey: "tenantAdmin",
            header: () => "Tenant admin",
            meta: {
              valueType: "boolean",
            },
            size: 20,
          },
          {
            accessorKey: "userType",
            header: () => "User type",
            meta: {
              valueType: "discrete",
            },
            size: 15,
          },
          {
            accessorKey: "right",
            header: () => "Right level",
            meta: {
              valueType: "rights",
            },
            cell: (col) => {
              const user = col.row.original;
              const maybeDefaultProjectRight = user.defaultRight;

              if (maybeDefaultProjectRight && user.right) {
                const isDefaultRightAboveRight = isRightAbove(
                  maybeDefaultProjectRight,
                  user.right
                );
                return isDefaultRightAboveRight
                  ? `${maybeDefaultProjectRight} (default right)`
                  : user.right;
              } else if (user.right) {
                return user.right;
              } else if (maybeDefaultProjectRight) {
                return `${maybeDefaultProjectRight} (default right)`;
              } else {
                return <></>;
              }
            },
            size: 25,
            filterFn: (data, columndId, filterValue) => {
              const right = data.getValue(columndId);
              if (filterValue === TLevel.Admin) {
                return right === TLevel.Admin || right === undefined;
              }
              return data && right === filterValue;
            },
          },
        ]}
      />
    </>
  );
}

function RightLevelModification(props: {
  title: string;
  level?: TLevel;
  cancel: () => void;
  submit: (obj: any) => Promise<any>;
}) {
  const { level, cancel, submit, title } = props;
  const [newLevel, setNewLevel] = React.useState(level);
  return (
    <div className="sub_container">
      <h3>{title}</h3>
      <Select
        menuPlacement="top"
        styles={customStyles}
        defaultValue={{
          label: level ?? "No rights",
          value: newLevel ?? "No rights",
        }}
        options={["No rights", ...Object.values(TLevel)].map((v) => ({
          label: v,
          value: v,
        }))}
        onChange={(e) => {
          setNewLevel(e?.value as any);
        }}
      />
      <div className="d-flex justify-content-end">
        <button
          type="button"
          className="btn btn-danger m-2"
          onClick={() => cancel()}
        >
          Cancel
        </button>
        <button
          className="btn btn-primary m-2"
          onClick={() => submit(newLevel)}
        >
          Save
        </button>
      </div>
    </div>
  );
}
