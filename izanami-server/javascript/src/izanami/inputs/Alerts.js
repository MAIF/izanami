import React, { Component } from "react";
import PropTypes from "prop-types"; // ES6
import * as TranslateService from "../services/TranslateService";

export class Alerts extends Component {
  static propTypes = {
    display: PropTypes.bool,
    messages: PropTypes.array,
    type: PropTypes.string,
    onClose: PropTypes.func
  };

  state = {
    display: true
  };

  close = () => {
    this.setState({ display: false });
    if (this.props.onClose) {
      this.props.onClose();
    }
  };

  render() {
    const display =
      this.state.display &&
      this.props.display &&
      this.props.messages &&
      this.props.messages.length;
    const type = this.props.type || "danger";
    let title;
    switch (type) {
      case "danger":
        title = "Errors";
        break;
      case "success":
        title = "Success";
        break;
      case "warning":
        title = "Warning";
        break;
      default:
        title = "Success";
    }
    if (display) {
      return (
        <div className={`panel panel-${type}`}>
          <div className="panel-heading">
            <span>{title} </span>
            <button
              type="button"
              onClick={this.close}
              className="close cancel float-right"
            >
              &times;
            </button>
          </div>
          <div className="panel-body">
            {this.props.messages.map((err, index) => (
              <p key={index}>{TranslateService.translate(err)}</p>
            ))}
          </div>
        </div>
      );
    }

    return <div />;
  }
}
