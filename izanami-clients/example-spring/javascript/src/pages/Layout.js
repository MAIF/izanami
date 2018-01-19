import React from "react";
import * as Service from "../services";
import { BrowserRouter as Router, Route, Link, Switch, Redirect } from 'react-router-dom';

const Layout = props => (
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
            <li><Link to={"/"}><i className="fa fa-list-ul" aria-hidden="true"></i> My shows</Link></li>
          </ul>
          <ul className="nav navbar-nav navbar-right">
            <li><Link to={"/login"} onClick={ e => Service.logout()}>{props.user.userId} <span className="glyphicon glyphicon-off"></span></Link></li>
          </ul>
        </div>
      </div>
    </nav>
    {props.children}
  </div>
);

export default Layout;

