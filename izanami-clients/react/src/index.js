import React, { Component } from 'react';
import { FeatureProvider } from './features';
import { ExperimentsProvider } from './experiments';
import * as Api from './api';
import { object, bool, string, func, oneOfType } from 'prop-types';
import isFunction from 'lodash/isFunction';
import isString from 'lodash/isString';

export * as Api from './api';
export * from './features';
export * from './experiments';

export class IzanamiProvider extends Component {

  static propTypes = {
    id: string,
    features: object,
    featuresFallback: object,
    experiments: object,
    experimentsFallback: object,
    debug: bool,
    fetchFrom: oneOfType([string, func]),
    fetchData: func,
    fetchHeaders: object,
    loading: func,
  };

  static defaultProps = {
    featuresFallback: {},
    experimentsFallback: {},
    debug: false,
    fetchHeaders: {},
    id: "default",
    loading: () => null
  };

  state = {
    loading: false,
    fetched: {},
    isFetchPending: Boolean(this.props.fetchFrom),
  };

  constructor(args) {
    super(args);
  }

  id = () => {
      return this.props.id ? this.props.id : (isFunction(this.props.fetchFrom) ? this.props.fetchFrom :  "default");
  }

  onDataLoaded = data => {
    this.setState({
      loading: false,
      fetched: {
        features: data.features || this.props.features,
        featuresFallback: data.featuresFallback || this.props.featuresFallback,
        experiments: data.experiments || this.props.experiments,
        experimentsFallback: data.experimentsFallback || this.props.experimentsFallback,
        debug: data.debug || !!this.props.debug,
      }
    })
  };

  componentDidMount() {
    if (this.props.fetchFrom) {
      const id = this.id();
      if (!id) {
        console.error("Id should not be null for IzanamiProvider");
        return;
      }
      this.setState({id});
      if (isFunction(this.props.fetchFrom)) {
          Api.registerFetch(id, this.props.fetchFrom);
      } else if (isString(this.props.fetchFrom)) {
          Api.registerFetch(id, () =>
              fetch(this.props.fetchFrom, {
                  method: 'GET',
                  credentials: 'include',
                  headers: this.props.fetchHeaders,
              })
          );
      }
      Api.register(id, this.onDataLoaded);
      this.setState({loading: true});
      Api.izanamiReload(id, this.props.fetchHeaders).finally(() => {
        this.setState({
          isFetchPending: false
        })
      })
    }
  }

  componentWillUnmount() {
    if (this.state.id) {
      Api.unregister(this.state.id, this.onDataLoaded)
    }
  }

  render() {
    if (this.props.fetchFrom) {
        const { debug, features, featuresFallback, experiments, experimentsFallback } = this.state.fetched;
        const id = this.id();
        return (
          <FeatureProvider isFetchPending={this.state.isFetchPending} id={id} debug={debug} features={features || {}} fallback={featuresFallback} fetchFrom={this.props.fetchFrom}>
            <ExperimentsProvider id={id} debug={debug} experiments={experiments || {}} experimentsFallback={experimentsFallback}>
              {this.props.children}
            </ExperimentsProvider>
          </FeatureProvider>
        );

    } else {
      const { debug, features, featuresFallback, experiments, experimentsFallback, children } = this.props;
      const id = this.id();
      return (
        <FeatureProvider id={id} debug={debug} features={features} fallback={featuresFallback}>
          <ExperimentsProvider id={id} debug={debug} experiments={experiments} experimentsFallback={experimentsFallback}>
            {children}
          </ExperimentsProvider>
        </FeatureProvider>
      );
    }
  }
}
