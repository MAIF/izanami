import 'es6-shim';
import 'whatwg-fetch';
import Symbol from 'es-symbol';
import React, { Component } from 'react';
import { func, string, bool, object, node, oneOfType, arrayOf } from 'prop-types';
import deepEqual from 'deep-equal';
import deepmerge from 'deepmerge';
import { get, isFunction } from 'lodash';
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

class Debug extends Component {

  static propTypes = {
    path: oneOfType([string, arrayOf(string)]),
    isActive: bool,
    children: node
  };

  render() {
    const { isActive, path, children } = this.props;
    return (
      <div className={`izanami-feature-${isActive ? 'enabled' : 'disabled'}`} title={`Feature ${path} is ${isActive ? 'enabled' : 'disabled'}`} style={{ position: 'relative', outline: '1px solid green' }}>
        <span style={{ padding: 2, fontFamily: 'Arial', color: 'white', border: '1px solid black',  borderRadius: '5px', backgroundColor: isActive ? 'green' : 'grey', position: 'absolute', top: -17, left: -1, zIndex: 100000, boxShadow: '0 4px 8px 0 rgba(0, 0, 0, 0.3), 0 6px 20px 0 rgba(0, 0, 0, 0.19)' }}>
          Feature <span style={{ fontWeight: 'bold' }}>{path}</span> is {isActive ? 'enabled' : 'disabled'}
        </span>
        { children }
      </div>
    );
  }
}

export class Feature extends Component {

  static contextTypes = {
    __subscribeToFeatureContext: func,
    __unsubscribeToFeatureContext: func,
  };

  static propTypes = {
    path: oneOfType([string, arrayOf(string)]).isRequired,
    debug: bool,
  };

  static defaultProps = {
    debug: false,
  };

  state = {
    features: {},
    mergedFeatures: {}
  };

  onContextChange = ({__mergedFeatures, __fetchFrom, __debug}) => {
    if (__fetchFrom && this.state.fetchFrom !== __fetchFrom ) {
      this.setState({fetchFrom: __fetchFrom, debug: __debug, mergedFeatures: __mergedFeatures});
      if(__debug) console.log('[Features] Registering to api for ', __fetchFrom);
      Api.register(__fetchFrom , this.onFeaturesChanged)
    } else {
      this.setState({debug: __debug, mergedFeatures: __mergedFeatures});
    }
  };

  componentDidMount() {
    this.context.__subscribeToFeatureContext(this.onContextChange);
  }

  componentWillUnmount() {
    const fetchFrom = this.state.__fetchFrom;
    if (fetchFrom) {
      Api.unregister(fetchFrom, this.onFeaturesChanged)
    }
    this.context.__unsubscribeToFeatureContext(this.onContextChange);
  }

  onFeaturesChanged = ({features}) => {
    if (!deepEqual(this.state.features, features)) {
      this.setState({features});
    }
  };

  getIsActive = (features, path) => {
    if (Array.isArray(path)) {
      return path
        .map(p => this.getIsFeatureActive(features, p))
        .every(Boolean);
    } else {
      return this.getIsFeatureActive(features, path);
    }
  }

  getIsFeatureActive = (features, path) => {
    const value = get(features, path) || { active: false };
    return value.active;
  }

  render() {
    const children = this.props.children;
    const features = deepmerge(this.state.mergedFeatures, this.state.features);
    const path = this.props.path.replace(/:/g, '.');
    const isActive = this.getIsActive(features, path);
    const childrenArray = Array.isArray(children) ? children : [children];
    const enabledChildren = childrenArray.filter(c => c && c.type === Enabled);
    const disabledChildren = childrenArray.filter(c => c && c.type === Disabled);
    const debug = !!this.state.debug || this.props.debug;
    if (this.props.render && isFunction(this.props.render)) {
      if (debug) {
        return (
          <Debug isActive={ isActive } path={ path }>
            {this.props.render(isActive)}
          </Debug>
        );
      } else {
        return this.props.render(isActive);
      }
    }
    if (isActive && enabledChildren.length > 0) {
      if (debug) console.log(`[Features] feature '${path}' is enabled, rendering first <Enabled /> component`);
      if (debug) {
        return (
          <Debug isActive={ isActive } path={ path }>
            {enabledChildren[0]}
          </Debug>
        );
      }
      return enabledChildren[0];
    } else if (!isActive && disabledChildren.length > 0) {
      if (debug) console.log(`[Features] feature '${path}' is disabled, rendering first <Disabled /> component`);
      if (debug) {
        return (
          <Debug isActive={ isActive } path={ path }>
            {disabledChildren[0]}
          </Debug>
        );
      }
      return disabledChildren[0];
    } else if (isActive) {
      if (debug) console.log(`[Features] feature '${path}' is enabled, rendering first child`);
      if (childrenArray.length > 1) {
        console.warn('You have to provide only one child to <Feature /> unless it\'s <Enabled /> and <Disabled /> used together.');
      }
      if (debug) {
        return (
          <Debug isActive={ isActive } path={ path }>
            {childrenArray[0]}
          </Debug>
        );
      }
      return childrenArray[0];
    } else {
      if (debug) console.log(`[Features] feature '${path}' is disabled, rendering nothing`);
      if (debug) {
        return <Debug isActive={ isActive } path={ path } />;
      }
      return null;
    }
  }
}


export class FeatureProvider extends Component {

  callbacks = [];

  static childContextTypes = {
    __subscribeToFeatureContext: func,
    __unsubscribeToFeatureContext: func
  };

  static propTypes = {
    features: object.isRequired,
    fallback: object,
    fetchFrom: string,
    debug: bool,
  };

  static defaultProps = {
    fallback: {},
  };

  state = {
    __features: this.props.features,
    __fallback: this.props.fallback,
    __debug: this.props.debug,
    __mergedFeatures: deepmerge(this.props.fallback, this.props.features),
    __fetchFrom: this.props.fetchFrom
  };

  registerCb = (callback) => {
    const index = this.callbacks.indexOf(callback);
    if (index === -1) {
      this.callbacks.push(callback);
    }
  };

  unregisterCb = (callback) => {
    const index = this.callbacks.indexOf(callback);
    if (index > -1) {
      this.callbacks.splice(index, 1);
    }
  };

  publish = () => {
    this.callbacks.forEach(cb => {
      cb({...this.state})
    })
  };

  getChildContext() {
    return {
      __subscribeToFeatureContext: cb => {
        if (cb) {
          let context = {...this.state};
          cb(context);
          this.registerCb(cb);
        }
      },
      __unsubscribeToFeatureContext: cb => {
        if (cb) {
          this.unregisterCb(cb);
        }
      }
    };
  }

  componentWillReceiveProps(nextProps) {
    if (!deepEqual(nextProps.features, this.props.features)) {
      this.setState({ __features: nextProps.features, __mergedFeatures: deepmerge(this.state.__fallback, nextProps.features) }, this.publish);
    }
    if (!deepEqual(nextProps.fallback, this.props.fallback)) {
      this.setState({ __fallback: nextProps.fallback, __mergedFeatures: deepmerge(nextProps.fallback, this.state.__features) }, this.publish);
    }
    if (nextProps.debug !== this.props.debug) {
      this.setState({ __debug: nextProps.debug }, this.publish);
    }
  }

  render() {
    return this.props.children || null;
  }
}