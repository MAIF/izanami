import React, {PureComponent} from "react";

function convert(values) {
  return values
    .slice(0) // reverse change original value
    .reverse()
    .filter(([k]) => !!k)
    .reduce((acc, [k, v]) => {
      return { [k]: v, ...acc };
    }, {});
}

export class ObjectInput extends PureComponent {
  state = {
    values: this.props.value ? Object.entries(this.props.value) : []
  };

  changeValue = (e, name, index) => {
    if (e && e.preventDefault) e.preventDefault();

    const values = this.state.values.map(([k, v], i) => {
      return i === index ? [k, e.target.value] : [k, v];
    });

    this.setState({ values });
    this.props.onChange(convert(values));
  };

  changeKey = (e, oldName, index) => {
    if (e && e.preventDefault) e.preventDefault();

    const values = this.state.values.map(([k, v], i) => {
      return i === index ? [e.target.value, v]: [k, v];
    });

    this.setState({ values });
    this.props.onChange(convert(values));
  };

  addFirst = e => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.value || Object.keys(this.props.value).length === 0) {
      const values = [
        this.props.defaultValue || ["", ""],
        ...this.state.values
      ];
      this.setState({ values });
      //this.props.onChange(convert(values));
    }
  };

  addNext = e => {
    if (e && e.preventDefault) e.preventDefault();
    const values = [...this.state.values, this.props.defaultValue || ["", ""]];
    this.setState({ values });
    //this.props.onChange(convert(values));
  };

  remove = (e, idx) => {
    if (e && e.preventDefault) e.preventDefault();
    const values = this.state.values.filter((_, i) => i !== idx);
    this.setState({ values });
    //this.props.onChange(convert(values));
  };

  render() {
    return (
      <div>
        {this.state.values.length === 0 && (
          <div className="form-group row">
            <label
              htmlFor={`input-${this.props.label}`}
              className="col-sm-2 col-form-label"
            >
              {this.props.label}
            </label>
            <div className="col-sm-10 d-flex align-items-center">
              <button
                disabled={this.props.disabled}
                type="button"
                className="btn btn-primary btn-sm"
                onClick={this.addFirst}
              >
                    <i className="fas fa-plus-circle" />{" "}
              </button>
            </div>
          </div>
        )}
        {this.state.values.map((value, idx) => (
          <div className="form-group row" key={`obj-${idx}`}>
            {idx === 0 && (
              <label className="col-sm-2 col-form-label">
                {this.props.label}
              </label>
            )}
            {idx > 0 && (
              <label className="col-sm-2 col-form-label">&nbsp;</label>
            )}
            <div className="col-sm-10">
              <div className="input-group">
                <input
                  disabled={this.props.disabled}
                  type="text"
                  className="form-control"
                  placeholder={this.props.placeholderKey}
                  value={value[0]}
                  onChange={e => this.changeKey(e, value[0], idx)}
                />
                <input
                  disabled={this.props.disabled}
                  type="text"
                  className="form-control"
                  placeholder={this.props.placeholderValue}
                  value={value[1]}
                  onChange={e => this.changeValue(e, value[0], idx)}
                />
                <span className="ml-1 input-group-prepend align-items-center">
                  <div>
                    <button
                      disabled={this.props.disabled}
                      type="button"
                      className="btn btn-danger btn-sm"
                      onClick={e => this.remove(e, idx)}
                    >
                      <i className="fas fa-trash-alt" />
                    </button>
                    {idx === this.state.values.length - 1 && (
                      <button
                        disabled={this.props.disabled}
                        type="button"
                        className="btn btn-primary btn-sm"
                        onClick={this.addNext}
                      >
                            <i className="fas fa-plus-circle" />{" "}
                      </button>
                    )}
                  </div>
                </span>
              </div>
            </div>
          </div>
        ))}
      </div>
    );
  }
}
