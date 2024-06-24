import React, { useState } from "react";
import { TLevel } from "../utils/types";
import AsyncSelect from "react-select/async";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { Controller, FormProvider, useForm } from "react-hook-form";
import { ErrorMessage } from "../components/HookFormErrorMessage";

const loadOptions = (
  inputValue: string,
  callback: (options: string[]) => void
) => {
  fetch(`/api/admin/users/search?query=${inputValue}&count=20`)
    .then((resp) => resp.json())
    .then((data) =>
      callback(data.map((d: string) => ({ label: d, value: d })))
    );
};

const LEVEL_OPTIONS = Object.values(TLevel).map((n) => ({
  label: n,
  value: n,
}));

export function InvitationForm(props: {
  submit: (p: { users: string[]; level: TLevel }) => void;
  cancel: () => void;
}) {
  const methods = useForm<{ users: string[]; level: TLevel }>({
    defaultValues: {},
  });
  const {
    handleSubmit,
    control,
    formState: { errors },
  } = methods;

  return (
    <FormProvider {...methods}>
      <form
        onSubmit={handleSubmit((data) => props?.submit?.(data))}
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
            render={({ field: { onChange, value } }) => (
              <AsyncSelect
                loadOptions={loadOptions as any} // FIXME TS
                styles={customStyles}
                cacheOptions
                isMulti
                noOptionsMessage={({ inputValue }) => {
                  return inputValue && inputValue.length > 0
                    ? "No user found for this search"
                    : "Start typing to search users";
                }}
                placeholder="Start typing to search users"
                onChange={(vs) => onChange(vs?.map((v) => v?.value))}
                value={value ? { label: value, value: value } : []}
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
                value={LEVEL_OPTIONS.find((o) => o.value === value)}
                onChange={(value) => {
                  onChange(value?.value);
                }}
                styles={customStyles}
                options={LEVEL_OPTIONS}
              />
            )}
          />
          <ErrorMessage errors={errors} name="level" />
        </label>
        <div className="d-flex justify-content-end">
          <button
            type="button"
            className="btn btn-danger m-2"
            onClick={() => props.cancel()}
          >
            Cancel
          </button>
          <button className="btn btn-success m-2" type="submit">
            Invite users
          </button>
        </div>
      </form>
    </FormProvider>
  );
}
