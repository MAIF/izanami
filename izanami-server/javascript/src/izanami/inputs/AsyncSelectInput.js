import React, { Component } from "react";
import { customStyles } from "../../styles/reactSelect";
import Async from "react-select/async";

export class AsyncSelectInput extends Component {
  state = {
    loading: false,
    value: this.props.value || null,
    loadedOptions: []
  };

  loadOptions = query => {
    return fetch(this.props.computeUrl(query), {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    })
      .then(r => r.json())
      .then(this.props.extractResponse)
      .then(results => {
        this.setState({ loadedOptions: results });
        return results;
      });
  };

  onChange = e => {
    console.log(e);
    this.setState({ value: e });
    this.props.onChange(e.value);
  };

  render() {
    return (
      <div className="form-group row">
        <label
          htmlFor={`input-${this.props.label}`}
          className="col-sm-2 col-form-label"
        >
          {this.props.label}
        </label>
        <div className="col-sm-10">
          <div style={{ width: "100%" }}>
            {!this.props.disabled && (
              <Async
                style={{ width: this.props.more ? "100%" : "100%" }}
                styles={customStyles}
                defaultOptions
                name={`${this.props.label}-search`}
                value={this.state.loadedOptions.find(
                  v => v.value === this.state.value
                )}
                placeholder={this.props.placeholder}
                loadOptions={this.loadOptions}
                onChange={this.onChange}
              />
            )}
            {this.props.disabled && (
              <input
                type="text"
                className="form-control"
                disabled={true}
                placeholder={this.props.placeholder}
                value={this.state.value}
                onChange={this.onChange}
              />
            )}
          </div>
        </div>
      </div>
    );
  }
}
