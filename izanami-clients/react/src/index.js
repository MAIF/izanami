import React, { Component } from 'react';
import { FeatureProvider } from './features';
import { ExperimentsProvider } from './experiments';
import * as Api from './api'
import PropTypes from 'prop-types';

export * as Api from './api';
export * from './features';
export * from './experiments';

export class IzanamiProvider extends Component {

  static propTypes = {
    features: PropTypes.object,
    featuresFallback: PropTypes.object,
    experiments: PropTypes.object,
    experimentsFallback: PropTypes.object,
    debug: PropTypes.bool,
    fetchFrom: PropTypes.string,
    fetchData: PropTypes.func,
    fetchHeaders: PropTypes.object,
    loading: PropTypes.func,
  };

  static defaultProps = {
    featuresFallback: {},
    experimentsFallback: {},
    debug: false,
    fetchHeaders: {},
    loading: () => null
  };

  state = {
    loading: false,
    fetched: {},
  };

  constructor(args) {
    super(args);
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
      console.log(this.featuresCallbacks);
      Api.register(this.props.fetchFrom, this.onDataLoaded);
      this.setState({loading: true});
      Api.izanamiReload(this.props.fetchFrom, this.props.fetchHeaders);
    }
  }

  componentWillUnmount() {
    if (this.props.fetchFrom) {
      Api.unregister(this.props.fetchFrom, this.onDataLoaded)
    }
  }

  render() {
    if (this.props.fetchFrom) {
        const { debug, features, featuresFallback, experiments, experimentsFallback } = this.state.fetched;
        console.log('Refreshing', features);
        return (
          <FeatureProvider debug={debug} features={features || {}} fallback={featuresFallback} fetchFrom={this.props.fetchFrom}>
            <ExperimentsProvider debug={debug} experiments={experiments || {}} experimentsFallback={experimentsFallback}>
              {this.props.children}
            </ExperimentsProvider>
          </FeatureProvider>
        );

    } else {
      const { debug, features, featuresFallback, experiments, experimentsFallback, children } = this.props;
      return (
        <FeatureProvider debug={debug} features={features} fallback={featuresFallback}>
          <ExperimentsProvider debug={debug} experiments={experiments} experimentsFallback={experimentsFallback}>
            {children}
          </ExperimentsProvider>
        </FeatureProvider>
      );
    }
  }
}
