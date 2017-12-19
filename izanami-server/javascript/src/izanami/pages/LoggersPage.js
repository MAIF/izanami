import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services';
import { Table } from '../inputs';

class LogLevel extends Component {
  state = {
    logger: this.props.logger,
  };

  changeLogLevel = e => {
    const logger = this.state.logger;
    const level = e.target.value;
    this.setState({ logger: { ...logger, level } });
    BackOfficeServices.changeLogLevel(logger.name, level).then(l => {
      this.setState({ logger: { ...logger, level: l.newLevel } });
    });
  };

  render() {
    return (
      <select value={this.state.logger.level} onChange={this.changeLogLevel}>
        <option value="OFF">OFF</option>
        <option value="TRACE">TRACE</option>
        <option value="DEBUG">DEBUG</option>
        <option value="INFO">INFO</option>
        <option value="WARN">WARN</option>
        <option value="ERROR">ERROR</option>
        <option value="ALL">ALL</option>
      </select>
    );
  }
}

export class LoggersPage extends Component {
  columns = [
    { title: 'Name', search: (s, item) => item.name.indexOf(s) > -1, content: item => item.name },
    {
      title: 'Level',
      style: { textAlign: 'center', width: 100 },
      content: (item, table) => <LogLevel logger={item} table={table} />,
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Loggers Level`);
  }

  render() {
    return (
      <Table
        parentProps={this.props}
        selfUrl="loggers"
        defaultTitle="Loggers Level"
        defaultValue={() => ({})}
        itemName="logger"
        columns={this.columns}
        fetchItems={BackOfficeServices.fetchLoggers}
        showActions={false}
        showLink={false}
        extractKey={item => item.name}
        pageSize={40}
        search="otoroshi-"
      />
    );
  }
}