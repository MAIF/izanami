import React, { Component } from 'react';
import Select from 'react-select-plus';

export class SelectInput extends Component {

  state = {
    loading: false,
    value: this.props.value || null,
    values: (this.props.possibleValues || []).map(a => ({ label: a, value: a }))
  };

  componentDidMount() {
    if (this.props.valuesFrom) {
      this.reloadValues();
    }
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.valuesFrom && nextProps.value !== this.props.value) {
      this.reloadValues().then(() => {
        this.setState({ value: nextProps.value });
      });
    }
    if (nextProps.possibleValues !== this.props.possibleValues) {
      this.setState({ values: (nextProps.possibleValues || []).map(a => ({ label: a, value: a })) });
    }
    if (!nextProps.valuesFrom && nextProps.value !== this.props.value) {
      this.setState({ value: nextProps.value });
    }
  }

  reloadValues = () => {
    this.setState({ loading: true });
    return fetch(this.props.valuesFrom, {
      method: 'GET',
      credentials: 'include',
      headers: {
        'Accept': 'application/json'
      }
    }).then(r => r.json())
      .then(values => (this.props.extractResponse || (a => a))(values.map(this.props.transformer || (a => a))))
      .then(values => this.setState({ values, loading: false }));
  };

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
            {!this.props.disabled && <Select style={{ width: this.props.more ? '100%' : '100%' }} name={`${this.props.label}-search`} isLoading={this.state.loading} value={this.state.value} placeholder={this.props.placeholder} options={this.state.values} onChange={this.onChange} />}
            {this.props.disabled && <input type="text" className="form-control" disabled={true} placeholder={this.props.placeholder} value={this.state.value} onChange={this.onChange} />}
          </div>
        </div>
      </div>
  );
  }
}