import { Controller, useForm } from "react-hook-form";
import { TContextOverload, TLightFeature } from "../utils/types";
import { useContext, useState } from "react";
import { useParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Tooltip as LocalToolTip } from "./Tooltip";
import Select from "react-select";
import {
  projectContextKey,
  queryContextsForProject,
  testExistingFeature,
} from "../utils/queries";
import { IzanamiContext, Modes } from "../securityContext";
import { possiblePaths } from "../utils/contextUtils";
import { customStyles } from "../styles/reactSelect";
import { format, parse } from "date-fns";
import CodeMirror from "@uiw/react-codemirror";
import { json } from "@codemirror/lang-json";
import { Loader } from "./Loader";

export interface FeatureTestType {
  user?: string;
  date: Date;
  context?: string;
  payload?: string;
}

export function ExistingFeatureTestForm(props: {
  feature: TLightFeature | TContextOverload;
  cancel?: () => any;
  context?: string;
}) {
  const { control, register, handleSubmit } = useForm<FeatureTestType>();
  const { feature } = props;

  const [message, setMessage] = useState<object | undefined>(undefined);
  const { tenant } = useParams();

  const contextQuery = useQuery({
    queryKey: [projectContextKey(tenant!, feature.project!)],
    queryFn: () => queryContextsForProject(tenant!, feature.project!),
  });

  const { mode } = useContext(IzanamiContext);

  const featureTestMutation = useMutation({
    mutationFn: ({
      context,
      user,
      date,
      payload,
    }: {
      date: Date;
      user: string;
      context: string;
      payload?: string;
    }) =>
      testExistingFeature(
        tenant!,
        feature.id!,
        date,
        context ?? "",
        user ?? "",
        payload
      ),
  });

  if (contextQuery.error) {
    return <div>Error while fetching contexts</div>;
  } else if (contextQuery.data) {
    const allContexts = possiblePaths(contextQuery.data).map(
      ({ path }) => path
    );
    return (
      <div className="position-relative">
        <div className="position-absolute top-0 end-0 px-3">
          <button
            className="btn btn-danger-light btn-lg"
            aria-label="Close test feature form"
            onClick={() => props?.cancel?.()}
          >
            <i className="fa-solid fa-xmark" />
          </button>
        </div>
        <div className="my-4">
          <form
            onSubmit={handleSubmit(({ user, date, context, payload }) => {
              setMessage(undefined);
              featureTestMutation
                .mutateAsync({
                  context: context ?? "",
                  user: user ?? "",
                  date,
                  payload,
                })
                .then((response) => {
                  setMessage(response);
                });
            })}
            className="container"
          >
            <div className="row justify-content-center">
              <div className="col-10">
                <h4>Test feature</h4>
                <div className="d-flex">
                  <div style={{ width: "45%" }}>
                    <div className="row ">
                      <label>
                        Context
                        <LocalToolTip id="context-tooltip-id">
                          Context to use for feature evaluation
                        </LocalToolTip>
                        <Controller
                          name={`context`}
                          control={control}
                          defaultValue={props.context ?? undefined}
                          render={({ field: { onChange, value } }) => (
                            <Select
                              value={value ? { label: value, value } : null}
                              onChange={(e) => {
                                onChange(e?.value ?? null);
                              }}
                              styles={customStyles}
                              options={allContexts
                                .map((c) => c.substring(1))
                                .sort()
                                .map((c) => ({ value: c, label: c }))}
                              isClearable
                              isDisabled={allContexts?.length === 0}
                              placeholder={
                                allContexts?.length > 0
                                  ? "Specify context"
                                  : "No context available"
                              }
                            />
                          )}
                        />
                      </label>
                    </div>
                    <div className="row ">
                      <label className="mt-3">
                        Date
                        <LocalToolTip id="date-tooltip-id">
                          Date to use for feature evaluation
                        </LocalToolTip>
                        <br />
                        <Controller
                          name="date"
                          defaultValue={new Date()}
                          control={control}
                          render={({ field: { onChange, value } }) => {
                            return (
                              <input
                                style={{ width: "100%" }}
                                className="form-control"
                                defaultValue={format(
                                  new Date(),
                                  "yyyy-MM-dd'T'HH:mm"
                                )}
                                value={
                                  value
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
                              />
                            );
                          }}
                        />
                      </label>
                    </div>
                    <div className="row ">
                      <label className="mt-3">
                        User
                        <LocalToolTip id="user-tooltip-id">
                          User to use for feature evaluation
                        </LocalToolTip>
                        <input
                          type="text"
                          className="form-control"
                          {...register("user")}
                        ></input>
                      </label>
                    </div>
                    <div className="row">
                      <label className="mt-3">
                        Payload
                        <LocalToolTip id="payload-tooltip-id">
                          Payload to use for feature evaluation.
                          <br />
                          This will only be used by script features.
                        </LocalToolTip>
                        <Controller
                          name="payload"
                          control={control}
                          render={({ field: { onChange, value } }) => (
                            <CodeMirror
                              value={value}
                              onChange={onChange}
                              extensions={[json()]}
                              theme={`${
                                mode === Modes.dark ? "dark" : "light"
                              }`}
                              id="test-payload"
                              minHeight="100px"
                              maxHeight="300px"
                            />
                          )}
                        />
                      </label>
                    </div>
                  </div>
                  <div
                    className="d-flex align-items-center justify-content-center"
                    style={{ width: "10%" }}
                  >
                    <div
                      style={{
                        borderRight: "2px solid var(--color_level2)",
                        height: "100%",
                      }}
                    />
                  </div>
                  <div
                    style={{ width: "45%" }}
                    className="d-flex flex-column justify-content-center"
                  >
                    <div className="row justify-content-center align-items-center">
                      <label>
                        Result
                        <CodeMirror
                          value={
                            message
                              ? JSON.stringify(message, null, 2)
                              : 'No result yet, click "Test feature"\nto fetch feature state'
                          }
                          extensions={message ? [json()] : []}
                          readOnly={true}
                          theme={`${mode === Modes.dark ? "dark" : "light"}`}
                          id="test-result"
                        />
                      </label>
                      <div className="d-flex justify-content-end ">
                        <button type="submit" className="btn btn-primary mt-2">
                          Test feature
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </form>
        </div>
      </div>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}
