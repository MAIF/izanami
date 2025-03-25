import {
  Column,
  ColumnDef,
  ColumnFiltersState,
  flexRender,
  getCoreRowModel,
  getFacetedMinMaxValues,
  getFacetedRowModel,
  getFacetedUniqueValues,
  getFilteredRowModel,
  getSortedRowModel,
  Row,
  RowData,
  SortingState,
  Table,
  useReactTable,
} from "@tanstack/react-table";
import React, { useContext, useEffect, useState } from "react";
import Select from "react-select";
import { IzanamiContext } from "../securityContext";
import { customStyles } from "../styles/reactSelect";
import { TLevel, TUser } from "../utils/types";

interface TProps<T extends RowData> {
  columns: ColumnDef<T>[];
  data: T[];
  selectableRows?: boolean;
  onRowSelectionChange?: (data: T[]) => void;
  idAccessor: (t: T) => string;
  defaultSort?: string;
  customRowActions?: {
    [x: string]: TCustomAction<T>;
  };
  isRowSelectable?: (feature: T) => boolean;
  filters?: ColumnFiltersState | undefined;
}

export type TCustomAction<T> =
  | TCustomActionWithoutForm<T>
  | TFormCustomAction<T>;

export interface TCustomActionParent<T> {
  icon: React.ReactNode | ((data: any) => React.ReactNode);
  hasRight?: (user: TUser, object: T) => boolean;
}

export interface TCustomActionWithoutForm<T> extends TCustomActionParent<T> {
  action: (data: any) => void;
}

export interface TFormCustomActionParent<T> extends TCustomActionParent<T> {
  keepOpenOnSubmit?: boolean;
}

export interface TFormCustomAction<T> extends TFormCustomActionParent<T> {
  customForm: (data: any, cancel: () => void) => JSX.Element;
}

function isCustomActionWithoutForm<T>(
  action: TCustomAction<T>
): action is TCustomActionWithoutForm<T> {
  return (action as TCustomActionWithoutForm<T>).action !== undefined;
}

function isFormCustomAction<T>(
  action: TCustomAction<T>
): action is TFormCustomAction<T> {
  return (action as TFormCustomAction<T>).customForm !== undefined;
}

function renderActionForm<T>(
  action: TCustomAction<T> | undefined,
  customFormRender: (action: TFormCustomAction<T>) => React.ReactNode
): React.ReactNode {
  if (!action) {
    return <></>;
  }
  if (isFormCustomAction(action)) {
    return customFormRender(action);
  } else {
    return <></>;
  }
}

