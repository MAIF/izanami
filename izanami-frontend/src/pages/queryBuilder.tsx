import * as React from "react";
import { queryTags, tagsQueryKey } from "../utils/queries";
import { useQuery } from "@tanstack/react-query";
import { NavLink, useParams } from "react-router-dom";
import Select from "react-select";
import { JsonViewer } from "@textea/json-viewer";
import { customStyles } from "../styles/reactSelect";
import { IzanamiContext } from "../securityContext";
import { GenericTable } from "../components/GenericTable";
import { format, parse } from "date-fns";
import { CopyButton } from "../components/CopyButton";
import { Tooltip } from "../components/Tooltip";
import { useRef } from "react";
import { Loader } from "../components/Loader";
import { AllContexts } from "../components/AllContextSelect";
import { FeatureSelector } from "../components/FeatureSelector";
import { ProjectSelector } from "../components/ProjectSelector";

export function QueryBuilder() {
  const { tenant } = useParams();
  const { expositionUrl } = React.useContext(IzanamiContext);

  const tagQuery = useQuery({
    queryKey: [tagsQueryKey(tenant!)],
    queryFn: () => queryTags(tenant!),
  });
  const [selectedProjects, setSelectedProjects] = React.useState<
    readonly string[]
  >([]);
  const [features, setFeatures] = React.useState<readonly string[]>([]);
  const [allTagsIn, setAllTagsIn] = React.useState<readonly string[]>([]);

  const [oneTagIn, setOneTagIn] = React.useState<readonly string[]>([]);
  const [noTagIn, setNoTagIn] = React.useState<readonly string[]>([]);
  const [result, setResult] = React.useState<
    { name: string; active: boolean; id: string; project: string }[]
  >([]);
  const [rawResult, setRawResult] = React.useState<{
    [x: string]: { name: string; active: boolean; project: string };
  }>({});
  const [context, setContext] = React.useState<string>("");
  const [date, setDate] = React.useState<Date>(new Date());
  const [user, setUser] = React.useState<string>("");
  const [baseUrl, setBaseUrl] = React.useState<string>(expositionUrl ?? "");
  const [parseError, setParseError] = React.useState<string>("");
  const [jsonDisplay, setJsonDisplay] = React.useState(false);
  const [tagDisplay, setTagDisplay] = React.useState(false);
  const [importDisplay, setImportDisplay] = React.useState(false);

  const [inputUrl, setInputUrl] = React.useState("");

  const resultRef = useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    resultRef?.current?.scrollIntoView();
  }, [rawResult]);

  const queryStr = [
    { name: "allTagsIn", value: allTagsIn },
    { name: "noTagIn", value: noTagIn },
    { name: "oneTagIn", value: oneTagIn },
    { name: "projects", value: selectedProjects },
    { name: "features", value: features },
    { name: "user", value: user },
    { name: "context", value: context },
  ]
    .filter(({ value }) => (Array.isArray(value) ? value.length > 0 : value))
    .map(
      ({ name, value }) =>
        `${name}=${encodeURIComponent(
          (Array.isArray(value) ? value.join(",") : value) as string
        )}`
    )
    .join("&");

  const callUrl = `${expositionUrl}/api/admin/tenants/${tenant}/features/_test${
    queryStr.length > 0 ? "?" + queryStr : ""
  }`;
  const url = `${baseUrl}/api/v2/features${
    queryStr.length > 0 ? "?" + queryStr : ""
  }`;

  const handleToggle = () => {
    setTagDisplay((current) => !current);
  };

  const handleToggleImport = () => {
    setImportDisplay((current) => !current);
  };

  if (tagQuery.error) {
    return <div>Failed to fetch tags</div>;
  } else if (tagQuery.data) {
    const tagOptions = tagQuery.data.map((t) => ({
      label: t.name,
      value: t.id,
    }));

    return (
      <>
        <h1>Client query builder</h1>
        <div className="container">
          <div className="row mt-4">
            <button
              className="ms-2 my-3 d-flex btn btn-secondary btn-sm"
              style={{ flexBasis: "fit-content" }}
              onClick={handleToggleImport}
            >
              Import existing URL
            </button>
            {importDisplay && (
              <label className="w-100">
                Existing url
                <Tooltip id="paste_url">
                  Paste an existing URL to fill below fields.
                </Tooltip>
                <div className="input-group">
                  <input
                    placeholder="Paste an existing URL to fill below fields"
                    onChange={(e) => {
                      setInputUrl(e.target.value);
                      setParseError("");
                    }}
                    type="text"
                    className="form-control"
                    id="url"
                  />
                  <div className="input-group-append">
                    <button
                      className="ms-2 btn btn-primary"
                      type="button"
                      onClick={() => {
                        const parseResult = parseUrl(inputUrl);

                        if (!parseResult) {
                          setParseError(
                            `Failed to parse pasted url, REGEXP is ${URL_REGEXP}`
                          );
                        } else {
                          const {
                            allTagsIn,
                            oneTagIn,
                            noTagIn,
                            projects,
                            features,
                            user,
                            context,
                            baseUrl,
                          } = parseResult;

                          setAllTagsIn(allTagsIn);
                          setOneTagIn(oneTagIn);
                          setNoTagIn(noTagIn);
                          setUser(user);
                          setContext(context);
                          setSelectedProjects(projects);
                          setFeatures(features);
                          setBaseUrl(baseUrl);
                        }
                      }}
                    >
                      <i
                        className="fa-solid fa-mortar-pestle"
                        title="Click to fill the fields"
                      ></i>
                    </button>
                  </div>
                </div>
                {parseError && (
                  <div id="url" className="error-message">
                    {parseError}
                  </div>
                )}
                <hr />
              </label>
            )}
          </div>
          <div className="row mt-2">
            <div className="col-sm">
              <label className="w-100">
                Projects&nbsp;
                <Tooltip id="projects">
                  All features of these projects will be evaluated.
                </Tooltip>
                <ProjectSelector
                  onChange={(ids) => {
                    setSelectedProjects(ids);
                  }}
                />
              </label>
            </div>
            <div className="col-sm">
              <label className="w-100">
                Features (Project) &nbsp;
                <Tooltip id="features" position="top">
                  These features will be evaluated, even if their projects are
                  not selected.
                </Tooltip>
                <FeatureSelector
                  onChange={(ids) => {
                    setFeatures(ids.filter((v) => v !== undefined) as string[]);
                  }}
                />
              </label>
            </div>
            <div className="col-sm">
              <label className="w-100">
                Context&nbsp;
                <Tooltip id="contexts">
                  Features will be evaluated for this context, either select or
                  type your context.
                </Tooltip>
                <AllContexts
                  id="query-selector-context-select"
                  onChange={(value) => {
                    if (!value) {
                      setContext("");
                    } else {
                      setContext(value);
                    }
                  }}
                />
              </label>
            </div>
          </div>
          <div className="row  mt-2">
            <label className="col-sm">
              User
              <input
                value={user ?? ""}
                type="text"
                className="form-control"
                onChange={(e) => setUser(e.target.value)}
              />
            </label>
            <label className="col-sm">
              Base url
              <input
                value={baseUrl}
                type="text"
                className="form-control"
                onChange={(e) => setBaseUrl(e.target.value)}
              />
            </label>
          </div>
          <div className="row mt-2">
            <div className="col">
              <button
                onClick={handleToggle}
                className="btn btn-secondary btn-sm"
              >
                {tagDisplay ? "Hide tag filters" : "Show tag filters"}
              </button>
            </div>
          </div>
          {tagDisplay && (
            <div className="row mt-2">
              <div className="col-sm">
                <label className="w-100">
                  All tags in&nbsp;
                  <Tooltip id="alltags">
                    Projects will be filtered to evaluate only features with all
                    listed tags.
                  </Tooltip>
                  <Select
                    value={tagOptions.filter(({ value }) =>
                      allTagsIn.includes(value)
                    )}
                    styles={customStyles}
                    options={tagOptions.filter(
                      ({ value }) => !allTagsIn.includes(value)
                    )}
                    onChange={(vs) => {
                      setAllTagsIn(vs.map(({ value }) => value));
                    }}
                    isMulti
                    isClearable
                  />
                </label>
              </div>
              <div className="col-sm">
                <label className="w-100">
                  No tag in&nbsp;
                  <Tooltip id="notags">
                    Projects will be filtered to evaluate only features with
                    none of the listed tags.
                  </Tooltip>
                  <Select
                    value={tagOptions.filter(({ value }) =>
                      noTagIn.includes(value)
                    )}
                    styles={customStyles}
                    options={tagOptions.filter(
                      ({ value }) => !noTagIn.includes(value)
                    )}
                    onChange={(vs) => {
                      setNoTagIn(vs.map(({ value }) => value));
                    }}
                    isMulti
                    isClearable
                  />
                </label>
              </div>
              <div className="col-sm">
                <label className="w-100">
                  One tag in&nbsp;
                  <Tooltip id="onetag">
                    Projects will be filtered to evaluate only features with
                    none of the listed tags.
                  </Tooltip>
                  <Select
                    value={tagOptions.filter(({ value }) =>
                      oneTagIn.includes(value)
                    )}
                    styles={customStyles}
                    options={tagOptions.filter(
                      ({ value }) => !oneTagIn.includes(value)
                    )}
                    onChange={(vs) => {
                      setOneTagIn(vs.map(({ value }) => value));
                    }}
                    isMulti
                    isClearable
                  />
                </label>
              </div>
            </div>
          )}
          <hr />
          <div className="row mt-4">
            <label className="w-100 ">
              Generated URL&nbsp;
              <Tooltip id="url">
                URL you can use in Izanami client applications.
              </Tooltip>
              <div className="input-group">
                <input
                  type="text"
                  className="form-control"
                  value={url}
                  id="url"
                />
                <div className="input-group-append">
                  <CopyButton value={url} />
                </div>
              </div>
            </label>
          </div>

          <div className="row justify-content-center mt-4">
            <div
              className="col-2 sub_container-bglighter"
              style={{ minWidth: "300px" }}
            >
              <div className="row justify-content-center">
                <div className="p-4">
                  <div className="row mb-4">
                    <label className="col-sm">
                      Date to emulate
                      <Tooltip id="date-emulate">
                        Allow to emulate a date to test features that rely on
                        activation days / dates
                      </Tooltip>
                      <input
                        className="form-control"
                        type="datetime-local"
                        value={format(date, "yyyy-MM-dd'T'HH:mm")}
                        onChange={(e) =>
                          setDate(
                            parse(
                              e.target.value,
                              "yyyy-MM-dd'T'HH:mm",
                              new Date()
                            )
                          )
                        }
                      />
                    </label>
                  </div>
                  <div className="text-center">
                    <button
                      disabled={
                        selectedProjects.length === 0 && features.length === 0
                      }
                      className="btn btn-primary btn-lg"
                      type="button"
                      onClick={() => {
                        fetch(callUrl.replace(expositionUrl!, ""))
                          .then((response) => response.json())
                          .then(
                            (result: {
                              [x: string]: {
                                name: string;
                                active: boolean;
                                project: string;
                              };
                            }) => {
                              setRawResult(result);
                              setResult(
                                Object.entries(result).map(
                                  ([key, { name, active, project }]) => ({
                                    name,
                                    active,
                                    id: key,
                                    project,
                                  })
                                )
                              );
                            }
                          );
                      }}
                    >
                      Test it!
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
          {Object.keys(result).length > 0 && (
            <>
              <div className="row">
                <h2 className="mt-4">Call results</h2>
                <label>
                  <input
                    checked={jsonDisplay}
                    type="checkbox"
                    className="izanami-checkbox izanami-checkbox-inline"
                    onChange={(e) => setJsonDisplay(e.target.checked)}
                  />{" "}
                  Display json
                </label>
              </div>
              {jsonDisplay ? (
                <div className="row mb-2">
                  <JsonViewer
                    rootName={false}
                    value={rawResult}
                    displayDataTypes={false}
                    displaySize={false}
                    theme="dark"
                  />
                </div>
              ) : (
                <div className="row" ref={resultRef}>
                  <GenericTable
                    isRowSelectable={() => false}
                    idAccessor={(entry) => entry.id}
                    columns={[
                      {
                        accessorKey: "id",
                        header: () => "Identifier",
                        size: 30,
                        minSize: 350,
                      },
                      {
                        accessorKey: "project",
                        header: () => "Project",
                        cell: (info: any) => {
                          return (
                            <NavLink
                              to={`/tenants/${tenant}/projects/${info.getValue()}`}
                            >
                              {info.getValue()}
                            </NavLink>
                          );
                        },
                        size: 25,
                      },
                      {
                        accessorKey: "name",
                        header: () => "Name",
                        size: 25,
                      },
                      {
                        accessorKey: "active",
                        header: () => "Value",
                        cell: (info: any) => {
                          return info.getValue() ? (
                            <span className="activation-status">
                              {"" + info.getValue()}
                            </span>
                          ) : (
                            <span className="activation-status disabled-status">
                              {"" + info.getValue()}
                            </span>
                          );
                        },
                        size: 20,
                        meta: {
                          valueType: "activation",
                        },
                      },
                    ]}
                    data={result}
                  />
                </div>
              )}
              <div className="row">
                <div className="col d-flex justify-content-end">
                  <button
                    className="btn btn-primary"
                    type="button"
                    onClick={() => {
                      setResult([]);
                    }}
                  >
                    Clear
                  </button>
                </div>
              </div>
            </>
          )}
        </div>
      </>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}

const URL_REGEXP =
  /(?<protocol>(http|https)):\/\/(?<host>.*?)(?<port>:\d*?)?\/api\/features(?<search>\?.*)?/;

function parseUrl(url: string):
  | {
      projects: string[];
      features: string[];
      allTagsIn: string[];
      noTagIn: string[];
      oneTagIn: string[];
      context: string;
      user: string;
      baseUrl: string;
    }
  | undefined {
  const groups = URL_REGEXP.exec(url)?.groups;
  if (!groups) {
    return undefined;
  }

  const search = new URLSearchParams(groups?.search);

  return {
    baseUrl: `${groups.protocol}://${groups.host}${groups.port}`,
    context: search?.get("context") ?? undefined ?? "",
    projects: search?.get("projects")?.split(",") ?? [],
    features: search?.get("features")?.split(",") ?? [],
    noTagIn: search?.get("noTagIn")?.split(",") ?? [],
    allTagsIn: search?.get("allTagsIn")?.split(",") ?? [],
    oneTagIn: search?.get("oneTagIn")?.split(",") ?? [],
    user: search?.get("user") ?? undefined ?? "",
  };
}
