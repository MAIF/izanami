import React, { Component } from 'react';

export class KeyInput extends Component {

  state = {
    key: this.props.value,
    segments: (this.props.value || '').split(":").filter(e => !!e),
    computedValue: this.props.value,
    textValue: '',
    datas: []
  };

  componentDidMount() {
    document.body.addEventListener('keydown', this.tabShortcut);
  }

  componentWillUnmount() {
    document.body.removeEventListener('keydown', this.tabShortcut);
  }

  tabShortcut = e => {
    if (e.keyCode === 9) {
      e.preventDefault();
      if (this.state.textValue) {
        const segments = [...this.state.segments, this.state.textValue];
        const key = segments.join(":");
        this.setState({segments, key, textValue: '', computedValue: key});
      }
    }
  };

  computeValue = (e) => {
    const v = e.target.value;
    if (v.endsWith(":")) {
      const segments = [...this.state.segments, ...v.split(":").filter(e => !!e)];
      const key = segments.join(":");
      this.setState({segments, key, textValue: '', computedValue: key});
      this.props.onChange(key);
      this.search();
    } else {

      const computedValue = this.state.key ? this.state.key + ":" + v : v;
      this.setState({computedValue, textValue: v});
      this.props.onChange(computedValue);
      this.search();
    }
  };



  removeLastSegment = () => {
    const segments = this.state.segments.slice(0, -1);
    const key = segments.join(":");
    this.props.onChange(key);
    this.setState({segments, key, computedValue: key})
  };

  onFocus = () => {
    this.search();
  };

  search = () => {
    if (this.state.computedValue.length > 0) {
      this.props.search(this.state.computedValue + "*")
        .then(datas => {
          this.setState({datas})
        })
    }
  };

  selectValue = (i, values) => () => {
    const segments = values.slice(0, i + 1);
    const key = segments.join(":");
    this.setState({segments, key, textValue: '', computedValue: key, datas: []});
    this.props.onChange(key);
  };

  renderResult = (str) => {
    const values = str.split(":").filter(e => !!e);
    const size = values.length;
    return (
      <div className="keypicker-result-control">
        <span className="keypicker-multi-value-wrapper">
        {values.map( (part,i) =>
        [
          <div className="keypicker-result-value" key={`result-${str}-${i}`} onClick={this.selectValue(i, values)}>
            <span className="keypicker-result-value-label">{part}</span>
          </div>,
          <div className="keypicker-result-value-sep" key={`result-sep-${str}-${i}`}>
            {i < (size - 1) && <span className="keypicker-result-value-sep-value">:</span>}
          </div>
        ])}
        </span>
      </div>
    )
  };

  render() {
    const size = this.state.segments.length;
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-sm-2 control-label">{this.props.label}</label>
        <div className="col-sm-10">
          <div className="keypicker keypicker--multi">
            <div className="keypicker-control">
              <span className="keypicker-multi-value-wrapper">
                {this.state.segments.map( (part,i) => [
                  <div className="keypicker-value" key={`value-${i}`}>
                    <span className="keypicker-value-label">{part}</span>
                    {i === (size - 1) &&
                      <span className="keypicker-value-icon" onClick={this.removeLastSegment}>x</span>
                    }
                  </div>,
                  <div className="keypicker-value-sep" key={`sep-${i}`}>
                    {i < (size - 1) && <span className="keypicker-value-sep-value">:</span>}
                  </div>
                  ]
                )}
                <div className="keypicker-input" style={{display: 'inline-block'}}>
                  <input type="text" onChange={this.computeValue} value={this.state.textValue} onFocus={this.onFocus}/>
                </div>
              </span>
            </div>
            {this.state.datas && this.state.datas.length > 0  &&
            <div className="keypicker-menu-outer">
              {this.state.datas.map((d, i) =>
                <div key={`res-${i}`}>
                  {this.renderResult(d)}
                </div>
              )}
            </div>
            }
          </div>
        </div>
      </div>
    );
  }
}