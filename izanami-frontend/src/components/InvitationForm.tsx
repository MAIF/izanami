import React, { useState } from "react";
import { TLevel } from "../utils/types";
import AsyncSelect from "react-select/async";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";

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

export function InvitationForm(props: {
  submit: (p: { users: string[]; level: TLevel }) => void;
  cancel: () => void;
}) {
  const [selection, setSelection] = useState<string[]>([]);
  const [level, setLevel] = useState<TLevel | undefined>();
  return (
    <div className="d-flex flex-column sub_container anim__rightToLeft mb-2">
      <h4>Invite new users</h4>
      <label>
        Users to invite
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
          onChange={
            (values) => setSelection([...values].map((e: any) => e.value)) // FIXME TS
          }
        />
      </label>
      <label className="mt-2">
        Right level
        <Select
          styles={customStyles}
          options={Object.values(TLevel).map((n) => ({ label: n, value: n }))}
          onChange={(s) => setLevel(s?.value)}
        />
      </label>
      <div className="d-flex justify-content-end">
        <button
          type="button"
          className="btn btn-danger m-2"
          onClick={() => props.cancel()}
        >
          Cancel
        </button>
        <button
          disabled={!level || !selection}
          className="btn btn-success m-2"
          onClick={() =>
            props.submit({ users: selection, level: level || TLevel.Read })
          }
        >
          Invite users
        </button>
      </div>
    </div>
  );
}
