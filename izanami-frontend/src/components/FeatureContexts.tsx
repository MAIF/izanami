import { constraints, type } from "@maif/react-forms";
import { Form } from "../components/Form";
import * as React from "react";
import { useState } from "react";
import { IzanamiContext } from "../securityContext";

import { TContext } from "../utils/types";
import { useQuery } from "@tanstack/react-query";
import { GlobalContextIcon } from "../utils/icons";
import { FEATURE_NAME_REGEXP } from "../utils/patterns";
import { Loader } from "./Loader";

const LocalContext = React.createContext<{
  open: string[];
  deleteContextCallback: (path: string) => void;
  createSubContextCallback: (path: string, name: string) => Promise<any>;
  hasUpdateDeleteRight: boolean;
}>({
  open: [],
  deleteContextCallback: () => {
    /**/
  },
  createSubContextCallback: () => {
    return Promise.resolve();
  },
  hasUpdateDeleteRight: false,
});

type TOverloadRender = (
  context: TContext,
  parents: TContext[],
  path: string,
  modificationRight: boolean
) => JSX.Element | JSX.Element[];

export function FeatureContexts(props: {
  open: string[];
  deleteContext: (path: string) => Promise<void>;
  createSubContext: (path: string, name: string) => Promise<void>;
  fetchContexts: () => Promise<TContext[]>;
  overloadRender?: TOverloadRender;
  refreshKey: string;
  modificationRight: boolean;
  allowGlobalContextDelete: boolean;
}): JSX.Element {
  const {
    open,
    createSubContext,
    fetchContexts,
    modificationRight,
    deleteContext,
    overloadRender,
    refreshKey,
    allowGlobalContextDelete,
  } = props;

  const [creating, setCreating] = useState(false);
  const contextQuery = useQuery({
    queryKey: [refreshKey],
    queryFn: () => fetchContexts(),
  });

  const { askConfirmation } = React.useContext(IzanamiContext);

  if (contextQuery.data) {
    return (
      <LocalContext.Provider
        value={{
          hasUpdateDeleteRight: modificationRight,
          open,
          createSubContextCallback: (path, name) => {
            return createSubContext(path, name);
          },
          deleteContextCallback: (path) =>
            askConfirmation(
              <>
                Are you sure you want to delete context {path} ?
                <br />
                All subcontexts will be lost !
              </>,
              () => deleteContext(path)
            ),
        }}
      >
        <div className="d-flex align-items-center">
          <h1>Contexts</h1>
          {modificationRight && !creating && contextQuery.data.length > 0 && (
            <button
              className="btn btn-secondary btn-sm mb-2 ms-3"
              type="button"
              onClick={() => setCreating(true)}
            >
              Create new context
            </button>
          )}
        </div>
        {creating && (
          <div className="sub_container anim__rightToLeft">
            <h4>Create new context</h4>
            <Form
              schema={{
                name: {
                  type: type.string,
                  required: true,
                  label: "Name",
                  placeholder: "Context name",
                  constraints: [
                    constraints.matches(
                      FEATURE_NAME_REGEXP,
                      `Context name must match ${FEATURE_NAME_REGEXP} regex`
                    ),
                  ],
                  props: {
                    autoFocus: true,
                  },
                },
              }}
              onClose={() => setCreating(false)}
              onSubmit={({ name }) =>
                createSubContext("", name).then(() => setCreating(false))
              }
            />
          </div>
        )}
        {contextQuery.data.length === 0 && !creating && (
          <div className="item-block">
            <div className="item-text">
              There is no {allowGlobalContextDelete ? "global" : ""} context
              {modificationRight ? "" : " you can see"} for this{" "}
              {allowGlobalContextDelete ? "tenant" : "project"}.
            </div>
            {modificationRight && (
              <button
                type="button"
                className="btn btn-primary btn-lg"
                onClick={() => setCreating(true)}
              >
                Create new context
              </button>
            )}
          </div>
        )}
        <FeatureContextTree
          contexts={contextQuery.data}
          overloadRender={overloadRender}
          allowGlobalContextDelete={allowGlobalContextDelete ?? false}
        />
      </LocalContext.Provider>
    );
  } else if (contextQuery.error) {
    return <div>Failed to fetch contexts</div>;
  } else {
    return <Loader message="Loading contexts..." />;
  }
}

function isOpen(currentPath: string, open: string[]) {
  return open.some((path) => path.startsWith(currentPath.substring(1)));
}

function isOpenExact(currentPath: string, open: string[]) {
  return open.some((path) => path === currentPath.substring(1));
}

