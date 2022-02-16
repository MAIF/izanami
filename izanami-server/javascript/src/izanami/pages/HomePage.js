import React, {Component} from "react";
import * as IzanamiServices from "../services/index";

class Metric extends Component {
  render() {
    const props = this.props;
    return (
      <div
        className="metric"
        style={{
          width: props.width || 400
        }}
      >
        <div>
          <span>
            {props.value}
          </span>
          <span>
            <i className={this.props.picto}/> {props.legend}
          </span>
        </div>
      </div>
    );
  }
}

export class HomePage extends Component {
  state = {
    configsCount: "--",
    featuresCount: "--",
    webHooksCount: "--",
    notificationsCount: "--",
    updatesCount: "--",
    experimentsCount: "--"
  };

  componentDidMount() {
    this.props.setTitle("");
    this.update();
  }

  componentWillUnmount() {
    if (this.timeout) {
      clearTimeout(this.timeout)
      this.timeout = null
      console.log("")
    }
  }

  update = () => {
    Promise.all([IzanamiServices.fetchConfigsCount(),
      IzanamiServices.fetchFeaturesCount(),
      IzanamiServices.fetchWebHooksCount(),
      IzanamiServices.fetchExperimentsCount()])
      .then(([configsCount, featuresCount, webHooksCount, experimentsCount]) => {
        this.setState(
          {configsCount, featuresCount, webHooksCount, experimentsCount},
          () => {
            this.timeout = setTimeout(this.update, 60000);
          })
      })
  };

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <div
            className="d-flex align-items-center justify-content-center">
            <img
              className="logo_izanami_dashboard"
              src={`${window.__contextPath}/assets/images/izanami.png`}
            />
          </div>
        </div>
        <div style={{marginTop: 80}}>
          <div
            className="d-flex align-items-center justify-content-center mt-3">
            <Metric
              value={this.state.configsCount}
              legend="Registered Configurations"
              picto={"fas fa-wrench"}
            />
            <Metric
              value={this.state.featuresCount}
              legend="Registered Features"
              picto={"fas fa-toggle-on"}
            />
          </div>
          <div
            className="d-flex align-items-center justify-content-center mt-3">
            <Metric
              value={this.state.experimentsCount}
              legend="Registered Experiments"
              picto={"fas fa-flask"}
            />
            <Metric
              value={this.state.webHooksCount}
              legend="Registered WebHooks"
              picto={"fas fa-plug"}
            />
          </div>
        </div>
      </div>
    );
  }
}
