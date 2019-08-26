import React, { Component } from "react";
import moment from "moment";
import TimeKeeper from 'react-timekeeper';

export class TimePicker extends Component {

    state = {
        hours: undefined,
        minutes: undefined
    }

    onChange = e => {
      if (e && e.preventDefault) e.preventDefault();
      const time = moment(this.props.hourOfDay, TIME_FORMAT)        
      this.props.onChange(e.formattedSimple);        
    };
  
    render() {
      return (
        <div className="form-group">
          <label htmlFor="exampleInputAmount" className="col-sm-2 control-label">
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
                onChange={this.onChange}
            />
        </div>
      );
    }
  }