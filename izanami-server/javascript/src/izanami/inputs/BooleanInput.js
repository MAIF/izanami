import React, { Component } from "react";

export const OnSwitch = props => (
  <div className="content-switch-button-on" onClick={props.onChange}>
    <div className="switch-button-on"></div>
  </div>
);

export const OffSwitch = props => (
  <div className="content-switch-button-off" onClick={props.onChange}>
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
        <div className="form-group">
          <label className="col-sm-2 control-label">{this.props.label}</label>
          <div className="col-sm-10">
            {value && <OnSwitch onChange={this.toggleOff} />}
            {!value && <OffSwitch onChange={this.toggleOn} />}
          </div>
        </div>
      </div>
    );
    //
    // return (
    //   <div>
    //     <div className="form-group">
    //       <label className="col-sm-2 control-label">{this.props.label}</label>
    //       <div className="col-sm-10">
    //         {value &&
    //         <button disabled={this.props.disabled} type="button" style={{marginTop: 6}} title="Click to disable"
    //                 className="btn btn-success btn-xs active" data-toggle="button" aria-pressed="true"
    //                 autoComplete="off" onClick={this.toggleOff}>
    //           <i className="glyphicon glyphicon-ok"/>
    //         </button>}
    //         {!value &&
    //         <button disabled={this.props.disabled} type="button" style={{marginTop: 6}} title="Click to enable"
    //                 className="btn btn-danger btn-xs" data-toggle="button" aria-pressed="false" autoComplete="off"
    //                 onClick={this.toggleOn}>
    //           <i className="glyphicon glyphicon-remove"/>
    //         </button>}
    //       </div>
    //     </div>
    //   </div>
    // );
  }
}

export class SimpleBooleanInput extends Component {
  state = {
    enabled: !!this.props.value
  };

  toggleOff = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.setState({ enabled: false });
    this.props.onChange(false);
  };

  toggleOn = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.setState({ enabled: true });
    this.props.onChange(true);
  };

  componentWillReceiveProps(nextProps) {
    if (nextProps.value !== this.props.value) {
      this.setState({ enabled: !!nextProps.value });
    }
  }

  render() {
    const value = this.state.enabled;
    if (value) return <OnSwitch onChange={this.toggleOff} />;
    if (!value) return <OffSwitch onChange={this.toggleOn} />;
  }
}
