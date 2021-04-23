import React, { Component } from "react";
import moment from "moment";

import LocaleProvider from "antd/lib/locale-provider";
import ConfigProvider from "antd/lib/config-provider"
import enUS from "antd/lib/locale-provider/en_US";

import DatePicker from "antd/lib/date-picker";
import "antd/lib/date-picker/style/index.css";
//import '../style/datepicker.css';

export class IzaDateRangePicker extends Component {
  static defaultProps = {
    disabled: false
  };

  componentDidMount() {
    const { from, to, updateDateRange } = this.props;
    if (from && to && updateDateRange) {
      updateDateRange(from, to);
    }
  }

  onChange = (value, dateString) => {
    const from = value[0];
    const to = value[1];
    if (
      from &&
      to &&
      this.props.updateDateRange &&
      (!this.props.from.isSame(from) || !this.props.to.isSame(to))
    ) {
      this.props.updateDateRange(from, to);
    }
  };

  render() {
    const { from, to, dateFormat, timeFormat } = this.props;
    const df = dateFormat || "YYYY-MM-DD";
    const tf = timeFormat || "HH:mm:ss";
    return (
      <ConfigProvider locale={enUS}>
        <DatePicker.RangePicker
          defaultValue={[from, to]}
          showTime={{ format: tf }}
          format={df}
          disabled={this.props.disabled}
          placeholder={["Start Time", "End Time"]}
          onChange={this.onChange}
          onOk={value => value}
        />
      </ConfigProvider>
    );
  }
}

export class IzaDatePicker extends Component {
  onChange = (date, dateString) => {
    if (date && this.props.updateDate) {
      this.props.updateDate(date);
    }
  };

  render() {
    const { date, dateFormat, timeFormat } = this.props;
    const df = dateFormat || "YYYY-MM-DD";
    const tf = timeFormat || "HH:mm:ss";
    return (
      <LocaleProvider locale={enUS}>
        <DatePicker
          defaultValue={date}
          showTime={{ format: tf }}
          format={df}
          placeholder={"Date"}
          onChange={this.onChange}
          onOk={value => value}
        />
      </LocaleProvider>
    );
  }
}
