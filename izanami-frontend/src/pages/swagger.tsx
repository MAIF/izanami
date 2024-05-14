import { useEffect } from "react";
import * as React from "react";
import SwaggerUI from "swagger-ui";
import "swagger-ui/dist/swagger-ui.css";

export function Swagger() {
  useEffect(() => {
    SwaggerUI({
      dom_id: "#swagger-ui",
      url: "/swagger.json",
    });
  }, []);
  return <div id="swagger-ui"></div>;
}
