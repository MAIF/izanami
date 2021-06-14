import React, { Component } from "react";
import sortBy from "lodash/sortBy";

class SearchResult extends Component {
  state = {
    hover: -1
  };

  selectValue = (i, values) => () => {
    const segments = values.slice(0, i + 1);
    const key = segments.join(":");
    this.props.onSelect(key);
  };

  setHoverIndex = i => e => {
    this.setState({ hover: i });
  };

  resetHoverIndex = e => {
    this.setState({ hover: -1 });
  };

  classOfElt = i => {
    if (i <= this.state.hover) {
      return "keypicker-result-value result-active";
    } else {
      return "keypicker-result-value";
    }
  };

  render() {
    const values = this.props.value.split(":").filter(e => !!e);
    return (
      <div className="keypicker-result-control ">
        <span className="keypicker-multi-value-wrapper btn-group btn-breadcrumb">
          {values.map((part, i) => [
            <div
              className={`${this.classOfElt(i)} `}
              key={`result-${this.props.value}-${i}`}
              onClick={this.selectValue(i, values)}
              onMouseOver={this.setHoverIndex(i)}
              onMouseOut={this.resetHoverIndex}
            >
              <span className="keypicker-result-value-label">{part}</span>
              {i < values.length - 1 && <i className="fas fa-caret-right" />}
            </div>
          ])}
        </span>
      </div>
    );
  }
}

const keys = {
  tab: 9,
  backspace: 8,
  enter: 13
};

export class KeyInputForm extends Component {
  render() {
    return <div className="form-group row">
      <label
          htmlFor={`input-${this.props.label}`}
          className="col-sm-2 col-form-label"
      >
        {this.props.label}
      </label>
      <div className="col-sm-10">
        <KeyInput {...this.props} />
      </div>
    </div>
  }
}

export class KeyInput extends Component {
  state = {
    key: this.props.value,
    segments: (this.props.value || "").split(":").filter(e => !!e),
    computedValue: this.props.value,
    textValue: "",
    open: false,
    datas: [],
    editedIndex: -1
  };

  componentDidMount() {
    document.body.addEventListener("keydown", this.keyboard);
    document.body.addEventListener("click", this.handleTouchOutside);
  }

  componentWillUnmount() {
    document.body.removeEventListener("keydown", this.keyboard);
    document.body.removeEventListener("click", this.handleTouchOutside);
  }

  keyboard = e => {
    if (
      (e.keyCode === keys.tab || e.keyCode === keys.enter) &&
      ((this.inputRef && this.inputRef === document.activeElement) ||
        (this.editedRef && this.editedRef === document.activeElement))
    ) {
      e.preventDefault();
      if (this.state.textValue) {
        const segments = [
          ...this.state.segments,
          ...this.state.textValue
            .split(":")
            .map(s => s.trim())
            .filter(s => !!s)
        ];
        const key = segments.join(":");
        this.setState({ segments, key, textValue: "", computedValue: key });
      }
      this.validateEditedValue();
    } else if (e.keyCode === keys.backspace) {
      if (
        this.inputRef &&
        this.inputRef === document.activeElement &&
        this.state.textValue === ""
      ) {
        e.preventDefault();
        this.editLastSegment();
      }
    }
  };

  validateEditedValue = () => {
    if (this.state.editedValue || this.state.editedIndex) {
      const key = this.state.segments.join(":");
      this.setState({
        key,
        computedValue: key,
        editedValue: null,
        editedIndex: -1
      });
      if (this.inputRef) {
        this.inputRef.focus();
      }
    }
  };

  editLastSegment = () => {
    const segments = this.state.segments.slice(0, -1);
    const last = this.state.segments.slice(-1).pop();
    const key = segments.join(":");
    this.props.onChange(this.state.computedValue);
    this.setState({ segments, key, computedValue: key, textValue: last });
  };

  computeValue = e => {
    const v = e.target.value;
    if (v.endsWith(":") || v.endsWith(" ")) {
      const segments = [
        ...this.state.segments,
        ...v
          .split(":")
          .map(s => s.trim())
          .filter(s => !!s)
      ];
      const key = segments.join(":");
      this.setState({ segments, key, textValue: "", computedValue: key }, () =>
        this.search()
      );
      this.props.onChange(key);
    } else {
      const computedValue = this.state.key
        ? this.state.key + ":" + v.trim()
        : v.trim();
      this.setState({ computedValue, textValue: v }, () => this.search());
      this.props.onChange(computedValue);
    }
  };

