import * as React from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import {
  createSearchParams,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
import { fetchProjectLogs, projectLogQueryKey } from "../utils/queries";
import { GenericTable } from "../components/GenericTable";
import { format, parse } from "date-fns";
import { FeatureDetails } from "../components/FeatureTable";
import {
  FeatureCreated,
  featureEventTypeOptions,
  FeatureUpdated,
  ProjectLogSearchQuery,
} from "../utils/types";
import { useState } from "react";
import { isEqual, range } from "lodash";
import CodeMirrorMerge from "react-codemirror-merge";
import { EditorView } from "codemirror";
import { EditorState } from "@codemirror/state";
import { json } from "@codemirror/lang-json";
import CodeMirror from "@uiw/react-codemirror";
import { Controller, FormProvider, useForm } from "react-hook-form";
import AsyncCreatableSelect from "react-select/async-creatable";
import { customStyles } from "../styles/reactSelect";
import { ErrorMessage } from "@hookform/error-message";
import Select from "react-select";
import { FeatureSelector } from "../components/FeatureSelector";
import { Tooltip } from "../components/Tooltip";
import { URLSearchParams } from "url";
import queryClient from "../queryClient";
const Original = CodeMirrorMerge.Original;
const Modified = CodeMirrorMerge.Modified;

const DEFAULT_PAGE_SIZE = 20;

function decodeArrayFromUrl(value: string | null): string[] {
  if (!value || value === "") {
    return [];
  }
  return value.split(",") ?? [];
}

function extractSearchQueryFromUrlParams(
  params: URLSearchParams
): Omit<ProjectLogSearchQuery, "total"> {
  return {
    users: decodeArrayFromUrl(params.get("users")),
    types: decodeArrayFromUrl(params.get("types")) as any,
    features: decodeArrayFromUrl(params.get("features")),
    order: (params.get("order") ?? "desc") as any,
    pageSize:
      params.get("pageSize") && Number(params.get("pageSize"))
        ? Number(params.get("pageSize"))
        : DEFAULT_PAGE_SIZE,
    begin: params.get("begin")
      ? parse(
          decodeURIComponent(params.get("begin")!),
          "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
          new Date()
        )
      : undefined,
    end: params.get("end")
      ? parse(
          decodeURIComponent(params.get("end")!),
          "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
          new Date()
        )
      : undefined,
  };
}

const logQueryKey = (
  tenant: string,
  project: string,
  query: Omit<Omit<ProjectLogSearchQuery, "total">, "pageSize">
): string[] => {
  return [
    projectLogQueryKey(tenant!, project!),
    query.users.join(""),
    query.types.join(""),
    query.features.join(""),
    String(query.begin),
    String(query.end),
    query.order,
  ];
};

export function ProjectLogs() {
  const { tenant, project } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const query = extractSearchQueryFromUrlParams(searchParams);

  const [page, setPage] = useState(0);
  const totalRef = React.useRef<number | undefined>(undefined);
  const {
    data,
    error,
    fetchNextPage,
    hasNextPage,
    isFetching,
    isFetchingNextPage,
    status,
  } = useInfiniteQuery({
    queryKey: logQueryKey(tenant!, project!, query),
    queryFn: ({ pageParam }) => {
      return fetchProjectLogs(tenant!, project!, pageParam, {
        ...query,
        total: totalRef.current === undefined,
      }).then((res) => {
        if (res.count) {
          totalRef.current = res.count;
        }
        return res;
      });
    },
    initialPageParam: null as number | null,
    getNextPageParam: (lastPage, pages) => {
      if (lastPage.events.length < query.pageSize) {
        return undefined;
      }
      if (query.order === "asc") {
        const next = Math.max.apply(
          Math,
          lastPage.events.map((log) => log.eventId)
        );
        return next;
      } else {
        const next = Math.min.apply(
          Math,
          lastPage.events.map((log) => log.eventId)
        );
        return next;
      }
    },
  });

  return (
    <>
      <SearchCriterions
        defaultValue={query}
        project={project!}
        onSubmit={({ users, features, begin, end, types }) => {
          totalRef.current = undefined;

          queryClient.invalidateQueries({
            queryKey: logQueryKey(tenant!, project!, query),
          });
          navigate({
            search: `?${createSearchParams({
              users: users.join(","),
              features: features.join(","),
              types: types.join(","),
              pageSize: "" + DEFAULT_PAGE_SIZE,
              order: query.order,
              begin: begin
                ? encodeURIComponent(
                    format(begin, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                  )
                : "",
              end: end
                ? encodeURIComponent(
                    format(end, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                  )
                : "",
            })}`,
          });
          setPage(0);
        }}
      />
      {status === "error" ? (
        <div className="error-message">Error while fetching logs</div>
      ) : status === "pending" ? (
        <div>Loading...</div>
      ) : (
        <>
          <>
            {totalRef.current && (
              <div className="d-flex justify-content-end">
                <PageSelector
                  afterMax={hasNextPage}
                  max={data.pages.length}
                  page={page}
                  total={totalRef.current}
                  disabled={isFetching || isFetchingNextPage}
                  onChange={(page) => {
                    setPage(page);
                    if (!data?.pages?.[page]) {
                      fetchNextPage();
                    }
                  }}
                  hasNext={
                    data?.pages?.[page]?.events?.length === query.pageSize
                  }
                />
              </div>
            )}
            {!data?.pages?.[page]?.events?.length ? (
              <div
                className="d-flex justify-content-center fw-bold"
                style={{ fontSize: "1.2rem" }}
              >
                No more data to display
              </div>
            ) : (
              <GenericTable
                idAccessor={(evt) => "" + evt.eventId}
                data={data.pages[page]?.events}
                columns={[
                  {
                    id: "emittedAt",
                    cell: (info) => {
                      const rowValue = info.row.original;
                      if (rowValue.emittedAt) {
                        return format(rowValue.emittedAt, "Pp");
                      }
                      return "";
                    },
                    header: () => (
                      <>
                        Date
                        <SortButton
                          currentState={query.order}
                          onChange={(newState) => {
                            navigate({
                              search: `?${createSearchParams({
                                users: query.users.join(","),
                                features: query.features.join(","),
                                types: query.types.join(","),
                                pageSize: "" + query.pageSize,
                                order: newState,
                                begin: query.begin
                                  ? encodeURIComponent(
                                      format(
                                        query.begin,
                                        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
                                      )
                                    )
                                  : "",
                                end: query.end
                                  ? encodeURIComponent(
                                      format(
                                        query.end,
                                        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
                                      )
                                    )
                                  : "",
                              })}`,
                            });
                            setPage(0);
                            totalRef.current = undefined;
                          }}
                        />
                      </>
                    ),
                    size: 15,
                    minSize: 100,
                  },
                  {
                    id: "user",
                    cell: (info) => info.row.original.user,
                    header: () => "User",
                    size: 15,
                    minSize: 100,
                  },
                  {
                    id: "type",
                    header: () => "Type",
                    cell: (info) => {
                      const rowValue = info.row.original;
                      const type = rowValue.type;
                      if (type === "FEATURE_CREATED") {
                        return "Created";
                      } else if (type === "FEATURE_UPDATED") {
                        return "Updated";
                      } else if (type === "FEATURE_DELETED") {
                        return "Deleted";
                      }
                    },
                    size: 15,
                    minSize: 100,
                  },
                  {
                    id: "eventId",
                    cell: (info) => {
                      const rowValue = info.row.original;
                      if ("conditions" in rowValue) {
                        return rowValue.conditions[""].name;
                      } else {
                        return rowValue.name;
                      }
                    },
                    header: () => "Feature",
                    size: 15,
                    minSize: 100,
                  },

                  {
                    id: "origin",
                    header: () => "Origin",
                    cell: (info) => {
                      const rowValue = info.row.original;
                      let base;
                      if (rowValue.origin === "NORMAL") {
                        base = "Triggered from backoffice";
                      } else if (
                        rowValue.origin === "IMPORT" &&
                        rowValue.authentication === "BACKOFFICE"
                      ) {
                        base = "Imported from backoffice";
                      } else if (
                        rowValue.origin === "IMPORT" &&
                        rowValue.authentication === "TOKEN"
                      ) {
                        base = `Imported with token ${rowValue.tokenName}`;
                      }

                      return base;
                    },
                    size: 30,
                    minSize: 150,
                  },
                ]}
                customRowActions={{
                  details: {
                    hasRight: (user, log) => {
                      return log.type !== "FEATURE_DELETED";
                    },
                    icon: (
                      <>
                        <i
                          className="fa-solid fa-circle-info"
                          aria-hidden="true"
                        ></i>{" "}
                        Details
                      </>
                    ),
                    customForm: (data, cancel) => (
                      <div>
                        <LogFeatureDetails
                          details={data}
                          onClose={() => cancel()}
                        />
                      </div>
                    ),
                  },
                }}
              />
            )}
          </>
        </>
      )}
    </>
  );
}

function PageSelector(props: {
  max: number;
  page: number;
  total: number;
  disabled: boolean;
  onChange: (page: number) => void;
  hasNext: boolean;
  afterMax: boolean;
}) {
  const { max, page, total, disabled, onChange, hasNext, afterMax } = props;
  return (
    <div className="d-flex flex-column align-items-end">
      <span>
        <span className="fw-bold log-result-count">{total} results</span>
        <Tooltip id="result-explanation">
          Result count is computed once initially, therefore it can become
          incorrect if new events are emitted after that.
        </Tooltip>
      </span>
      <nav>
        <ul className="pagination">
          <li
            className={`page-item ${page === 0 || disabled ? "disabled" : ""}`}
          >
            <a
              className="page-link"
              href="#"
              aria-label="Previous"
              onClick={() => {
                onChange(page - 1);
              }}
            >
              <span aria-hidden="true">&laquo;</span>
            </a>
          </li>
          {Array.from({ length: page }, (_, index) => index + 1).map((p) => {
            return (
              <li className="page-item">
                <a
                  className="page-link log-page"
                  href="#"
                  onClick={() => {
                    onChange(p - 1);
                  }}
                >
                  {p}
                </a>
              </li>
            );
          })}
          <li className="page-item disabled">
            <a className="page-link log-page current-page" href="#">
              {page + 1}
            </a>
          </li>
          {range(page + 2, max + 1).map((p) => {
            return (
              <li className="page-item">
                <a
                  className="page-link log-page"
                  href="#"
                  onClick={() => {
                    onChange(p - 1);
                  }}
                >
                  {p}
                </a>
              </li>
            );
          })}
          {afterMax && (
            <li className="page-item disabled">
              <a className="page-link" href="#" aria-label="Next">
                ...
              </a>
            </li>
          )}
          <li className={`page-item ${disabled || !hasNext ? "disabled" : ""}`}>
            <a
              className="page-link"
              href="#"
              aria-label="Next"
              onClick={() => {
                onChange(page + 1);
              }}
            >
              <span aria-hidden="true">&raquo;</span>
            </a>
          </li>
        </ul>
      </nav>
    </div>
  );
}

const loadOptions = (
  inputValue: string,
  callback: (options: { label: string; value: string }[]) => void
) => {
  fetch(`/api/admin/users/search?query=${inputValue}&count=20`)
    .then((resp) => resp.json())
    .then((data) => callback(data.map((d: string) => ({ label: d, value: d }))))
    .catch((error) => {
      console.error("Error loading options", error);
      callback([]);
    });
};

function SearchCriterions(props: {
  project: string;
  onSubmit: (
    query: Omit<ProjectLogSearchQuery, "order" | "total" | "pageSize">
  ) => void;
  defaultValue?: Omit<ProjectLogSearchQuery, "order" | "total" | "pageSize">;
}) {
  const methods = useForm<
    Omit<ProjectLogSearchQuery, "order" | "total" | "pageSize">
  >({
    defaultValues: props.defaultValue || {
      users: [],
      types: [],
      features: [],
      begin: undefined,
      end: undefined,
    },
  });

  const {
    control,
    register,
    handleSubmit,
    watch,
    formState: { errors },
    setError,
  } = methods;

  return (
    <FormProvider {...methods}>
      <form
        onSubmit={handleSubmit((data) => {
          props.onSubmit(data);
        })}
        className="container"
      >
        <div className="row">
          <label className="col-12 col-xl-6">
            Users
            <Controller
              name="users"
              control={control}
              render={({ field: { value, onChange } }) => (
                <AsyncCreatableSelect
                  loadOptions={loadOptions} // FIXME TS
                  filterOption={(options) =>
                    !value.find((v) => v === options.value)
                  }
                  styles={customStyles}
                  cacheOptions
                  isMulti
                  noOptionsMessage={({ inputValue }) => {
                    return inputValue && inputValue.length > 0
                      ? "No user found for this search"
                      : "Start typing to search users";
                  }}
                  placeholder="Start typing to search users"
                  onChange={(selected) =>
                    onChange(selected.map((s) => s.value))
                  }
                />
              )}
            />
            <ErrorMessage errors={errors} name="users" />
          </label>
          <label className="col-12 col-xl-6">
            Event type
            <Controller
              name="types"
              control={control}
              render={({ field: { onChange, value } }) => (
                <Select
                  value={featureEventTypeOptions.filter((base) =>
                    value.includes(base.value)
                  )}
                  options={featureEventTypeOptions}
                  styles={customStyles}
                  isMulti
                  onChange={(selected) =>
                    onChange(selected?.map(({ value }) => value))
                  }
                />
              )}
            />
          </label>
        </div>
        <div className="row mt-4">
          <label className="col-12 col-xl-6">
            Feature
            <Controller
              name="features"
              control={control}
              render={({ field: { onChange, value } }) => (
                <FeatureSelector
                  project={props.project}
                  value={value}
                  onChange={onChange}
                  creatable
                />
              )}
            />
          </label>
          <div className="col-12 col-xl-6">
            <div className="d-flex flex-row flex-wrap">
              <label
                style={{
                  marginRight: "24px",
                }}
              >
                <div>Period start</div>
                <Controller
                  name="begin"
                  control={control}
                  render={({ field: { onChange, value } }) => {
                    return (
                      <input
                        className="form-control"
                        value={
                          value && !isNaN(value.getTime())
                            ? format(value, "yyyy-MM-dd'T'HH:mm")
                            : ""
                        }
                        onChange={(e) => {
                          onChange(
                            parse(
                              e.target.value,
                              "yyyy-MM-dd'T'HH:mm",
                              new Date()
                            )
                          );
                        }}
                        type="datetime-local"
                        aria-label="date-range-from"
                      />
                    );
                  }}
                />
              </label>
              <label>
                <div>Period end</div>
                <Controller
                  name="end"
                  control={control}
                  render={({ field: { onChange, value } }) => {
                    return (
                      <input
                        className="form-control"
                        value={
                          value && !isNaN(value.getTime())
                            ? format(value, "yyyy-MM-dd'T'HH:mm")
                            : ""
                        }
                        onChange={(e) => {
                          onChange(
                            parse(
                              e.target.value,
                              "yyyy-MM-dd'T'HH:mm",
                              new Date()
                            )
                          );
                        }}
                        type="datetime-local"
                        aria-label="date-range-to"
                      />
                    );
                  }}
                />
              </label>
            </div>
          </div>
          <label className="col-12 col-xl-3"></label>
          {/*</div>*/}
        </div>
        <div className="row">
          <div className="d-flex justify-content-end">
            <button className="btn btn-primary" type="submit">
              Search
            </button>
          </div>
        </div>
      </form>
    </FormProvider>
  );
}

function LogFeatureDetails(props: {
  details: FeatureCreated | FeatureUpdated;
  onClose: () => void;
}) {
  const type = props.details.type;
  if (type === "FEATURE_CREATED") {
    return (
      <FeatureCreatedLogFeatureDetails
        details={props.details}
        onClose={() => props.onClose()}
      />
    );
  } else if (type === "FEATURE_UPDATED") {
    return (
      <FeatureUpdatedLogFeatureDetails
        details={props.details}
        onClose={() => props.onClose()}
      />
    );
  } else {
    return <div className="error-message">Unknown event type {type}</div>;
  }
}

function FeatureCreatedLogFeatureDetails(props: {
  details: FeatureCreated;
  onClose: () => void;
}) {
  const [isJson, setJson] = useState(false);
  const details = props.details;
  return (
    <>
      <label>
        Display raw event
        <input
          type="checkbox"
          className="izanami-checkbox"
          style={{ marginTop: 0 }}
          onChange={(e) => setJson(e.target.checked)}
        />
      </label>
      <br />
      <br />
      {isJson ? (
        <>
          <CodeMirror
            id="event-value"
            value={JSON.stringify(details, null, 2)}
            readOnly={true}
            extensions={[json()]}
            theme="dark"
          />
        </>
      ) : (
        <>
          {details.conditions[""].enabled ? "Enabled" : "Disabled"}
          <FeatureDetails feature={details.conditions[""]} />
        </>
      )}
      <div className="d-flex justify-content-end">
        <button
          type="button"
          className="btn btn-danger m-2"
          onClick={() => props.onClose()}
        >
          Close
        </button>
      </div>
    </>
  );
}

function FeatureUpdatedLogFeatureDetails(props: {
  details: FeatureUpdated;
  onClose: () => void;
}) {
  const [isJson, setJson] = useState(false);
  const details = props.details;

  return (
    <>
      <label>
        Display JSON diff
        <input
          type="checkbox"
          className="izanami-checkbox"
          style={{ marginTop: 0 }}
          onChange={(e) => {
            setJson(e.target.checked);
          }}
        />
      </label>
      <br />
      {isJson ? (
        <FeatureUpdateJsonEventDisplay event={details} />
      ) : (
        <NaturalLanguageUpdateEventDisplay event={details} />
      )}
      <div className="d-flex justify-content-end">
        <button
          type="button"
          className="btn btn-danger m-2"
          onClick={() => props.onClose()}
        >
          Close
        </button>
      </div>
    </>
  );
}

function NaturalLanguageUpdateEventDisplay(props: { event: FeatureUpdated }) {
  const details = props.event;
  const contexts = new Set([
    ...Object.keys(details.conditions),
    ...Object.keys(details.previousConditions),
  ]);
  return (
    <>
      {[...contexts].map((ctx) => {
        const oldConditions = details.previousConditions[ctx];
        const newConditions = details.conditions[ctx];
        const hasChanged = !isEqual(oldConditions, newConditions);
        return (
          <div
            className="accordion mt-3"
            id={`accordion-${ctx}`}
            key={`accordion-${ctx}`}
          >
            <div className="accordion-item">
              <h2 className="accordion-header" id="headingOne">
                <button
                  className={`accordion-button ${
                    hasChanged ? "" : "collapsed"
                  }`}
                  type="button"
                  data-bs-toggle="collapse"
                  data-bs-target={`#${`collapse-accordion-${ctx}`}`}
                  aria-expanded="true"
                  aria-controls="collapseOne"
                >
                  {ctx ? `Context ${ctx}` : "Base strategy"}
                  &nbsp;
                  {hasChanged ? (
                    <span>[CHANGED]</span>
                  ) : (
                    <span className="text-secondary">[UNCHANGED]</span>
                  )}
                </button>
              </h2>
              <div
                id={`collapse-accordion-${ctx}`}
                className={`accordion-collapse collapse ${
                  hasChanged ? "show" : ""
                }`}
                aria-labelledby="headingOne"
                data-bs-parent={`#${`accordion-${ctx}`}`}
              >
                <div className="accordion-body">
                  <div className="d-flex justify-content-start">
                    <div>
                      <h5>Before</h5>
                      {oldConditions ? (
                        <>
                          {oldConditions.name}
                          {oldConditions.enabled ? (
                            <div className="fw-bold">Enabled</div>
                          ) : (
                            <div>Disabled</div>
                          )}
                          <>
                            {oldConditions.resultType === "boolean" ? (
                              <div style={{ marginTop: "8px" }}>
                                <h6>Activation conditions</h6>
                              </div>
                            ) : (
                              <div style={{ marginTop: "8px" }}>
                                <h6>Possible values</h6>
                              </div>
                            )}
                          </>
                          <FeatureDetails feature={oldConditions} />
                        </>
                      ) : (
                        <span className="fst-italic">No specific strategy</span>
                      )}
                    </div>
                    <div
                      className="d-flex justify-content-center align-items-center"
                      style={{ fontSize: 60, margin: "0px 48px" }}
                    >
                      <i className="fa-solid fa-arrow-right"></i>
                    </div>
                    <div>
                      <h5>After</h5>
                      {hasChanged ? (
                        newConditions ? (
                          <>
                            {newConditions.name}
                            {newConditions.enabled ? (
                              <div className="fw-bold">Enabled</div>
                            ) : (
                              <div>Disabled</div>
                            )}
                            <>
                              {newConditions.resultType === "boolean" ? (
                                <div style={{ marginTop: "8px" }}>
                                  <h6>Activation conditions</h6>
                                </div>
                              ) : (
                                <div style={{ marginTop: "8px" }}>
                                  <h6>Possible values</h6>
                                </div>
                              )}
                            </>
                            <FeatureDetails feature={newConditions} />
                          </>
                        ) : (
                          <span className="fst-italic">
                            No specific strategy
                          </span>
                        )
                      ) : (
                        <span className="fst-italic">No changes</span>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        );
      })}
    </>
  );
}

function FeatureUpdateJsonEventDisplay(props: { event: FeatureUpdated }) {
  const { conditions: after, previousConditions: before } = props.event;
  return (
    <CodeMirrorMerge orientation="a-b" theme="dark">
      <div className="d-flex justify-content-around">
        <h2>Before</h2>
        <h2>After</h2>
      </div>
      <Original
        value={JSON.stringify(before, null, 2)}
        extensions={[
          json(),
          EditorView.editable.of(false),
          EditorState.readOnly.of(true),
        ]}
      />
      <Modified
        value={JSON.stringify(after, null, 2)}
        extensions={[
          json(),
          EditorView.editable.of(false),
          EditorState.readOnly.of(true),
        ]}
      />
    </CodeMirrorMerge>
  );
}

function SortButton(props: {
  currentState: "asc" | "desc";
  onChange: (newState: "asc" | "desc") => void;
}) {
  const { currentState } = props;
  if (currentState === "desc") {
    return (
      <i
        role="button"
        className="bi bi-arrow-down ms-2"
        aria-label="sort in ascending order"
        onClick={() => props.onChange("asc")}
      ></i>
    );
  } else {
    return (
      <i
        role="button"
        className="bi bi-arrow-up ms-2"
        aria-label="sort in descending order"
        onClick={() => props.onChange("desc")}
      ></i>
    );
  }
}
