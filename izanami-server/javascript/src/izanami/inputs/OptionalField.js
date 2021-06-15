import React, { Component } from "react";
import { OffSwitch, OnSwitch } from "./BooleanInput";

export class OptionalField extends Component {
  state = {
    disabled: this.props.disabled
  };

  toggleOff = e => {
    if (e && e.preventDefault) e.preventDefault();
    if (this.props.onChange) {
      this.props.onChange(null);
    }
    this.setState({ disabled: true });
  };

  toggleOn = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.setState({ disabled: false });
  };

  render() {
    return (
      <div>
        <div className="row mb-3">
          <label className="col-sm-2 col-form-label">{this.props.label}</label>
          <div className="col-sm-1 d-flex align-items-center">
            {!this.state.disabled && <OnSwitch onChange={this.toggleOff} />}
            {this.state.disabled && <OffSwitch onChange={this.toggleOn} />}
          </div>
          <div className="col-sm-9 d-flex align-items-center">
            {!this.state.disabled &&
              React.cloneElement(this.props.children, {
                ...this.props,
                disabled: this.state.disabled
              })}
          </div>
        </div>
      </div>
    );
  }
}
