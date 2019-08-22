import React, { Component } from "react";

export class TextInput extends Component {
  onChange = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(e.target.value);
  };

  render() {
    return (
      <div className="form-group">
        <label
          htmlFor={`input-${this.props.label}`}
          className="col-sm-2 control-label"
        >
          {this.props.label}
        </label>
        <div className="col-sm-10">
          <input
            type={this.props.type || "text"}
            className="form-control"
            disabled={this.props.disabled}
            id={`input-${this.props.label}`}
            placeholder={this.props.placeholder}
            value={this.props.value}
            onChange={this.onChange}
          />
        </div>
      </div>
    );
  }
}