function Overloads({
  context,
  path,
  spacing = 16,
  parents,
  defaultOpen,
  overloadRender,
}: {
  context: TContext;
  path: string;
  spacing: number;
  parents: TContext[];
  defaultOpen: boolean;
  overloadRender: TOverloadRender;
}) {
  const [overloadDisplayed, setOverloadDisplay] = useState(defaultOpen);
  const icon = overloadDisplayed
    ? "bi-caret-down-fill anim__rotate"
    : "bi-caret-right-fill";
  const { hasUpdateDeleteRight } = React.useContext(LocalContext);
  const overloads = context.overloads;

  return (
    <div
      style={{
        marginLeft: `${spacing}px`,
        marginTop: `10px`,
      }}
    >
      <a
        href="#"
        onClick={(e) => {
          e.preventDefault();
          setOverloadDisplay((displayed) => !displayed);
        }}
      >
        <i className={`bi ${icon}`} aria-hidden></i> {overloads.length || "no"}{" "}
        overload
        {overloads.length === 1 ? "" : "s"}
      </a>
      <div
        style={{
          marginLeft: `${spacing}px`,
        }}
      >
        {overloadDisplayed &&
          overloadRender(context, parents, path, hasUpdateDeleteRight)}
      </div>
    </div>
  );
}

function FeatureContextTree(props: {
  contexts: TContext[];
  overloadRender?: TOverloadRender;
  allowGlobalContextDelete: boolean;
}): JSX.Element {
  const { contexts, overloadRender, allowGlobalContextDelete } = props;
  const { hasUpdateDeleteRight, open, deleteContextCallback } =
    React.useContext(LocalContext);
  const spacing = 20;

  function contextToTreeNode(
    contexts: TContext[],
    path = "",
    parents: TContext[] = []
  ): TreeNode<{
    context: TContext;
    path: string;
    parents: TContext[];
  }>[] {
    return contexts.map((ctx) => {
      const nonLeafChildren = contextToTreeNode(
        ctx.children,
        `${path}/${ctx.name}`,
        [...parents, ctx]
      );
      return {
        options: hasUpdateDeleteRight
          ? [
              {
                icon: <>Add subcontext</>,
                form: (submit, cancel) => {
                  const { createSubContextCallback } =
                    React.useContext(LocalContext);
                  return (
                    <div className="sub_container anim__popUp mt-2">
                      <h4>Add subcontext</h4>
                      <Form
                        schema={{
                          name: {
                            type: type.string,
                            label: "Name",
                            placeholder: "Context name",
                            required: true,
                            constraints: [
                              constraints.matches(
                                FEATURE_NAME_REGEXP,
                                `Context name must match ${FEATURE_NAME_REGEXP} regex`
                              ),
                            ],
                            props: {
                              autoFocus: true,
                            },
                          },
                        }}
                        footer={({ valid }: { valid: () => void }) => {
                          return (
                            <div className="d-flex justify-content-end">
                              <button
                                type="button"
                                className="btn btn-danger-light m-2"
                                onClick={() => cancel()}
                              >
                                Cancel
                              </button>
                              <button
                                className="btn btn-primary m-2"
                                onClick={valid}
                              >
                                Save
                              </button>
                            </div>
                          );
                        }}
                        onSubmit={({ name }) =>
                          createSubContextCallback(
                            `${path}/${ctx.name}`,
                            name
                          ).then(() => submit())
                        }
                      />
                    </div>
                  );
                },
              },
              ...(ctx.global && !allowGlobalContextDelete
                ? []
                : [
                    {
                      icon: <>Delete</>,
                      action: () =>
                        deleteContextCallback(`${path}/${ctx.name}`),
                    },
                  ]),
            ]
          : [],
        name: ctx.name,
        children: nonLeafChildren,
        payload: {
          context: ctx,
          path: `${path}/${ctx.name}`,
          parents: [...parents, ctx],
        },
        defaultOpened: isOpen(`${path}/${ctx.name}`, open),
      };
    });
  }

  return (
    <TreeRoot
      nodes={contextToTreeNode(contexts, "", [])}
      labelRender={(node) => {
        return node.payload.context.global ? (
          <span>
            <GlobalContextIcon />
            &nbsp;{node.name}
          </span>
        ) : (
          <>{node.name}</>
        );
      }}
      payloadRender={
        overloadRender
          ? ({ payload: { context, path, parents } }) => (
              <Overloads
                context={context}
                path={path}
                spacing={spacing}
                parents={parents}
                defaultOpen={isOpenExact(path, open)}
                overloadRender={overloadRender}
              />
            )
          : undefined
      }
      spacing={spacing}
      fontSize={20}
    />
  );
}

