import React, { Component } from "react";

export class PercentageInput extends Component {
  onChange = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(parseInt(e.target.value));
  };

  render() {
    return (
      <div className="form-group">
        <label htmlFor="exampleInputAmount" className="col-sm-2 control-label">
          {this.props.label} (%)
        </label>
        <div className="col-sm-2">
          <input
            type={"number"}
            className="form-control"
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