export function GenericTable<T extends RowData>(props: TProps<T>) {
  const {
    data,
    columns,
    customRowActions,
    idAccessor,
    defaultSort,
    selectableRows,
    onRowSelectionChange,
    isRowSelectable,
    filters,
  } = props;
  const [sorting, setSorting] = React.useState<SortingState>(
    defaultSort
      ? [
          {
            id: defaultSort,
            desc: false,
          },
        ]
      : []
  );
  const [rowSelection, setRowSelection] = React.useState({});
  const [columnFilters, setColumnFilters] = React.useState<ColumnFiltersState>(
    filters || []
  );
  useEffect(() => {
    setColumnFilters(filters!);
  }, [filters]);

  const hasActionColumn =
    customRowActions && Object.keys(customRowActions).length > 0;

  const [activeCustomAction, setActiveCustomAction] = useState(new Map());

  const izanamiCtx = useContext(IzanamiContext);
  const user = izanamiCtx.user!;

  function extractAction(row: Row<any>) {
    return customRowActions?.[
      activeCustomAction.get(idAccessor(row.original!))
    ];
  }

  const actionColumn: ColumnDef<T> = {
    id: "actions",
    enableSorting: false,
    minSize: 47,
    header: () => (
      <div className="d-flex flex-column align-items-end">Actions</div>
    ),
    cell: function (props: { row: Row<any> }) {
      const element: T = props.row.original!;
      const actionList = [
        ...Object.entries(customRowActions || {})
          .filter(([, value]) => {
            return value.hasRight ? value.hasRight(user, element) : true;
          })
          .map(([key, action]) => {
            return (
              <li
                key={`${props.row.id}-${key}`}
                onClick={(e) => {
                  e.stopPropagation();
                  if (isCustomActionWithoutForm(action)) {
                    action.action(props.row.original);
                  } else {
                    setActiveCustomAction((current) => {
                      const mapKey = idAccessor(element);
                      current.set(mapKey, key);
                      return new Map(current);
                    });
                  }
                }}
              >
                <a
                  className="dropdown-item"
                  href="#"
                  onClick={(e) => e.preventDefault()}
                >
                  {typeof action.icon === "function"
                    ? action.icon(props.row.original)
                    : action.icon}
                </a>
              </li>
            );
          }),
      ].filter(Boolean);
      return (
        <>
          {actionList.length > 0 && (
            <div className="dropdown">
              <button
                className="btn btn-sm btn-secondary dropdown-toggle"
                type="button"
                data-bs-toggle="dropdown"
                aria-expanded="false"
                aria-label="actions"
              >
                <i className="bi bi-three-dots-vertical"></i>
              </button>
              <ul className="dropdown-menu">{actionList}</ul>
            </div>
          )}
        </>
      );
    },
  };

  const completeColumns: ColumnDef<T>[] = [...columns];
  if (hasActionColumn) {
    completeColumns.push(actionColumn);
  }
  if (selectableRows) {
    completeColumns.unshift({
      id: "select",
      header: ({ table }) => (
        <div
          className="d-flex justify-content-center align-items-center h-100"
          style={{ width: "16px", position: "relative" }}
        >
          <IndeterminateCheckbox
            {...{
              checked: table.getIsAllRowsSelected(),
              indeterminate: table.getIsSomeRowsSelected(),
              onChange: table.getToggleAllRowsSelectedHandler(),
              id: "header_checkbox",
              label: "select all rows",
            }}
          />
        </div>
      ),
      cell: ({ row }) => {
        if (isRowSelectable === undefined || !isRowSelectable(row.original!)) {
          return <></>;
        }
        return (
          <div
            style={{
              display: "flex",
              justifyContent: "center",
              alignItems: "center",
            }}
          >
            <div
              style={{ width: "16px", height: "16px", position: "relative" }}
            >
              <IndeterminateCheckbox
                {...{
                  checked: row.getIsSelected(),
                  disabled: !row.getCanSelect(),
                  indeterminate: row.getIsSomeSelected(),
                  onChange: row.getToggleSelectedHandler(),
                  id: `checkbox-${row.id}`,
                  label: `select ${row.id}`,
                }}
              />
            </div>
          </div>
        );
      },
    });
  }

  const table = useReactTable({
    data: data, // FIXME TS
    columns: completeColumns, // FIXME TS
    state: {
      sorting,
      columnFilters,
      rowSelection,
    },
    enableRowSelection: Boolean(selectableRows),
    onRowSelectionChange: setRowSelection,
    getCoreRowModel: getCoreRowModel(),
    onSortingChange: setSorting,
    getSortedRowModel: getSortedRowModel(),
    onColumnFiltersChange: setColumnFilters,
    getFilteredRowModel: getFilteredRowModel(),
    getFacetedRowModel: getFacetedRowModel(),
    getFacetedUniqueValues: getFacetedUniqueValues(),
    getFacetedMinMaxValues: getFacetedMinMaxValues(),
    getRowId: (originalRow: T) => {
      return idAccessor(originalRow);
    },
  });

  React.useEffect(() => {
    if (selectableRows) {
      const rows = table
        .getSelectedRowModel()
        .rows.map((r) => r.original)
        .filter((r) => isRowSelectable && isRowSelectable(r));
      onRowSelectionChange?.(rows as any);
    }
  }, [rowSelection]);

  return (
    <div className="overflow-auto">
      <table className="table table-borderless table-striped mt-2">
        <thead>
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id} aria-label="headers">
              {headerGroup.headers.map((header, index) => {
                let className = "col";
                if (header.column.getCanSort()) {
                  className += " sortable";
                }
                let sortIcon = <></>;
                const sorted = header.column.getIsSorted();
                if (sorted === "desc") {
                  sortIcon = (
                    <i
                      className="bi bi-arrow-down ms-2"
                      aria-label="move down"
                    ></i>
                  );
                } else if (sorted === "asc") {
                  sortIcon = (
                    <i className="bi bi-arrow-up ms-2" aria-label="move up"></i>
                  );
                } else if (header.column.getCanSort()) {
                  sortIcon = (
                    <i
                      className="bi bi-arrow-down-up ms-2"
                      aria-label="move"
                    ></i>
                  );
                }
                return (
                  <th
                    scope="col"
                    key={header.id}
                    colSpan={header.colSpan}
                    className={className}
                    onClick={header.column.getToggleSortingHandler()}
                    style={{
                      maxWidth: header.column.columnDef.maxSize,
                      minWidth: header.column.columnDef.minSize,
                      width: `${header.column.columnDef.size}%`,
                      ...(index == 0 && selectableRows
                        ? { width: "45px" }
                        : {}),
                    }}
                  >
                    <div
                      className={`d-flex flex-column ${
                        index == 0 && selectableRows
                          ? "justify-content-center align-items-center"
                          : header.column.getCanFilter()
                          ? "filterable"
                          : ""
                      }`}
                    >
                      <div>
                        {header.isPlaceholder
                          ? null
                          : flexRender(
                              header.column.columnDef.header,
                              header.getContext()
                            )}
                        {sortIcon}
                      </div>
                      {header.column.getCanFilter() && (
                        <div
                          onClick={
                            (e) =>
                              e.stopPropagation() /* avoid sort on filter click */
                          }
                        >
                          <Filter column={header.column} table={table} />
                        </div>
                      )}
                    </div>
                  </th>
                );
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          <>
            {table.getRowModel().rows.length > 0 ? (
              table.getRowModel().rows.map((row) => (
                <>
                  <tr key={row.id}>
                    {row.getVisibleCells().map((cell, index, arr) => {
                      if (selectableRows ? index == 1 : index === 0) {
                        return (
                          <th
                            scope="row"
                            key={cell.id}
                            style={{
                              ...(selectableRows
                                ? { verticalAlign: "middle" }
                                : {}),
                            }}
                          >
                            {flexRender(
                              cell.column.columnDef.cell,
                              cell.getContext()
                            )}
                          </th>
                        );
                      } else if (hasActionColumn && index === arr.length - 1) {
                        return (
                          <td
                            scope="row"
                            key={cell.id}
                            style={{ paddingLeft: "0" }}
                          >
                            <div className="d-flex flex-row justify-content-end align-items-center">
                              {flexRender(
                                cell.column.columnDef.cell,
                                cell.getContext()
                              )}
                            </div>
                          </td>
                        );
                      }
                      return (
                        <td key={cell.id}>
                          {flexRender(
                            cell.column.columnDef.cell,
                            cell.getContext()
                          )}
                        </td>
                      );
                    })}
                  </tr>
                  {activeCustomAction.has(idAccessor(row.original!)) && (
                    <tr
                      key={`${row.id}-${activeCustomAction.get(
                        idAccessor(row.original!)
                      )}`}
                    >
                      <td colSpan={completeColumns.length}>
                        <div className="sub_container">
                          {renderActionForm(
                            extractAction(row),
                            (customRowAction) => {
                              return customRowAction.customForm?.(
                                row.original,
                                () => {
                                  setActiveCustomAction((current) => {
                                    current.delete(idAccessor(row.original!));
                                    return new Map(current);
                                  });
                                }
                              );
                            }
                          )}
                        </div>
                      </td>
                    </tr>
                  )}
                </>
              ))
            ) : (
              <tr style={{ height: "100px", fontSize: "1.3rem" }}>
                <td colSpan={completeColumns.length} className="text-center">
                  No items yet
                </td>
              </tr>
            )}
          </>
        </tbody>
      </table>
    </div>
  );
}

function Filter({ column }: { column: Column<any>; table: Table<any> }) {
  if (
    (column.columnDef?.meta as { valueType?: string })?.valueType === "discrete"
  ) {
    const possibleValues = new Set(
      [...column.getFacetedUniqueValues().keys()]
        .flatMap((arr) => arr)
        .flatMap((str) => str.split(","))
    );
    const options = [...possibleValues].map((v) => ({ value: v, label: v }));
    return (
      <Select
        isClearable
        styles={customStyles}
        options={options}
        onChange={(e) => {
          const values = e.map(({ value }) => value);
          if (values.length === 0) {
            column.setFilterValue(undefined);
          } else {
            column.setFilterValue(values);
          }
        }}
        isMulti
      />
    );
  } else if (
    (column.columnDef?.meta as { valueType?: string })?.valueType === "boolean"
  ) {
    return (
      <Select
        isClearable
        styles={customStyles}
        options={[
          { label: "true", value: true },
          { label: "false", value: false },
        ]}
        onChange={(v) => {
          if (v) {
            column.setFilterValue(v.value);
          } else {
            column.setFilterValue(undefined);
          }
        }}
      />
    );
  } else if (
    (column.columnDef?.meta as { valueType?: string })?.valueType === "status"
  ) {
    return (
      <Select
        isClearable
        styles={customStyles}
        options={[
          { label: "Enabled", value: true },
          { label: "Disabled", value: false },
        ]}
        onChange={(v) => {
          if (v) {
            column.setFilterValue(v.value);
          } else {
            column.setFilterValue(undefined);
          }
        }}
      />
    );
  } else if (
    (column.columnDef?.meta as { valueType?: string })?.valueType ===
    "activation"
  ) {
    return (
      <Select
        isClearable
        styles={customStyles}
        options={[
          { label: "Active", value: true },
          { label: "Inactive", value: false },
        ]}
        onChange={(v) => {
          if (v) {
            column.setFilterValue(v.value);
          } else {
            column.setFilterValue(undefined);
          }
        }}
      />
    );
  } else if (
    (column.columnDef?.meta as { valueType?: string })?.valueType === "rights"
  ) {
    return (
      <Select
        isClearable
        styles={customStyles}
        options={Object.keys(TLevel).map((v) => ({ label: v, value: v }))}
        onChange={(v) => {
          if (v?.value) {
            column.setFilterValue(v.value);
          } else {
            column.setFilterValue(undefined);
          }
        }}
      />
    );
  }
  return (
    <input
      value={column.getFilterValue() as string}
      type="text"
      className="table-filter"
      onChange={(e) => column.setFilterValue(e.target.value)}
    />
  );
}

function IndeterminateCheckbox({
  indeterminate,
  className = "",
  id,
  label,
  ...rest
}: {
  indeterminate?: boolean;
  className?: string;
  label: string;
  id: string;
} & React.HTMLProps<HTMLInputElement>) {
  const ref = React.useRef<HTMLInputElement>(null!);

  React.useEffect(() => {
    if (typeof indeterminate === "boolean") {
      ref.current.indeterminate = !rest.checked && indeterminate;
    }
  }, [ref, indeterminate]);

  return (
    <>
      <label className="checkbox-marked" htmlFor={id} aria-label={label} />
      <input
        type="checkbox"
        ref={ref}
        checked
        className={className + " cursor-pointer checkbox-rounded"}
        id={id}
        {...rest}
      />
    </>
  );
}
