import React, { Component } from "react";

export class LabelInput extends Component {
  state = {
    value: this.props.value,
    loading: false
  };

  identity(v) {
    return v;
  }

  componentDidMount() {
    const transform = this.props.transform || this.identity;
    if (this.props.from) {
      this.props
        .from()
        .then(value => this.setState({ value: transform(value) }));
    }
  }

  render() {
    return (
      <div className="form-group">
        <label className="col-sm-2 control-label">{this.props.label}</label>
        <div className="col-sm-10">
          {this.state.loading && (
            <input
              type="text"
              readOnly
              className="form-control"
              value="Loading ..."
            />
          )}
          {!this.state.loading && (
            <input
              type="text"
              readOnly
              className="form-control"
              value={this.state.value}
            />
          )}
        </div>
      </div>
    );
  }
}
