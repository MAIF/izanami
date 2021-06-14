import React, { Component } from "react";

export class TextInputAnimated extends Component {
  onChange = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(e.target.value);
  };

  render() {
    return (
      <div className="form-floating">
        <input
          type={this.props.type || "text"}
          className="form-control"
          disabled={this.props.disabled}
          id={`input-${this.props.label}`}
          placeholder={this.props.placeholder}
          value={this.props.value}
          onChange={this.onChange}
        />
        <label htmlFor={`input-${this.props.label}`}>{this.props.label}</label>
      </div>
    );
  }
}
