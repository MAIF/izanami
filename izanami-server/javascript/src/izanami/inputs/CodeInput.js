import React, {PureComponent} from "react";
import AceEditor from "react-ace";
import "brace/mode/javascript";
import "brace/mode/scala";
import "brace/mode/kotlin";
import "brace/theme/monokai";
import Select from "react-select";
import { customStyles } from "../../styles/reactSelect";
import { ScriptDebug } from "../components/ScriptDebug";

export class CodeInput extends PureComponent {
  state = {}

  selectValues = () => {
    return Object.keys(this.props.languages).map(k => ({
      value: k,
      label: this.props.languages[k].label
    }));
  };

  onChange = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange({ type: this.state.language, script: e });
  };

  componentDidMount() {
    this.setState(CodeInput.applyProps(this.props));
  }

  static applyProps(props) {
    const { languages } = props;
    const language = props.value && props.value.type ? props.value.type : props.default
    const defaultCode = languages[language].snippet;
    const code = props.value && props.value.script ? props.value.script : defaultCode;
    return { language, code };
  };

  static getDerivedStateFromProps(nextProps) {
    return CodeInput.applyProps(nextProps);
  }

  onLanguageChange = e => {
    const language = e.value;
    let code = this.props.languages[language].snippet;
    this.props.onChange({ type: language, script: code });
  };

  render() {
    const debug = this.props.debug ? (
      <div className="form-group row" key={"code-input-debug-zone"}>
        <label htmlFor={`input-debug`} className="col-sm-2 col-form-label">
          Debug
        </label>
        <div className="col-sm-10">
          <ScriptDebug
            script={this.state.code || ""}
            language={this.state.language || "javascript"}
          />
        </div>
      </div>
    ) : (
      <div />
    );

    return [
      <div className="form-group row" key={"code-input-main-zone"}>
        <label
          htmlFor={`input-${this.props.label}`}
          className="col-sm-2 col-form-label"
        >
          {this.props.label}
        </label>
        <div className="col-sm-10">
          {this.selectValues() && this.selectValues().length > 1 && (
            <Select
              style={{ width: "100%" }}
              className={`react-select-container`}
              classNamePrefix={`react-select`}
              name={`select-language-search`}
              styles={customStyles}
              value={this.selectValues().filter(
                v => v.value === this.state.language
              )}
              placeholder={"Select language"}
              options={this.selectValues()}
              onChange={this.onLanguageChange}
            />
          )}
          <AceEditor
            mode={this.state.language}
            theme="monokai"
            onChange={this.onChange}
            value={this.state.code}
            name="scriptParam"
            editorProps={{ $blockScrolling: true }}
            height="300px"
            width="100%"
            className="AceEditorComponent"
          />
        </div>
      </div>,
      debug
    ];
  }
}