  onFocus = () => {
    this.validateEditedValue();
    this.search();
  };

  search = (open = true) => {
    if (this.state.computedValue.length > 0) {
      this.props.search(this.state.computedValue + "*").then(datas => {
        sortBy(datas);
        this.setState({ datas, open });
      });
    } else {
      this.setState({ open: false });
    }
  };

  handleTouchOutside = event => {
    if (this.inputRef && this.inputRef.contains(event.target)) {
      this.setState({ open: !this.state.open });
    } else if (this.wrapper && !this.wrapper.contains(event.target)) {
      this.setState({ open: false });
    }
  };

  selectValue = key => {
    this.setState(
      {
        segments: key.split(":"),
        key,
        textValue: "",
        computedValue: key,
        datas: [],
        open: false
      },
      () => {
        if (this.inputRef) {
          this.inputRef.focus();
        }
        this.search(true);
        this.props.onChange(key);
      }
    );
  };

  copyToClipboard = e => {
    const text = this.state.key;
    const textArea = document.createElement("textarea");
    textArea.value = text;
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();

    try {
      document.execCommand("copy");
    } catch (err) {}
    document.body.removeChild(textArea);
  };

  setEditedIndex = (i, text) => e => {
    if(!this.props.disabled)
      if (this.state.segments.length - 1 === i) {
        this.editLastSegment();
      } else {
        this.setState({ editedIndex: i, editedValue: text });
      }
  };

  changeEditedValue = i => e => {
    const datas = this.state.segments;
    const text = e.target.value;
    if (text.endsWith(":") || text.endsWith(" ")) {
      const truncated = text.substring(0, text.length - 1);
      datas[i] = truncated;
      this.setState(
        {
          segments: [...datas],
          computedValue: datas.join(":"),
          open: false,
          editedValue: truncated
        },
        () => this.validateEditedValue()
      );
    } else {
      datas[i] = text;
      this.setState({
        segments: [...datas],
        computedValue: datas.join(":"),
        open: false,
        editedValue: text
      });
    }
  };

  render() {
    const autoFocus = this.props.autoFocus || (this.state.segments.length === 0);
    return (
          <div
            className={`keypicker keypicker--multi flex-grow-1 ${this.props.disabled ? "is-disabled" : ""}`} 
            ref={ref => (this.wrapper = ref)}
          >
            <div className="keypicker-control">
              <span className="keypicker-multi-value-wrapper btn-group btn-breadcrumb">
                {this.state.segments.map((part, i) => {
                  if (i === this.state.editedIndex) {
                    return (
                      <span
                        className="keypicker-value ms-0"
                        key={`value-${i}`}
                      >
                        <input
                          type="text"
                          ref={e => {
                            e && e.setSelectionRange(99999, 99999);
                            this.editedRef = e;
                          }}
                          className="key-picker-edited-input"
                          size={`${part.length}`}
                          onChange={this.changeEditedValue(i)}
                          value={this.state.editedValue}
                        />
                        <i className="fas fa-caret-right" />
                      </span>
                    );
                  } else {
                    return (
                      <span
                        className="keypicker-value ms-2"
                        key={`value-${i}`}
                        onDoubleClick={this.setEditedIndex(i, part)}
                      >
                        { part==="*" &&  <span style={{ fontSize: "24px" }}>{part}</span>}
                        { part!=="*" &&  <span>{part}</span>}
                        <i className="fas fa-caret-right" />
                      </span>
                    );
                  }
                })}
                <div className="keypicker-input" style={{ overflow: "hidden" }}>
                  <input
                    autoFocus={autoFocus}
                    type="text"
                    size={`${(this.state.textValue || "").length}`}
                    onChange={this.computeValue}
                    value={this.state.textValue}
                    onFocus={this.onFocus}
                    ref={e => (this.inputRef = e)}
                    placeholder={this.props.disabled ? "" : "Press space or : to separate key segments"}
                    disabled={this.props.disabled}
                  />
                </div>
              </span>
              {this.props.copyButton && <span>
                <button
                  type="button"
                  className="btn btn-sm btn-success"
                  title="copy"
                  onClick={this.copyToClipboard}
                  style={{marginTop:'2px'}}
                >
                  <i className="far fa-copy" />
                </button>
              </span>}
            </div>
            {this.state.open && (
              <div className="keypicker-menu-outer">
                {this.state.datas.map((d, i) => (
                  <SearchResult
                    key={`res-${i}`}
                    value={d}
                    onSelect={this.selectValue}
                  />
                ))}
              </div>
            )}
          </div>
    );
  }
}
