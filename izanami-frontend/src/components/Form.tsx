import {
  FlowObject,
  Option,
  FormRef,
  TBaseObject,
  SchemaEntry,
  constraints,
} from "@maif/react-forms";
import { JSX, MutableRefObject } from "react";
import { Form as MaifReactForm } from "@maif/react-forms";
import * as React from "react";
import { Loader } from "./Loader";
import { Tooltip } from "./Tooltip";

type FormProps<DataType> = {
  schema: {
    [key: string]:
      | SchemaEntry
      | (Omit<SchemaEntry, "label"> & {
          required: true;
          label?: string | (() => string) | (() => React.ReactNode);
          tooltip?: () => React.ReactNode;
        });
  };
  flow?: Array<string | FlowObject>;
  value?: DataType;
  inputWrapper?: (props: object) => JSX.Element;
  onError?: (errors: object, e?: React.BaseSyntheticEvent) => void;
  footer?: (props: { reset: () => void; valid: () => void }) => JSX.Element;
  className?: string;
  options?: Option;
  ref?: MutableRefObject<FormRef | undefined>;

  // specific
  onSubmit: (obj: DataType) => Promise<void>;
  onClose?: () => void;
  submitText?: string;
  htmlFor?: string;
};

export function Form<T extends TBaseObject>(props: FormProps<T>) {
  const [loading, setLoading] = React.useState(false);
  const { onSubmit, schema, ...rest } = props;

  const newSchema = Object.entries(schema ?? {})
    .map(([key, value]) => {
      if ("required" in value && value.required) {
        let newLabel: string;
        if (typeof value.label === "function") {
          newLabel = String(value.label());
        } else {
          newLabel = value.label ?? key;
        }

        if (newLabel.slice(-1) !== "*") {
          newLabel = `${newLabel}*`;
        }

        let finalLabel: string | (() => React.ReactNode) = newLabel;
        if (value?.tooltip) {
          finalLabel = () => (
            <>
              {newLabel}
              <Tooltip id={key}>{value.tooltip?.()}</Tooltip>
            </>
          );
        }
        return [
          key,
          {
            ...value,
            label: finalLabel,
            constraints: [
              ...(value.constraints ?? []),
              constraints.required(`${value.label} is required`),
            ],
          },
        ];
      }
      return [key, value];
    })
    .reduce((acc: any, [key, value]) => {
      acc[key as string] = value;
      return acc;
    }, {});

  return (
    <MaifReactForm
      schema={newSchema}
      footer={({ valid }: { valid: () => void }) => {
        return (
          <div className="d-flex justify-content-end pt-3">
            {props.onClose && (
              <button
                type="button"
                className="btn btn-danger-light m-2"
                onClick={() => props?.onClose?.()}
              >
                Cancel
              </button>
            )}
            {loading ? (
              <div
                style={{
                  display: "flex",
                  width: "75px",
                  justifyContent: "center",
                  alignItems: "center",
                }}
              >
                <div style={{ width: "30px", height: "30px" }}>
                  <Loader />
                </div>
              </div>
            ) : (
              <button className="btn btn-primary m-2" onClick={valid}>
                {props.submitText ?? "Save"}
              </button>
            )}
          </div>
        );
      }}
      onSubmit={(value) => {
        if (onSubmit) {
          setLoading(true);
          onSubmit(value).finally(() => setLoading(false));
        }
      }}
      {...rest}
    />
  );
}
