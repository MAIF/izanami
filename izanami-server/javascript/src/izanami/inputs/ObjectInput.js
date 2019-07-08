import React, { Component } from 'react';

function convert(values) {
    return values
        .reverse()
        .filter(([k]) => !!k)
        .reduce((acc, [k, v]) => {
            return {[k]: v, ...acc};
        }, {});
}

export class ObjectInput extends Component {

  state = {
      values: []
  };

  componentDidMount() {
      const values = Object.entries(this.props.value);
      this.setState({values});
  }

  componentWillReceiveProps(nextProps) {
     const values = Object.entries(nextProps.value);
     this.setState({values});
  }

  changeValue = (e, name, index) => {
      if (e && e.preventDefault) e.preventDefault();

      const values = this.state.values.map(([k, v], i) => {
          if (i === index) {
              return [k, e.target.value];
          } else {
              return [k, v];
          }
      });

      this.setState({values});
      this.props.onChange(convert(values));
  };

  changeKey = (e, oldName, index) => {
    if (e && e.preventDefault) e.preventDefault();

    const values = this.state.values.map(([k, v], i) => {
       if (i === index) {
           return [e.target.value, v];
       } else {
           return [k, v];
       }
    });
    this.setState({values});
    this.props.onChange(convert(values));
  };

  addFirst = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.value || Object.keys(this.props.value).length === 0) {
      const values = [this.props.defaultValue || ['', ''], ...this.state.values];
      this.setState({values});
      //this.props.onChange(convert(values));
    }
  };

  addNext = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const values = [...this.state.values, this.props.defaultValue || ['', '']];
    this.setState({values});
    //this.props.onChange(convert(values));
  };

  remove = (e, idx) => {
    if (e && e.preventDefault) e.preventDefault();
    const values = this.state.values.filter((_, i) => i !== idx);
    this.setState({values});
    //this.props.onChange(convert(values));
  };

  render() {
    return (
      <div>
        {this.state.values.length === 0 && (
          <div className="form-group">
            <label htmlFor={`input-${this.props.label}`} className="col-sm-2 control-label">{this.props.label}</label>
            <div className="col-sm-10">
              <button disabled={this.props.disabled} type="button" className="btn btn-primary" onClick={this.addFirst}><i className="glyphicon glyphicon-plus-sign" /> </button>
            </div>
          </div>
        )}
        {
          this.state.values.map((value, idx) => (
            <div className="form-group" key={`obj-${idx}`}>
              {idx === 0 && <label className="col-sm-2 control-label">{this.props.label}</label>}
              {idx > 0   && <label className="col-sm-2 control-label">&nbsp;</label>}
              <div className="col-sm-10">
                <div className="input-group">
                  <input disabled={this.props.disabled} type="text" className="form-control" style={{ width: '50%' }}
                         placeholder={this.props.placeholderKey} value={value[0]} onChange={e => this.changeKey(e, value[0], idx)} />
                  <input disabled={this.props.disabled} type="text" className="form-control" style={{ width: '50%' }}
                         placeholder={this.props.placeholderValue} value={value[1]} onChange={e => this.changeValue(e, value[0], idx)} />
                  <span className="input-group-btn">
                  <button disabled={this.props.disabled} type="button" className="btn btn-danger" onClick={e => this.remove(e, idx)}><i className="glyphicon glyphicon-trash" /></button>
                    {idx === (this.state.values.length - 1) && (
                      <button disabled={this.props.disabled} type="button" className="btn btn-primary" onClick={this.addNext}><i className="glyphicon glyphicon-plus-sign" /> </button>
                    )}
                  </span>
                </div>
              </div>
            </div>
          ))
        }
      </div>
    );
  }
}