type TOption =
  | { icon: JSX.Element; action: () => void }
  | {
      icon: JSX.Element;
      form: (submit: () => void, cancel: () => void) => JSX.Element;
    };

interface TreeNode<T> {
  name: string;
  children: TreeNode<T>[];
  payload: T;
  options: TOption[];
  defaultOpened: boolean;
}

function TreeRoot<T>({
  spacing = 16,
  fontSize = 16,
  ...props
}: {
  nodes: TreeNode<T>[];
  payloadRender?: (props: { payload: T }) => JSX.Element;
  labelRender?: (node: TreeNode<T>) => JSX.Element;
  spacing: number;
  fontSize: number;
}): JSX.Element {
  return (
    <>
      {props.nodes.map((n, index) => (
        <EditableTree
          node={n}
          key={index}
          payloadRender={props.payloadRender}
          spacing={spacing}
          root={true}
          fontSize={fontSize}
          labelRender={props.labelRender}
        />
      ))}
    </>
  );
}

function defaultForm() {
  return <></>;
}

function EditableTree<T>({
  root = false,
  ...props
}: {
  node: TreeNode<T>;
  payloadRender?: (props: { payload: T }) => JSX.Element;
  spacing: number;
  root?: boolean;
  fontSize: number;
  labelRender?: (node: TreeNode<T>) => JSX.Element;
}): JSX.Element {
  const { node, payloadRender, spacing, fontSize, labelRender } = props;
  const { name, defaultOpened } = node;

  const [isOpen, setOpen] = useState(defaultOpened);
  const [form, setForm] = useState<
    (submit: () => void, cancel: () => void) => JSX.Element
  >(() => defaultForm);

  const icon = isOpen ? "bi-caret-down anim__rotate" : "bi-caret-right";
  const isOpenable = Boolean(payloadRender || node?.children?.length > 0);

  return (
    <div
      className={`izanami-tree-node ${isOpen ? "open" : ""}`}
      style={{
        marginLeft: `${root ? 0 : spacing}px`,
      }}
    >
      <div>
        <div>
          <a
            className={`${isOpenable ? "" : "disabled"}`}
            style={{ fontSize: `${fontSize}px` }}
            href="#"
            onClick={(e) => {
              e.preventDefault();
              if (isOpenable) {
                setOpen((open) => !open);
              }
            }}
          >
            <i
              className={`bi ${icon}`}
              style={isOpenable ? {} : { opacity: "0.5" }}
              aria-hidden
            ></i>{" "}
            {labelRender ? labelRender(node) : name}
          </a>{" "}
          {node.options?.length > 0 && (
            <div className="dropdown d-inline-block">
              <button
                style={{
                  paddingBottom: "0px",
                  paddingTop: "2px",
                  paddingRight: "3px",
                  paddingLeft: "3px",
                }}
                className="btn btn-secondary dropdown-toggle"
                type="button"
                data-bs-toggle="dropdown"
                aria-expanded="false"
              >
                <i
                  className="bi bi-three-dots-vertical"
                  aria-label="actions"
                ></i>
              </button>
              <ul className="dropdown-menu">
                {node.options.map(({ icon, ...rest }, index) => {
                  return (
                    <li
                      key={index}
                      onClick={() => {
                        if ("form" in rest) {
                          setForm(() => rest.form);
                        } else {
                          rest.action();
                        }
                      }}
                    >
                      <a
                        className="dropdown-item"
                        href="#"
                        onClick={(e) => e.preventDefault()}
                      >
                        {icon}
                      </a>
                    </li>
                  );
                })}
              </ul>
            </div>
          )}
        </div>
        {isOpen && (
          <>
            {payloadRender?.({ payload: node.payload })}
            {node.children.map((child, index) => (
              <>
                <EditableTree
                  node={child}
                  key={index}
                  payloadRender={payloadRender as any}
                  spacing={spacing}
                  fontSize={fontSize}
                  labelRender={labelRender}
                />
              </>
            ))}
          </>
        )}
        <div style={{ marginLeft: `${spacing}px` }}>
          {form(
            () => {
              setOpen(true);
              setForm(() => defaultForm);
            },
            () => {
              setOpen(true);
              setForm(() => defaultForm);
            }
          )}
        </div>
      </div>
    </div>
  );
}
