import * as React from "react";
import { useContext, useState } from "react";
import { useQuery } from "react-query";
import Select from "react-select";
import { OIDCSettings } from "../utils/types";

export function OIDCSettingsForm(props: {
  defaultValue?: OIDCSettings;
  onChange: (value: OIDCSettings) => void;
}) {

  return <div>
    Coiucou
  </div>
}
