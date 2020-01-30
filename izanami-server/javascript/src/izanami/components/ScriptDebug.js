import React, { Component } from "react";
import PropTypes from "prop-types";
import AceEditor from "react-ace";
import "brace/mode/javascript";
import "brace/mode/plain_text";
import "brace/theme/monokai";
import { debugScript } from "../services";

const Logs = props => {
  const size =
    props.size === "stretched" ? "10" : props.size === "half" ? "6" : "2";
  const icon = props.size === "stretched" ? "compress" : "expand-arrows-alt";
  const func = props.size === "stretched" ? props.collapse : props.expand;
  return (
    <div className={`col-sm-${size}`}>
      <h5>
        {props.title}{" "}
        <button className="btn btn-sm btn-primary" type="button" onClick={func}>
          <i className={`fas fa-${icon}`} />
        </button>
      </h5>
      <AceEditor
        mode="plain_text"
        theme="monokai"
        value={(props.logs || []).join("\n")}
        name="logs"
        width="100%"
        height="200px"
      />
    </div>
  );
};

export class ScriptDebug extends Component {
  state = {
    context: "{}",
    logsExpanded: "half",
    errorsExpanded: "half"
  };

  static propTypes = {
    script: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
  };

  runDebug = () => {
    debugScript(
      this.props.script,
      this.props.language,
      JSON.parse(this.state.context || "{}")
    ).then(({ status, result, logs, errors }) => {
      console.log("Result", result, logs, errors);
      this.setState({ result: result ? "enabled" : "disabled", logs, errors });
    });
  };

  expandErrors = () => {
    if (this.state.errorsExpanded === "half") {
      this.setState({ errorsExpanded: "stretched", logsExpanded: "collapsed" });
    } else if (this.state.errorsExpanded === "collapsed") {
      this.setState({ logsExpanded: "half", errorsExpanded: "half" });
    }
  };

  collapseErrors = () => {
    if (this.state.errorsExpanded === "stretched") {
      this.setState({ logsExpanded: "half", errorsExpanded: "half" });
    }
  };

  expandLogs = () => {
    console.log("Expand logs");
    if (this.state.logsExpanded === "half") {
      this.setState({ logsExpanded: "stretched", errorsExpanded: "collapsed" });
    } else if (this.state.logsExpanded === "collapsed") {
      this.setState({ logsExpanded: "half", errorsExpanded: "half" });
    }
  };

  collapseLogs = () => {
    if (this.state.logsExpanded === "stretched") {
      this.setState({ logsExpanded: "half", errorsExpanded: "half" });
    }
  };

  render() {
    return (
      <div>
        <div className="row">
          <div className="col-sm-12">
            <h5>User context</h5>
            <AceEditor
              mode="javascript"
              theme="monokai"
              onChange={c => this.setState({ context: c })}
              value={this.state.context}
              name="contextParam"
              editorProps={{ $blockScrolling: true }}
              height="50px"
              width="100%"
            />
          </div>
        </div>
        <div className="row">
          <div className="col-sm-12">
            <div className="debug-button-res">
              <div class="btn-group">
                <button
                  type="button"
                  className="btn btn-success btn-sm"
                  onClick={this.runDebug}
                >
                  <span>Run </span>
                  <i className="fas fa-play-circle" />
                </button>
              </div>
              {this.state.result && (
                <div className="debug-result-zone">
                  <div>
                    <span>Result is </span>
                    <span className="main-color">
                      {this.state.result}{" "}
                      <i
                        className={`main-color fa ${
                          this.state.result === "enabled"
                            ? "fa-thumbs-up"
                            : "fa-thumbs-down"
                        }`}
                      />
                    </span>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
        <div className="row mt-2">
          <Logs
            title="Logs"
            size={this.state.logsExpanded}
            expand={this.expandLogs}
            collapse={this.collapseLogs}
            logs={this.state.logs}
          />
          <Logs
            title="Errors"
            size={this.state.errorsExpanded}
            expand={this.expandErrors}
            collapse={this.collapseErrors}
            logs={this.state.errors}
          />
        </div>
      </div>
    );
  }
}
