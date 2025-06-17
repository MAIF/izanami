import { TLevel, TProjectLevel } from "../utils/types";
import AsyncSelect from "react-select/async";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { Controller, FormProvider, useForm } from "react-hook-form";
import { ErrorMessage } from "../components/HookFormErrorMessage";

export interface Option {
  label: string;
  value: string;
}

const loadOptions = (
  inputValue: string,
  callback: (options: string[]) => void
) => {
  fetch(`/api/admin/users/search?query=${inputValue}&count=20`)
    .then((resp) => resp.json())
    .then((data) => callback(data.map((d: string) => ({ label: d, value: d }))))
    .catch((error) => {
      console.error("Error loading options", error);
      callback([]);
    });
};

const LEVEL_OPTIONS = Object.values(TLevel).map((n) => ({
  label: n,
  value: n,
}));

const PROJECT_LEVEL_OPTIONS = Object.values(TProjectLevel).map((n) => ({
  label: n,
  value: n,
}));

type InvitationProps =
  | {
      submit: (p: { users: string[]; level: TLevel }) => void;
      cancel: () => void;
      invitedUsers?: string[];
      projectRight?: false;
    }
  | {
      submit: (p: { users: string[]; level: TProjectLevel }) => void;
      cancel: () => void;
      invitedUsers?: string[];
      projectRight: true;
    };

export function InvitationForm(props: InvitationProps) {
  const methods = useForm<{ users: Option[]; level: TLevel }>({
    defaultValues: {},
  });
  const {
    handleSubmit,
    control,
    formState: { errors },
  } = methods;
  console.log("props?.projectRight", props?.projectRight);
  return (
    <FormProvider {...methods}>
      <form
        onSubmit={handleSubmit((data) =>
          props?.submit?.({
            users: data.users.map((user) => user.value),
            level: data.level,
          })
        )}
        className="d-flex flex-column sub_container anim__rightToLeft mb-2"
      >
        <h4>Invite new users</h4>
        <label>
          Users to invite*
          <Controller
            name="users"
            control={control}
            rules={{
              required: "At least one user must be selected",
            }}
            defaultValue={[]}
            render={({ field }) => (
              <AsyncSelect
                {...field}
                loadOptions={loadOptions as any} // FIXME TS
                filterOption={(options) =>
                  !props.invitedUsers?.includes(options.data.value)
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
                onChange={(selected) => field.onChange(selected)}
              />
            )}
          />
          <ErrorMessage errors={errors} name="users" />
        </label>
        <label className="mt-2">
          Right level*
          <Controller
            name="level"
            control={control}
            rules={{
              required: "Right level must be selected",
            }}
            render={({ field: { onChange, value } }) => (
              <Select
                value={(props?.projectRight
                  ? PROJECT_LEVEL_OPTIONS
                  : LEVEL_OPTIONS
                ).find((o) => o.value === value)}
                onChange={(value) => {
                  onChange(value?.value);
                }}
                styles={customStyles}
                options={
                  props?.projectRight ? PROJECT_LEVEL_OPTIONS : LEVEL_OPTIONS
                }
              />
            )}
          />
          <ErrorMessage errors={errors} name="level" />
        </label>
        <div className="d-flex justify-content-end">
          <button
            type="button"
            className="btn btn-danger-light m-2"
            onClick={() => props.cancel()}
          >
            Cancel
          </button>
          <button className="btn btn-primary m-2" type="submit">
            Invite users
          </button>
        </div>
      </form>
    </FormProvider>
  );
}
