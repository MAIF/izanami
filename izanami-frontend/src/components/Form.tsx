import {
  FlowObject,
  Schema,
  Option,
  FormRef,
  TBaseObject,
} from "@maif/react-forms";
import { MutableRefObject } from "react";
import { Form as MaifReactForm } from "@maif/react-forms";
import * as React from "react";
import { Loader } from "./Loader";

type FormProps<DataType> = {
  schema: Schema;
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
};

export function Form<T extends TBaseObject>(props: FormProps<T>) {
  const [loading, setLoading] = React.useState(false);
  const { onSubmit, ...rest } = props;
  return (
    <MaifReactForm
      footer={({ valid, ...rest }: { valid: () => void }) => {
        console.log("rest", rest);
        return (
          <div className="d-flex justify-content-end pt-3">
            {props.onClose && (
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => props?.onClose()}
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
              <button className="btn btn-success ms-2" onClick={valid}>
                Save
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
