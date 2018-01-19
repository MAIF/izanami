import * as Service from "../services";
import React from "react";
import {Api as IzanamiApi} from 'react-izanami';
import { Redirect } from 'react-router-dom';

export default class Login extends React.Component {

  state = {
    error: false,
    ok: false
  };

  setEmail = e => {
    this.setState({email: e.target.value})
  };

  doLogin = () => {
    const {email} = this.state;
    Service.login({email})
      .then(r => {
        if (r.status === 200) {
          this.setState({error: false, ok: true});
          IzanamiApi.izanamiReload("/api/izanami");
        } else {
          this.setState({error: true, ok: false})
        }
      })
  };

  render() {
    if (this.state.ok) {
      return (
        <Redirect to={{
          pathname: '/',
          state: {from: this.props.location}
        }}/>
      )
    } else {
      return (
        <div className="container">
          <nav className="navbar navbar-default">
            <div className="container-fluid">
              <div className="navbar-header">
                <button type="button" className="navbar-toggle collapsed" data-toggle="collapse" data-target="#navbar" aria-expanded="false" aria-controls="navbar">
                  <span className="sr-only">Toggle navigation</span>
                  <span className="icon-bar"></span>
                  <span className="icon-bar"></span>
                  <span className="icon-bar"></span>
                </button>
                <a className="navbar-brand" href="#">My Tv Shows</a>
              </div>
              <div id="navbar" className="navbar-collapse collapse">
                <ul className="nav navbar-nav">
                </ul>
              </div>
            </div>
          </nav>

          <div className="row">
            <div className="col-md-6 col-md-offset-3">
              <div className="jumbotron">
                <h2>Signin and manage your lovelly shows</h2>
                <form>
                  <div className={`form-group ${this.state.error ? "has-error" : ""}`}>
                    <label htmlFor="email">Email address</label>
                    <input type="email" className="form-control" id="exampleInputEmail1" placeholder="foo@gmail.com"
                           onChange={this.setEmail}/>
                    {this.state.error && <span className="help-block">Error while login</span>}
                  </div>
                  <button type="button" className="btn btn-lg btn-default" onClick={this.doLogin}>Signin</button>
                </form>
              </div>
            </div>
          </div>
        </div>
      )
    }
  }
}