import React, { Component } from 'react';
import * as IzanamiServices from "../services/index";

class Metric extends Component {

  render() {
    const props = this.props;
    return (
      <div className="metric" style={{
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        alignItems: 'center',
        borderRadius: 4,
        backgroundColor: 'rgb(73, 73, 72)',
        width: props.width || 400,
        height: 100,
        margin: 10,
        fontFamily: 'Roboto',
        fontSize: 23,
        fontWeight: 'bold',
        color: 'white' }}>
        <div style={{ zIndex: 103, position: 'absolute', display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center' }}>
          <span style={{ textShadow: 'rgb(0, 0, 0) 1px 1px, rgb(0, 0, 0) -1px 1px, rgb(0, 0, 0) -1px -1px, rgb(0, 0, 0) 1px -1px' }}>{props.value}</span>
          <span style={{ textShadow: 'rgb(0, 0, 0) 1px 1px, rgb(0, 0, 0) -1px 1px, rgb(0, 0, 0) -1px -1px, rgb(0, 0, 0) 1px -1px', fontSize: 22, fontWeight: 'normal' }}>
            <i className={this.props.picto} /> {props.legend}
          </span>
        </div>
      </div>
    );
  }
}

export class HomePage extends Component {

  state = {
    configsCount: '--',
    featuresCount: '--',
    webHooksCount: '--',
    notificationsCount: '--',
    updatesCount: '--',
    experimentsCount: '--',
  };

  componentDidMount() {
    this.props.setTitle("");
    this.update();
  }

  update = () => {
    IzanamiServices.fetchConfigsCount().then(configsCount => {
      IzanamiServices.fetchFeaturesCount().then(featuresCount => {
        IzanamiServices.fetchWebHooksCount().then(webHooksCount => {
          IzanamiServices.fetchExperimentsCount().then(experimentsCount => {
            this.setState({ configsCount, featuresCount, webHooksCount, experimentsCount }, () => {
              setTimeout(this.update, 60000);
            })
          })
        })
      })
    })
  };

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <div className="col-md-offset-5">
            <img className="logo_izanami_dashboard" src={`${window.__contextPath}/assets/images/izanami.png`}/>
          </div>
        </div>
        <div style={{ marginTop: 80 }}>
          <div style={{ display: 'flex', justifyContent: 'center', flexWrap: 'wrap', marginTop: 20 }}>
            <Metric value={this.state.configsCount}  legend="Registered Configurations" picto={"fa fa-wrench"} />
            <Metric value={this.state.featuresCount} legend="Registered Features" picto={"fa fa-toggle-on"} />
          </div>
          <div style={{ display: 'flex', justifyContent: 'center', flexWrap: 'wrap', marginTop: 20 }}>
            <Metric value={this.state.experimentsCount} legend="Registered Experiments" picto={"fa fa-flask"}/>
            <Metric value={this.state.webHooksCount} legend="Registered WebHooks" picto={"fa fa-plug"}/>
          </div>
        </div>
      </div>
    );
  }
}