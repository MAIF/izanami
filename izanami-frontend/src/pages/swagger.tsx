import * as React from "react";
import SwaggerUI from "swagger-ui-react";
import "swagger-ui-react/swagger-ui.css";

export function Swagger() {
  return (
    <div id="swagger-ui">
      <SwaggerUI url="/swagger.json" />
    </div>
  );
}
