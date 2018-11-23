import 'es6-shim';
import 'whatwg-fetch';
import Symbol from 'es-symbol';
import React, { Component } from 'react';
import { string, object, bool, func } from 'prop-types';
import deepEqual from 'deep-equal';
import deepmerge from 'deepmerge';
import get from 'lodash/get';
import * as Api from './api';

if (!window.Symbol) {
    window.Symbol = Symbol;
}

export class Variant extends Component {
  static propTypes = {
    id: string.isRequired,
  };
  render() {
    return this.props.children;
  }
}

export class Won extends Component {

  static propTypes = {
    path: string.isRequired,
    notifyWon: string.isRequired,
    notifyWonHeaders: object.isRequired,
  };

  componentDidMount() {
    if (this.props.notifyWon && !this.mounted) {
      this.mounted = true;
      const notifyWon = this.props.notifyWon;
      const url = notifyWon.indexOf('experiment=') > -1 ? notifyWon : (notifyWon + '?experiment=' + this.props.path);
      fetch(url, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ experiment: this.props.path })
      }).then(r => r.json());
    }
  }

  componentWillUnmount() {
    this.mounted = false;
  }

  render() {
    return this.props.children;
  }
}

export class Experiment extends Component {

  static contextTypes = {
    __subscribeToExperimentContext: func,
    __unsubscribeToExperimentContext: func,
  };

  static propTypes = {
    path: string.isRequired,
    default: string,
    debug: bool,
    notifyDisplay: string,
    notifyDisplayHeaders: object,
  };

  static defaultProps = {
    debug: false,
    notifyDisplayHeaders: {},
  };

  state = {
    experiments: {},
    mergedExperiments: {}
  };

  onExperimentsChanged = ({experiments}) => {
    if (!deepEqual(this.state.experiments, experiments)) {
      this.setState({experiments});
    }
  };

  onContextChange = ({__mergedExperiments, __id, __debug}) => {
    if (__id && this.state.id !== __id ) {
      if(__debug) console.log('[Experiments] Registering to api for ', __fetchFrom);
      this.setState({id: __id, debug: __debug, mergedExperiments: __mergedExperiments});
      Api.register(__id, this.onExperimentsChanged)
    } else {
      this.setState({debug: __debug, mergedExperiments: __mergedExperiments});
    }
  };

  componentDidMount() {
    this.context.__subscribeToExperimentContext(this.onContextChange);
    if (this.props.notifyDisplay && !this.mounted) {
      this.mounted = true;
      const notifyDisplay = this.props.notifyDisplay;
      const url = notifyDisplay.indexOf('experiment=') > -1 ? notifyDisplay : (notifyDisplay + '?experiment=' + this.props.path);
      fetch(url, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ experiment: this.props.path })
      }).then(r => r.json());
    }
  }

  componentWillUnmount() {
    this.mounted = false;
    const fetchFrom = this.state.fetchFrom;
    if (fetchFrom) {
      Api.unregister(fetchFrom, this.onExperimentsChanged)
    }
    this.context.__unsubscribeToExperimentContext(this.onContextChange);
  }

  render() {
    const children = this.props.children;
    const path = this.props.path.replace(/:/g, '.');
    const experiments = deepmerge(this.state.mergedExperiments, this.state.experiments);
    let experiment = (get(experiments, path) || { variant: null });
    const value = experiment.variant || this.props.default;

    const childrenArray = Array.isArray(children) ? children : [children];
    const variantChildren = childrenArray.filter(c => c.type === Variant).filter(c => c.props.id === value);
    const debug = !!this.state.debug || this.props.debug;
    if (variantChildren.length === 0) {
      if (debug) console.log(`[Experiments] experiment '${path}' has no valid Variant ${value}. Please provide one.`);
      return null;
    } else {
      const variant = variantChildren[0] || null;
      if (debug) console.log(`[Experiments] experiment '${path}' (${JSON.stringify(experiment)}) has variant ${value}`);
      if (variant && debug) {
        const color = '#' + (~~(Math.random()*(1<<24))).toString(16);
        return (
          <div className="izanami-experiment" title={`Experiment ${path}: variant is ${value}`} style={{ position: 'relative', outline: '1px solid ' + color }}>
            <span style={{ padding: 2, opacity: '0.9', fontFamily: 'Arial', color: 'white', border: '1px solid black',  borderRadius: '5px', backgroundColor: color, position: 'absolute', top: -17, left: -1, zIndex: 100000, boxShadow: '0 4px 8px 0 rgba(0, 0, 0, 0.3), 0 6px 20px 0 rgba(0, 0, 0, 0.19)'}}>
              Experiment <span style={{ fontWeight: 'bold' }}>{path}</span>: variant is <span style={{ fontWeight: 'bold' }}>{value}</span>
            </span>
            {variant}
          </div>
        );
      }
      return variant;
    }
  }
}

export class ExperimentsProvider extends Component {

  callbacks = [];

  static childContextTypes = {
    __subscribeToExperimentContext: func,
    __unsubscribeToExperimentContext: func
  };

  static propTypes = {
    experiments: object.isRequired,
    fallback: object,
    id: string,
    debug: bool,
  };

  static defaultProps = {
    fallback: {},
  };

  state = {
    __experiments: this.props.experiments,
    __fallback: this.props.fallback,
    __mergedExperiments: deepmerge(this.props.fallback, this.props.experiments),
    __id: this.props.id,
    __debug: this.props.debug,
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
      __subscribeToExperimentContext: cb => {
        if (cb) {
          cb({...this.state});
          this.registerCb(cb);
        }
      },
      __unsubscribeToExperimentContext: cb => {
        if (cb) {
          this.unregisterCb(cb);
        }
      }
    };
  }

  componentWillReceiveProps(nextProps) {
    if (!deepEqual(nextProps.experiments, this.props.experiments)) {
      this.setState({ __experiments: nextProps.experiments, __mergedExperiments: deepmerge(this.state.__fallback, nextProps.experiments) }, this.publish);
    }
    if (!deepEqual(nextProps.fallback, this.props.fallback)) {
      this.setState({ __fallback: nextProps.fallback, __mergedExperiments: deepmerge(nextProps.fallback, this.state.__experiments) }, this.publish);
    }
    if (nextProps.debug !== this.props.debug) {
      this.setState({ __debug: nextProps.debug }, this.publish);
    }
  }

  render() {
    return this.props.children || null;
  }
}
