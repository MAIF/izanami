import React from "react";
import { TContext } from "../utils/types";

export const LocalContext = React.createContext<{
  open: string[];
  deleteContextCallback: (path: string) => void;
  createSubContextCallback: (path: string, name: string) => Promise<any>;
  updateContextProtection: (v: {
    path: string;
    name: string;
    protected: boolean;
    global: boolean;
  }) => Promise<any>;
  hasUpdateDeleteRight: boolean;
  hasRightOverProtectedContexts: boolean;
  allContexts: TContext[];
}>({
  allContexts: [],
  open: [],
  deleteContextCallback: () => {
    /**/
  },
  createSubContextCallback: () => {
    return Promise.resolve();
  },
  updateContextProtection: () => {
    return Promise.resolve();
  },
  hasUpdateDeleteRight: false,
  hasRightOverProtectedContexts: false,
});
