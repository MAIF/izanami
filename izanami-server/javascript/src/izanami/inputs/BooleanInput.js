import React, {Component, PureComponent} from "react";

export const OnSwitch = props => (
  <div className={`content-switch-button-on ${props.disabled ? 'disabled' : ''}`} onClick={e => { if (!props.disabled) {props.onChange(e)}}}>
    <div className="switch-button-on"></div>
  </div>
);

export const OffSwitch = props => (
  <div className={`content-switch-button-off ${props.disabled ? 'disabled' : ''}`} onClick={e => { if (!props.disabled) {props.onChange(e)}}}>
    <div className="switch-button-off"></div>
  </div>
);

export class BooleanInput extends Component {
  toggleOff = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(false);
  };

  toggleOn = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(true);
  };

  toggle = value => {
    this.props.onChange(value);
  };

  render() {
    const value = !!this.props.value;
    return (
      <div>
        <div className="row mb-3">
          <label className="col-sm-2 col-form-label">{this.props.label}</label>
          <div className="col-sm-10 d-flex align-items-center">
            {value && <OnSwitch onChange={this.toggleOff} />}
            {!value && <OffSwitch onChange={this.toggleOn} />}
          </div>
        </div>
      </div>
    );
  }
}

export class SimpleBooleanInput extends PureComponent {
  toggleOff = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(false, this);
  };

  toggleOn = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(true, this);
  };

  render() {
    const value = !!this.props.value;

    if (value) return <OnSwitch onChange={this.toggleOff} disabled={this.props.disabled}/>;
    if (!value) return <OffSwitch onChange={this.toggleOn} disabled={this.props.disabled} />;
  }
}
