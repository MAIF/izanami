import React, { Component } from "react";
import AceEditor from "react-ace";
import "brace/mode/javascript";
import "brace/theme/monokai";

export class JsonInput extends Component {
  onChange = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(e);
  };

  render() {
    let code = this.props.value;
    if (!this.prettyFirstTime && this.props.parse) {
      try {
        code = JSON.stringify(JSON.parse(this.props.value), null, 2);
        this.prettyFirstTime = true;
      } catch (e) {
        console.error(e);
      }
    }
    return (
      <div className="form-group row">
        <label
          htmlFor={`input-${this.props.label}`}
          className="col-sm-2 col-form-label"
        >
          {this.props.label}
        </label>
        <div className="col-sm-10">
          <AceEditor
            mode="javascript"
            theme="monokai"
            onChange={this.onChange}
            value={code}
            name="scriptParam"
            editorProps={{ $blockScrolling: true }}
            height="300px"
            width="100%"
          />
        </div>
      </div>
    );
  }
}
