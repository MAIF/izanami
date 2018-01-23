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

  doLogin = (e) => {
    e.preventDefault();
    e.stopPropagation();
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
        <div className="row">
        <img src="/img/logo.png" className="img-responsive center-block logo" />
        </div>
          <div className="row">
            <div className="col-md-6 col-md-offset-3">
              <div className="jumbotron">
                <h2>Signin and manage your lovelly shows</h2>
                <form onSubmit={this.doLogin}>
                  <div className={`form-group ${this.state.error ? "has-error" : ""}`}>
                    <label htmlFor="email">Email address</label>
                    <input type="email" className="form-control" id="exampleInputEmail1" placeholder="foo@gmail.com"
                           onChange={this.setEmail}/>
                    {this.state.error && <span className="help-block">Error while login</span>}
                  </div>
                  <button type="submit" className="btn btn-lg btn-default pull-right" >Signin</button>
                </form>
              </div>
            </div>
          </div>
        </div>
      )
    }
  }
}
