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

export class Variant extends Component {
  static propTypes = {
    id: PropTypes.string.isRequired,
  };
  render() {
    return this.props.children;
  }
}

export class Won extends Component {

  static propTypes = {
    path: PropTypes.string.isRequired,
    notifyWon: PropTypes.string.isRequired,
    notifyWonHeaders: PropTypes.object.isRequired,
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
    __mergedExperiments: PropTypes.object,
    __debug: PropTypes.bool,
  };

  static propTypes = {
    path: PropTypes.string.isRequired,
    default: PropTypes.string,
    debug: PropTypes.bool,
    notifyDisplay: PropTypes.string,
    notifyDisplayHeaders: PropTypes.object,
  };

  static defaultProps = {
    debug: false,
    notifyDisplayHeaders: {},
  };

  state = {
    experiments: {}
  };

  onExperimentsChanged = ({experiments}) => {
    if (!deepEqual(this.state.experiments, experiments)) {
      this.setState({experiments});
    }
  };


  componentDidMount() {
    const fetchFrom = this.context.__fetchFrom;
    if (fetchFrom) {
      Api.register(fetchFrom, this.onExperimentsChanged)
    }
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
    const fetchFrom = this.context.__fetchFrom;
    if (fetchFrom) {
      Api.unregister(fetchFrom, this.onExperimentsChanged)
    }
  }

  render() {
    const children = this.props.children;
    const path = this.props.path.replace(/:/g, '.');
    const experiments = deepmerge(this.context.__mergedExperiments, this.state.experiments);
    let experiment = (_.get(experiments, path) || { variant: null });
    const value = experiment.variant || this.props.default;

    const childrenArray = Array.isArray(children) ? children : [children];
    const variantChildren = childrenArray.filter(c => c.type === Variant).filter(c => c.props.id === value);
    const debug = !!this.context.__debug || this.props.debug;
    if (variantChildren.length === 0) {
      if (debug) console.log(`Experiment '${path}' has no valid Variant ${value}. Please provide one.`);
      return null;
    } else {
      const variant = variantChildren[0] || null;
      if (debug) console.log(`Experiment '${path}' (${JSON.stringify(experiment)}) has variant ${value}`);
      return variant;
    }
  }
}

export class ExperimentsProvider extends Component {

  static childContextTypes = {
    __experiments: PropTypes.object,
    __fallback: PropTypes.object,
    __mergedExperiments: PropTypes.object,
    __debug: PropTypes.bool,
    __fetchFrom: PropTypes.string,
  };

  static propTypes = {
    experiments: PropTypes.object.isRequired,
    fallback: PropTypes.object,
    fetchFrom: PropTypes.string,
    debug: PropTypes.bool,
  };

  static defaultProps = {
    fallback: {},
  };

  state = {
    experiments: this.props.experiments,
    fallback: this.props.fallback,
    debug: this.props.debug,
  };

  getChildContext() {
    return {
      __debug: this.state.debug,
      __experiments: this.state.experiments,
      __fallback: this.state.fallback,
      __mergedExperiments: deepmerge(this.state.fallback, this.state.experiments),
      __fetchFrom: this.props.fetchFrom
    };
  }

  componentWillReceiveProps(nextProps) {
    if (!deepEqual(nextProps.experiments, this.props.experiments)) {
      this.setState({ experiments: nextProps.experiments });
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
