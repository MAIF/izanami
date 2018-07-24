import React from 'react';
import Select from 'react-select';
import _ from "lodash";
import 'react-select/dist/react-select.css';
import PropTypes from 'prop-types';
import * as Service from '../services';

class TvShowOption extends React.Component {
  static propTypes = {
    children: PropTypes.node,
    className: PropTypes.string,
    isDisabled: PropTypes.bool,
    isFocused: PropTypes.bool,
    isSelected: PropTypes.bool,
    onFocus: PropTypes.func,
    onSelect: PropTypes.func,
    option: PropTypes.object.isRequired,
  };

  onSelect = event => {
    event.preventDefault();
    event.stopPropagation();
    this.props.onSelect(this.props.option, event);
    Service.addTvShow(this.props.option.id)
  };

  render() {
    const {option:{image, title, description, source}} = this.props;
    return (
      <div>
        <button className={"btn btn-default btnSearch"} style={{width:'100%'}} onClick={this.onSelect}>
        <div className="row resultSearch">
            <div className="col-md-3">
              {image && <img width="300px" src={`${image}`} />}
            </div>
            <div className="col-md-9">
              <div className="TvResult">
                <h3>{title} ({source})</h3>
                <p>
                {_.truncate(description, {'length':120})}
                </p>
              </div>
            </div>
          </div>
        </button>
      </div>
    );
  }

}

export default class SearchTvShow extends React.Component {

  state = {
    value: ''
  };

  getOptions = (input) => {
    return Service.searchTvShow(input)
      .then((options) => {
        return {options};
      });
  };

  setValue = (value) => {
  };

  selectValue = (value, event) => {
    console.log("Selected", value);
    this.setState({ value });
  };

  render() {
    return <Select.Async
      name="form-field-name"
      onChange={this.setValue}
      onValueClick={this.selectValue}
      value={this.state.value}
      valueKey="id" labelKey="title"
      loadOptions={this.getOptions}
      placeholder={"Search a tv show"}
      optionComponent={TvShowOption}
    />
  }


}
