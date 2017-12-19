import 'es6-shim';
import 'whatwg-fetch';
import Symbol from 'es-symbol';
import React, { Component } from 'react';
import PropTypes from 'prop-types';
import deepEqual from 'deep-equal';
import deepmerge from 'deepmerge';
import _ from 'lodash';
import * as Api from './api'


if (!window.Symbol) {
    window.Symbol = Symbol;
}

export class Enabled extends Component {
  render() {
    return this.props.children;
  }
}

export class Disabled extends Component {
  render() {
    return this.props.children;
  }
}

export class Feature extends Component {

  static contextTypes = {
    __mergedFeatures: PropTypes.object,
    __fetchFrom: PropTypes.string,
    __debug: PropTypes.bool,
  };

  static propTypes = {
    path: PropTypes.string.isRequired,
    debug: PropTypes.bool,
  };

  static defaultProps = {
    debug: false,
  };

  state = {
    features: {}
  };

  componentDidMount() {
    const fetchFrom = this.context.__fetchFrom;
    if (fetchFrom) {
      Api.register(fetchFrom, this.onFeaturesChanged)
    }
  }

  componentWillUnmount() {
    const fetchFrom = this.context.__fetchFrom;
    if (fetchFrom) {
      Api.unregister(fetchFrom, this.onFeaturesChanged)
    }
  }

  onFeaturesChanged = ({features}) => {
    if (!deepEqual(this.state.features, features)) {
      this.setState({features});
    }
  };

  render() {
    const children = this.props.children;
    const path = this.props.path.replace(/:/g, '.');
    const features = deepmerge(this.context.__mergedFeatures, this.state.features);
    const value = _.get(features, path) || { active: false };
    console.log('Value', features, path);
    const isActive = value.active;
    const childrenArray = Array.isArray(children) ? children : [children];
    const enabledChildren = childrenArray.filter(c => c.type === Enabled);
    const disabledChildren = childrenArray.filter(c => c.type === Disabled);
    const debug = !!this.context.__debug || this.props.debug;
    if (isActive && enabledChildren.length > 0) {
      if (debug) console.log(`feature '${path}' is enabled, rendering first <Enabled /> component`);
      if (debug) {
        return (
          <div className="izanami-feature-enabled" title={`Experiment ${path}: variant is ${value}`} style={{ position: 'relative', outline: '1px solid green' }}>
            <span style={{ padding: 2, color: 'white', backgroundColor: 'green', position: 'absolute', top: -17, left: -1, zIndex: 100000 }}>
              Feature <span style={{ fontWeight: 'bold' }}>{path}</span> is enabled
            </span>
            {enabledChildren[0]}
          </div>
        );
      }
      return enabledChildren[0];
    } else if (!isActive && disabledChildren.length > 0) {
      if (debug) console.log(`feature '${path}' is disabled, rendering first <Disabled /> component`);
      if (debug) {
        return (
          <div className="izanami-feature-disabled" title={`Experiment ${path}: variant is ${value}`} style={{ position: 'relative', outline: '1px solid grey' }}>
            <span style={{ padding: 2, color: 'white', backgroundColor: 'grey', position: 'absolute', top: -17, left: -1, zIndex: 100000 }}>
              Feature <span style={{ fontWeight: 'bold' }}>{path}</span> is disabled
            </span>
            {disabledChildren[0]}
          </div>
        );
      }
      return disabledChildren[0];
    } else if (isActive) {
      if (debug) console.log(`feature '${path}' is enabled, rendering first child`);
      if (childrenArray.length > 1) {
        console.warn('You have to provide only one child to <Feature /> unless it\'s <Enabled /> and <Disabled /> used together.');
      }
      if (debug) {
        return (
          <div className="izanami-feature-enabled" title={`Experiment ${path}: variant is ${value}`} style={{ position: 'relative', outline: '1px solid green' }}>
            <span style={{ padding: 2, color: 'white', backgroundColor: 'green', position: 'absolute', top: -17, left: -1, zIndex: 100000 }}>
              Feature <span style={{ fontWeight: 'bold' }}>{path}</span> is enabled
            </span>
            {childrenArray[0]}
          </div>
        );
      }
      return childrenArray[0];
    } else {
      if (debug) console.log(`feature '${path}' is disabled, rendering nothing`);
      if (debug) {
        return (
          <div className="izanami-feature-disabled" title={`Experiment ${path}: variant is ${value}`} style={{ position: 'relative', outline: '1px solid grey' }}>
            <span style={{ padding: 2, color: 'white', backgroundColor: 'grey', position: 'absolute', top: -17, left: -1, zIndex: 100000 }}>
              Feature <span style={{ fontWeight: 'bold' }}>{path}</span> is disabled
            </span>
          </div>
        );
      }
      return null;
    }
  }
}


export class FeatureProvider extends Component {

  static childContextTypes = {
    __features: PropTypes.object,
    __fallback: PropTypes.object,
    __mergedFeatures: PropTypes.object,
    __debug: PropTypes.bool,
    __fetchFrom: PropTypes.string
  };

  static propTypes = {
    features: PropTypes.object.isRequired,
    fallback: PropTypes.object,
    fetchFrom: PropTypes.string,
    debug: PropTypes.bool,
  };

  static defaultProps = {
    fallback: {},
  };

  state = {
    features: this.props.features,
    fallback: this.props.fallback,
    debug: this.props.debug,
  };

  getChildContext() {
    const features = deepmerge(this.state.fallback, this.state.features);
    return {
      __debug: this.state.debug,
      __features: this.state.features,
      __fallback: this.state.fallback,
      __mergedFeatures: deepmerge(this.state.fallback, this.state.features),
      __fetchFrom: this.props.fetchFrom
    };
  }

  componentWillReceiveProps(nextProps) {
    if (!deepEqual(nextProps.features, this.props.features)) {
      this.setState({ features: nextProps.features });
    }
    if (!deepEqual(nextProps.fallback, this.props.fallback)) {
      this.setState({ fallback: nextProps.fallback });
    }
    if (nextProps.debug !== this.props.debug) {
      this.setState({ debug: nextProps.debug });
    }
  }

  render() {
    return this.props.children || null;
  }
}