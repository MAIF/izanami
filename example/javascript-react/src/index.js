import 'es6-shim';
import 'whatwg-fetch';
import Symbol from 'es-symbol';
import $ from 'jquery';
import {BrowserRouter as Router, Redirect, Route, Routes, useParams, Navigate} from 'react-router-dom';
import * as Service from './services';
import {Api as IzanamiApi, IzanamiProvider} from 'react-izanami';
import './styles.scss'
import React from 'react';
import ReactDOM from 'react-dom';
import MyTvShows from './pages/MyTvshows';
import TvShow from './pages/TvShow';
import Login from './pages/Login';

if (!window.Symbol) {
  window.Symbol = Symbol;
}
window.$ = $;
window.jQuery = $;

require('bootstrap/dist/js/bootstrap.min');

Array.prototype.flatMap = function (lambda) {
  return Array.prototype.concat.apply([], this.map(lambda));
};

function PrivateRoute(props) {
  const params = useParams();
  return (<InternalPrivateRoute {...props} params={params} />)
}

class InternalPrivateRoute extends React.Component {

  state = {
    loaded: false,
    user: null
  };

  componentDidMount() {
    Service.me().then(this.onUserChange);
    Service.onUserChange(this.onUserChange);
  }

  componentWillUnmount() {
    Service.unregister(this.onUserChange);
  }

  onUserChange = user => {
    this.setState({
      loaded: true,
      user
    })
  };

  componentDidUpdate(nextProps) {
    // will be true
    const locationChanged = nextProps.location !== this.props.location;
    if (locationChanged) {
      //Reload izanami data on route change
      console.log("Izanami reload")
      IzanamiApi.izanamiReload("mytvshows");
    }
  }

  render() {
    if (this.state.loaded) {
      const { component: Component} = this.props;
        return (
          this.state.user ? (
            <Component user={this.state.user} rootPath= {this.props.rootPath} params={this.props.params} />
          ) : (
            <Navigate to="/login" replace/>
          )
        )
    } else {
      return <div/>
    }
  }
}

class IzanamiApp extends React.PureComponent {
  render() {
    return (<IzanamiProvider id="mytvshows" fetchFrom={() =>
      fetch("/api/izanami", {
        method: 'GET',
        credentials: 'include'
      })
    }>
      <Router basename="/">
        <Routes>
          <Route path="/login" element={<Login />} rootPath={this.props.rootPath}/>
          <Route exact path="/" element={<PrivateRoute rootPath={this.props.rootPath} component={MyTvShows} />} />
          <Route path="/tvshow/:id" element={<PrivateRoute rootPath={this.props.rootPath} component={TvShow}/>} />
        </Routes>
      </Router>
    </IzanamiProvider>)
  }
}

window._basePath = window.__rootPath || "/assets";

export function init(node, rootPath) {
  ReactDOM.render(<IzanamiApp rootPath={rootPath || '/assets/'}/>, node);
}