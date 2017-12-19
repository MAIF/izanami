import React, { Component } from 'react';

export class NumberInput extends Component {

  onChange = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const value = e.target.value;
    if (value.indexOf('.') > -1) {
      this.props.onChange(parseFloat(value));
    } else {
      this.props.onChange(parseInt(value, 10));
    }
  };

  render() {
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-sm-2 control-label">{this.props.label}</label>
        <div className="col-sm-10">
          <input type="number" disabled={this.props.disabled} className="form-control" id={`input-${this.props.label}`}
                 placeholder={this.props.placeholder} value={this.props.value} onChange={this.onChange} />
        </div>
      </div>
    );
  }
}