import React, { useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import Select from "react-select";
import { handlebarsLanguage } from "@xiechao/codemirror-lang-handlebars";
import { json } from "@codemirror/lang-json";
import { customStyles } from "../styles/reactSelect";
import Handlebars from "handlebars";
import { Tooltip } from "./Tooltip";

export function WebhookTransformationEditor(props: {
  value: string;
  onChange: (v: string) => void;
}) {
  const { value, onChange } = props;
  const [template, setTemplate] = useState(value ?? "");
  const [result, setResult] = useState<undefined | string>(undefined);
  const [event, setEvent] = useState(events.get("FEATURE_UPDATED"));
  return (
    <>
      <div
        style={{
          display: "flex",
          flexDirection: "row",
          justifyContent: "space-between",
          width: "100%",
        }}
      >
        <div
          style={{
            width: "49%",
          }}
        >
          <EventPicker
            defaultEvent="FEATURE_UPDATED"
            onChange={(event) => {
              setResult(undefined);
              setEvent(event);
            }}
          />
        </div>
        <div
          style={{
            width: "50%",
          }}
        >
          <Editor
            value={value}
            onChange={(t) => {
              setResult(undefined);
              onChange(t);
              setTemplate(t);
            }}
          />
        </div>
      </div>
      <div className="mt-2">
        {result ? (
          <>
            <label htmlFor="body-transform-result">Result</label>
            <CodeMirror
              value={result}
              height="300px"
              readOnly={true}
              theme="dark"
              id="body-transform-result"
            />
          </>
        ) : (
          <div
            style={{
              display: "flex",
              justifyContent: "center",
              alignItems: "stretch",
              marginTop: "8px",
              flexDirection: "column",
            }}
          ></div>
        )}
        <div className="d-flex flex-column mt-2">
          <button
            type="button"
            className="btn btn-primary"
            style={{
              marginRight: "2%",
              marginLeft: "2%",
            }}
            onClick={() => {
              const compiled = Handlebars.compile(template);
              const json = JSON.parse(event);
              setResult(compiled(json));
            }}
          >
            Test the template
          </button>
        </div>
      </div>
    </>
  );
}

function Editor(props: { value: string; onChange: (v: string) => void }) {
  const { value, onChange } = props;

  return (
    <>
      <label htmlFor="handlebar-editor">
        Template
        <Tooltip id="handlebar-editor-tooltip">
          This handlebar template will be applied to an incoming event
          <br />
          to craft a webhook body. Make sure that you have a
          <br /> content-type header that matches template output.
        </Tooltip>
      </label>
      <CodeMirror
        aria-label="Handlebar template"
        id="handlebar-editor"
        value={value}
        height="450px"
        extensions={[handlebarsLanguage]}
        onChange={onChange}
        theme="dark"
      />
    </>
  );
}

const events = new Map();
events.set(
  "FEATURE_UPDATED",
  JSON.stringify(
    {
      _id: 1802464900523491300,
      timestamp: "2024-06-16T22:14:55.120227Z",
      payload: {
        name: "summer-sales",
        active: false,
        project: "website",
        conditions: {
          "": {
            enabled: true,
            conditions: [
              {
                period: {
                  begin: "2024-06-16T22:00:00Z",
                  end: "2024-06-21T06:00:00Z",
                  hourPeriods: [
                    {
                      startTime: "08:00:00",
                      endTime: "23:00:00",
                    },
                    {
                      startTime: "14:00:00",
                      endTime: "15:00:00",
                    },
                  ],
                  activationDays: {
                    days: [
                      "MONDAY",
                      "FRIDAY",
                      "TUESDAY",
                      "THURSDAY",
                      "WEDNESDAY",
                    ],
                  },
                  timezone: "Europe/Paris",
                },
                rule: {
                  percentage: 25,
                },
              },
              {
                period: null,
                rule: {
                  users: ["foo", "bar"],
                },
              },
            ],
          },
        },
        previousConditions: {
          "": {
            enabled: false,
            conditions: [],
          },
        },
      },
      metadata: {
        user: "RESERVED_ADMIN_USER",
      },
      type: "FEATURE_UPDATED",
      id: "73a2ef9e-aade-4eb7-8e12-6145b5e6ef7a",
    },
    null,
    2
  )
);
events.set(
  "FEATURE_CREATED",
  JSON.stringify(
    {
      _id: 1795016616246771700,
      timestamp: "2024-05-27T08:58:05.766116Z",
      payload: {
        name: "my-feature",
        active: false,
        project: "project",
        conditions: {
          "": {
            enabled: true,
            conditions: [
              {
                period: {
                  begin: "2024-05-26T22:00:00Z",
                  end: "2024-05-31T06:00:00Z",
                  hourPeriods: [
                    {
                      startTime: "10:00:00",
                      endTime: "11:00:00",
                    },
                    {
                      startTime: "14:00:00",
                      endTime: "18:00:00",
                    },
                  ],
                  activationDays: {
                    days: [
                      "MONDAY",
                      "TUESDAY",
                      "THURSDAY",
                      "WEDNESDAY",
                      "FRIDAY",
                    ],
                  },
                  timezone: "Europe/Paris",
                },
                rule: {
                  percentage: 10,
                },
              },
              {
                period: null,
                rule: {
                  users: ["my-prod-tester"],
                },
              },
            ],
          },
        },
      },
      type: "FEATURE_CREATED",
      id: "51b72f40-3e08-4b34-8bb2-10d1d925b911",
    },
    null,
    2
  )
);
events.set(
  "FEATURE_DELETED",
  JSON.stringify(
    {
      _id: 1795016937979248600,
      timestamp: "2024-05-27T08:59:22.473978Z",
      type: "FEATURE_DELETED",
      payload: "f458e183-b191-4b31-9071-eff4414cdfea",
    },
    null,
    2
  )
);

function EventPicker(props: {
  defaultEvent: string;
  onChange: (v: string) => void;
}) {
  const { defaultEvent, onChange } = props;
  const [value, setValue] = useState(events.get(defaultEvent) ?? "");

  return (
    <div>
      <label htmlFor="event-value">
        Sample payload
        <Tooltip id="event-value-tooltip">
          This field can be used to test your template and make sure
          <br /> it works as expected on various events.
        </Tooltip>
      </label>
      <div style={{ marginBottom: "4px" }}>
        <Select
          defaultValue={
            defaultEvent ? { label: defaultEvent, value: defaultEvent } : null
          }
          options={[...events.keys()].map((key) => ({
            label: key,
            value: key,
          }))}
          styles={customStyles}
          onChange={(v) => {
            if (v?.value) {
              const newJson = events.get(v.value);
              setValue(newJson);
              onChange?.(newJson);
            }
          }}
        />
      </div>
      <CodeMirror
        id="event-value"
        value={value}
        onChange={(str) => {
          setValue(str);
          onChange(str);
        }}
        height="408px"
        extensions={[json()]}
        theme="dark"
      />
    </div>
  );
}
