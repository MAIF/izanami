import 'es6-shim';
import 'whatwg-fetch';
import Symbol from 'es-symbol';
import React, { Component } from 'react';
import { func, string, bool, object, node, oneOfType, arrayOf } from 'prop-types';
import deepEqual from 'deep-equal';
import deepmerge from 'deepmerge';
import { get, isFunction } from 'lodash';
import * as Api from './api'
import Debug from './debug'
import { arrayPathToString, getCleanedArrayPath, getIsActive } from './util'


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

  onContextChange = ({__mergedFeatures, __id, __debug}) => {
    if (__id && this.state.id !== __id ) {
      this.setState({id: __id, debug: __debug, mergedFeatures: __mergedFeatures});
      if(__debug) console.log('[Features] Registering to api for ', __id);
      Api.register(__id , this.onFeaturesChanged);
    } else {
      this.setState({debug: __debug, mergedFeatures: __mergedFeatures});
    }
  };

  componentDidMount() {
    this.context.__subscribeToFeatureContext(this.onContextChange);
  }

  componentWillUnmount() {
    const id = this.state.id;
    if (id) {
      Api.unregister(id, this.onFeaturesChanged)
    }
    this.context.__unsubscribeToFeatureContext(this.onContextChange);
  }

  onFeaturesChanged = ({features}) => {
    if (!deepEqual(this.state.features, features)) {
      this.setState({features});
    }
  };

  render() {
    const children = this.props.children;
    const features = deepmerge(this.state.mergedFeatures, this.state.features);
    const arrayPath = getCleanedArrayPath(this.props.path);
    const isActive = getIsActive(features, arrayPath);
    const path = arrayPathToString(arrayPath)
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
    if (isActive && (enabledChildren.length > 0 || disabledChildren.length > 0)) {
      if (debug) console.log(`[Features] feature '${path}' is enabled, rendering first <Enabled /> component`);
      if (debug) {
        return (
          <Debug isActive={ isActive } path={ path }>
            {enabledChildren[0]}
          </Debug>
        );
      }
      return enabledChildren[0];
    } else if (!isActive && (enabledChildren.length > 0 || disabledChildren.length > 0)) {
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
    id: string,
    debug: bool,
  };

  static defaultProps = {
    fallback: {},
  };

  state = {
    __id: this.props.id,
    __features: this.props.features,
    __fallback: this.props.fallback,
    __debug: this.props.debug,
    __mergedFeatures: deepmerge(this.props.fallback, this.props.features)
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