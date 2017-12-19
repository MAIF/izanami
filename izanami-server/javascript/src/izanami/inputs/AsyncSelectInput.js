import React, { Component } from 'react';
import Select from 'react-select-plus';

export class AsyncSelectInput extends Component {

  state = {
    loading: false,
    value: this.props.value || null
  };

  componentDidMount() {
  
  }

  componentWillReceiveProps(nextProps) {
  
  }

  loadOptions = query => {
    return fetch(this.props.computeUrl(query), {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    })
    .then(r => r.json())
    .then(({results}) => {
        return {options: results};
    })
  }

  onChange = (e) => {
    this.setState({ value: e.value });
    this.props.onChange(e.value);
  };

  render() {
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-sm-2 control-label">{this.props.label}</label>
        <div className="col-sm-10">
          <div style={{ width: '100%'}}>
            {!this.props.disabled && <Select.Async 
                                        style={{ width: this.props.more ? '100%' : '100%' }} 
                                        name={`${this.props.label}-search`}                                         
                                        value={this.state.value} 
                                        placeholder={this.props.placeholder}                                         
                                        loadOptions={this.loadOptions}
                                        onChange={this.onChange} 
                                      />
            }
            {this.props.disabled && <input type="text" className="form-control" disabled={true} placeholder={this.props.placeholder} value={this.state.value} onChange={this.onChange} />}
          </div>
        </div>
      </div>
  );
  }
}