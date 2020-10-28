import React from 'react';
import Select from 'react-select/async';
import _ from "lodash";
import * as Service from '../services';

export default class SearchTvShow extends React.Component {

  state = {
    value: ''
  };

  getOptions = (input) => {
    return Service.searchTvShow(input)
  };

  render() {
    const Option = (props) => {
      if(props.isDisabled) return null
      return (
          <div className="row resultSearch">
            <button className={"btn btn-default btnSearch"} style={{width: '100%'}} onClick={() => props.setValue(props.data)}>
            <div className="col-md-3">
              {props.data.image && <img width="300px" src={`${props.data.image}`}/>}
            </div>
            <div className="col-md-9">
              <div className="TvResult">
                <h3>{props.data.title} ({props.data.source})</h3>
                <p>
                  {_.truncate(props.data.description, {'length': 120})}
                </p>
              </div>
            </div>
            </button>
          </div>
      )
    }
    return <Select
      name="form-field-name"
      onChange={value => {
        Service.addTvShow(value.id).then(() => this.setState({value}))
      }}
      inputValue={this.state.inputValue}
      blurInputOnSelect={false}
      onInputChange={inputValue => this.setState({ inputValue })}
      loadOptions={this.getOptions}
      placeholder={"Search a tv show"}
      components={{Option}}
    />
  }
}
