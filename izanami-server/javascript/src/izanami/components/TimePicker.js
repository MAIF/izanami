import React, { Component } from "react";
import moment from "moment";
import TimeKeeper from 'react-timekeeper';

export class TimePicker extends Component {

    onChange = e => {
      if (e && e.preventDefault) e.preventDefault();
      this.props.onChange(e.formatted24);        
    };
  
    render() {
      return (
        <div className="mb-3 row">
          <label htmlFor="exampleInputAmount" className="col-sm-2 col-form-label">
            {this.props.label}
          </label>
          <TimeKeeper 
                config={{
                    TIMEPICKER_BACKGROUND: '#494948', 
                    TIME_BACKGROUND: '#494948',
                    TIME_SELECTED_COLOR: '#b5b3b3',
                    CLOCK_WRAPPER_BACKGROUND: '#b5b3b3',
                    CLOCK_WRAPPER_MERIDIEM_BACKGROUND_COLOR_SELECTED: '#494948',
                    CLOCK_WRAPPER_MERIDIEM_TEXT_COLOR_SELECTED: '#b5b3b3',
                    CLOCK_HAND_ARM: '#b5b3b3',
                    CLOCK_HAND_CIRCLE_BACKGROUND: '#b5b3b3',
                    CLOCK_HAND_INTERMEDIATE_CIRCLE_BACKGROUND: '#b5b3b3',
                }}
                time={this.props.hourOfDay}
                hour24Mode
                onChange={this.onChange}
            />
        </div>
      );
    }
  }