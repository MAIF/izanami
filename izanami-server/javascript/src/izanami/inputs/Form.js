import React, {Component} from 'react';
import PropTypes from 'prop-types'; // ES6

import {
  ArrayInput,
  ObjectInput,
  BooleanInput,
  SelectInput,
  TextInput,
  NumberInput,
  LabelInput,
  CodeInput,
  KeyInput,
  FieldError,
} from ".";

import _ from 'lodash';

export class Form extends Component {

  static propTypes = {
    value: PropTypes.object,
    onChange: PropTypes.func,
    schema: PropTypes.object,
    flow: PropTypes.array,
    errorReportKeys: PropTypes.array
  };

  changeValue = (name, value) => {
    if (name.indexOf('.') > -1) {
      const [key1, key2] = name.split('.');
      const newValue = {...this.props.value, [key1]: {...this.props.value[key1], [key2]: value}};
      this.props.onChange(newValue);
    } else {
      const newValue = {...this.props.value, [name]: value};
      this.props.onChange(newValue);
    }
  };

  getValue = (name, defaultValue) => {
    if (name.indexOf('.') > -1) {
      const [key1, key2] = name.split('.');
      if (this.props.value[key1]) {
        return this.props.value[key1][key2] || defaultValue;
      } else {
        return defaultValue;
      }
    } else {
      return this.props.value[name] || defaultValue;
    }
  };

  getSchema = (name) => {
    let current = {...this.props.schema[name]};
    if (!current.error) {
      current.error = {
        key: `obj.${name}`
      }
    }
    return current;
  };


  generateStep(name, idx) {
    if (_.isFunction(name)) {
      return React.createElement(name, {})
    } else if (React.isValidElement(name)) {
      return name;
    } else if (name === '---') {
      return <hr key={idx}/>
    } else {
      const {type, disabled, props = {}, error} = this.getSchema(name);

      let fieldOnError = false;
      let errorReport = [];
      if (error) {
        errorReport = (this.props.errorReportKeys || []).filter(({message}) => message.startsWith(error.key))
      }

      if (type) {
        if (type === 'array') {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              <ArrayInput disabled={disabled} value={this.getValue(name, [])} {...props}
                          onChange={v => this.changeValue(name, v)}/>
            </FieldError>
          )
        } else if (type === 'object') {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              <ObjectInput disabled={disabled}
                           value={this.getValue(name, {})} {...props}
                           onChange={v => this.changeValue(name, v)}/>
            </FieldError>
          )
        } else if (type === 'bool') {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              <BooleanInput disabled={disabled}
                            value={this.getValue(name, false)} {...props}
                            onChange={v => this.changeValue(name, v)}/>
            </FieldError>
          )
        } else if (type === 'select') {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              <SelectInput disabled={disabled}
                           value={this.getValue(name, '')} {...props}
                           onChange={v => this.changeValue(name, v)}/>
            </FieldError>
          )
        } else if (type === 'string') {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              <TextInput disabled={disabled} value={this.getValue(name, '')} {...props}
                         onChange={v => this.changeValue(name, v)}/>
            </FieldError>)
        } else if (type === 'code') {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              <CodeInput disabled={disabled}
                         value={this.getValue(name, '')} {...props}
                         onChange={v => this.changeValue(name, v)}/>
            </FieldError>
          )
        } else if (type === 'label') {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              <LabelInput value={this.getValue(name, '')} {...props} />
            </FieldError>
          )
        } else if (type === 'number') {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              <NumberInput disabled={disabled}
                           value={this.getValue(name, 0)} {...props}
                           onChange={v => this.changeValue(name, v)}/>
            </FieldError>
          )
        } else if (type === 'key') {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              <KeyInput disabled={disabled} value={this.getValue(name, '')} {...props}
                         onChange={v => this.changeValue(name, v)}/>
            </FieldError>
          )
        } else if (_.isFunction(type)) {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              {
                React.createElement(type, {
                  ...props,
                  disabled,
                  key: name,
                  value: this.getValue(name, {}),
                  onChange: v => this.changeValue(name, v),
                  source: this.props.value
                })
              }
            </FieldError>
          );
        } else if (React.isValidElement(type)) {
          return (
            <FieldError key={name} error={fieldOnError} errorMessage={errorReport}>
              {type}
            </FieldError>
          );
        } else {
          console.error(`No field named '${name}' of type ${type}`);
          return null;
        }
      } else {
        return null;
      }
    }
  }

  render() {
    return (
      <form className="form-horizontal" style={this.props.style} {...this.props} >
        {
          this.props.flow.map((step, idx) => this.generateStep(step, idx))
        }
        {this.props.children}
      </form>
    );
  }
}