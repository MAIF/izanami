import React, { Component } from "react";

export const LinkDisplay = props => (
  <div className="form-group row">
    <label className="col-sm-2 col-form-label" />
    <div className="col-sm-10">
      <i className="glyphicon glyphicon-share" />{" "}
      <a href={props.link} target="_blank">
        {props.link}
      </a>
    </div>
  </div>
);
