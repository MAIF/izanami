import React, { Component } from "react";
import PropTypes from "prop-types"; // ES6
import * as TranslateService from "../services/TranslateService";

export class FieldError extends Component {
  static propTypes = {
    error: PropTypes.bool,
    errorMessage: PropTypes.array
  };

  render() {
    const display =
      this.props.error || (this.props.errorMessage || []).length > 0;

    if (display) {
      return (
        <div className="form-group row has-error">
          {this.props.children}
          {this.props.errorMessage.map((err, index) => (
            <div>
              <label
                className="col-form-label col-sm-offset-2 paddingLabelError"
                for="inputError1"
                key={index}
              >
                {TranslateService.translate(err)}
              </label>
            </div>
          ))}
        </div>
      );
    }

    return <div>{this.props.children}</div>;
  }
}
