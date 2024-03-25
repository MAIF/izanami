import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";
import "@maif/react-forms/lib/index.css";
//import "./custom.scss";
import "./styles/main.scss";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
