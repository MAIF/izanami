import 'es6-shim';
import 'whatwg-fetch';
import Symbol from 'es-symbol';
import $ from 'jquery';
import { BrowserRouter as Router, Route, Link, Switch, Redirect } from 'react-router-dom';
import * as Service from './services';
import {IzanamiProvider, Feature, Enabled, Disabled, Experiment, Variant, Api as IzanamiApi} from 'izanami';
import './styles.scss'

if (!window.Symbol) {
  window.Symbol = Symbol;
}
window.$ = $;
window.jQuery = $;

require('bootstrap/dist/js/bootstrap.min');

Array.prototype.flatMap = function (lambda) {
  return Array.prototype.concat.apply([], this.map(lambda));
};

import React from 'react';
import ReactDOM from 'react-dom';



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
          <a className="navbar-brand" href="#">Todo list</a>
        </div>
        <div id="navbar" className="navbar-collapse collapse">
          <ul className="nav navbar-nav">
            <li><Link to={"/"}><i className="fa fa-list-ul" aria-hidden="true"></i> Lists</Link></li>
          </ul>
          <ul className="nav navbar-nav navbar-right">
            <li><Link to={"/login"} onClick={ e => Service.logout()}>{props.user} <span className="glyphicon glyphicon-off"></span></Link></li>
          </ul>
        </div>
      </div>
    </nav>
    {props.children}
  </div>
)


class TodoLists extends React.Component {

  state = {
    todoLists: []
  };

  componentDidMount() {
    this.load()
  }

  load = () => {
    Service.todoLists()
      .then(todoLists => {
        this.setState({todoLists, name: ''});
      })
  };

  createTodoList = () => {
    const {name} = this.state;
    const {user} = this.props;
    Service.createTodoList({name, user})
      .then(__ => this.load());
  };

  removeTodoList = name => (e) => {
    if (name) {
      Service.notifyWon("izanami:example:button");
      Service.removeTodoList(name)
        .then(__ => this.load());
    }
  };

  onTodosClick = () => {
    Service.notifyWon("izanami:example:button");
  };

  setName = (e) => {
    const name = e.target.value;
    this.setState({name})
  };

  render() {
    return (
      <Layout user={this.props.user}>
        <div className="row" >
          <div className="col-md-12" >
            <div className="row">
              <div className="col-md-12">
                <h2>Todo Lists</h2>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Created by</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                  <tr>
                    <td><input type="text" className="form-control" placeholder="List name" onChange={this.setName} /></td>
                    <td></td>
                    <td>
                      <button type="button" className="btn btn-default" onClick={this.createTodoList}><i className="fa fa-plus" aria-hidden="true" /></button>
                    </td>
                  </tr>
                  {this.state.todoLists.map(l =>
                    <tr key={`td-${l.name}`}>
                      <td><Link to={`/todos/${l.name}`} >{l.name}</Link></td>
                      <td>{l.user}</td>
                      <td>
                        <Experiment path={"izanami:example:button"} notifyDisplay="/api/izanami/experiments/displayed" >
                          <Variant id={"A"}>
                            <Link to={`/todos/${l.name}`} onClick={this.onTodosClick} className="btn btn-sm btn-default"><i className="fa fa-eye" aria-hidden="true" /></Link>
                            <button className="btn btn-sm btn-default" onClick={this.removeTodoList(l.name)}><i className="glyphicon glyphicon-trash" /></button>
                          </Variant>
                          <Variant id={"B"}>
                            <Link to={`/todos/${l.name}`} onClick={this.onTodosClick} className="btn btn-sm btn-primary"><i className="glyphicon glyphicon-pencil" /></Link>
                            <button className="btn btn-sm btn-primary" onClick={this.removeTodoList(l.name)}><i className="glyphicon glyphicon-trash" /></button>
                          </Variant>
                        </Experiment>
                      </td>
                    </tr>
                  )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </Layout>
    )
  }
}

class Todos extends React.Component {

  state = {
    todoList: {},
    items: [],
    name: ''
  };

  componentDidMount() {
    const { match: { params: name}} = this.props;
    console.log(this.props, name.name);
    Promise.all([
      Service.getTodoList(name.name),
      Service.listItems(name.name)
    ]).then(([todoList, items]) => {
      console.log(items);

      this.setState({todoList, items})
    })
  }

  loadItems = (name) => {
    Service.listItems(name).then(items => {
      this.setState({items})
    })
  };

  loadTodoList = (name) => {
    Service.getTodoList(name).then(todoList => {
      console.log('Todo', todoList);
      this.setState({todoList})
    })
  };

  createItem = () => {
    const {name, description} = this.state;
    Service.addItem(this.state.todoList.name, {name, description, done: false})
      .then(__ => {
        this.setState({name: '', description: ''});
        this.loadItems(this.state.todoList.name)
      });
  };

  removeItem = name => (e) => {
    if (name) {
      Service.removeItem(this.state.todoList.name, name)
        .then(__ => this.loadItems(this.state.todoList.name));
    }
  };

  addEmail = () => {
    let {name, subscriptions} = this.state.todoList;
    Service.updateTodoList(name, {...this.state.todoList, subscriptions: [...subscriptions, this.state.email]})
      .then(__ => this.loadTodoList(name))
  };

  removeEmail = email => () => {
    let {name, subscriptions} = this.state.todoList;
    const {user} = this.props;
    Service.updateTodoList(name, {...this.state.todoList, subscriptions: subscriptions.filter(e => e !== email)})
      .then(__ => this.loadTodoList(name))
  };

  deleteAll = () => {
    let {name} = this.state.todoList;
    Service.removeAllItems(name, true)
      .then(__ => this.loadItems(name))
  };

  setValue = name => (e) => {
    const value = e.target.value;
    this.setState({[name]: value})
  };

  done = item => () => {
    Service.updateItem(this.state.todoList.name, item.name, {...item, done: true})
      .then(__ => this.loadItems(this.state.todoList.name));
  };

  undone = item => () => {
    Service.updateItem(this.state.todoList.name, item.name, {...item, done: false})
      .then(__ => this.loadItems(this.state.todoList.name));
  };

  render() {
    return (
      <Layout user={this.props.user}>
        <div className="row">
          <div className="col-md-12">
            <h2>Todo list "{this.state.todoList.name}"</h2>

            <Feature path="izanami.example.emailNotifications">
              <Enabled>
                <p>You can subscribe to get notified: </p>
                <form className="form-inline">
                  <div className="form-group">
                    { (this.state.todoList.subscriptions || []).map(email =>
                      <span key={`btn-${email}`} className="btn btn-sm btn-default">{email} <button onClick={this.removeEmail(email)} type="button" className="close pull-right" >&times;</button></span>
                    )}
                    <input type="email" className="form-control input-sm" placeholder="foo@gmail.com" onChange={this.setValue('email')} />
                  </div>
                  <button type="button" className="btn btn-sm btn-default" onClick={this.addEmail}><i className="glyphicon glyphicon-ok" /></button>
                </form>
              </Enabled>
              <Disabled>
                <div />
              </Disabled>
            </Feature>
            <h3>Items </h3>
            <table className="table">
              <thead>
              <tr>
                <th>Checked</th>
                <th>Name</th>
                <th>Description</th>
                <th>Actions</th>
              </tr>
              </thead>
              <tbody>
              <tr>
                <td></td>
                <td>
                  <input id="itemName" type="text" className="form-control" placeholder="new Item" value={this.state.name} onChange={this.setValue('name')} />
                </td>
                <td>
                  <input id="itemDescription" type="text" className="form-control" placeholder="Description" value={this.state.description} onChange={this.setValue('description')} />
                </td>
                <td>
                  <button type="button" className="btn btn-default" onClick={this.createItem}><i className="fa fa-plus" aria-hidden="true" /></button>
                </td>
              </tr>
              {this.state.items.map(l =>
                <tr key={`it-${l.name}`} className={ l.done ? 'done'  : '' } >
                  <td><input type="checkbox" checked={l.done} onChange={ e => { e.target.checked ? this.done(l)() : this.undone(l)() } } /></td>
                  <td>{l.name}</td>
                  <td>{l.description}</td>
                  <td>
                    <button className="btn btn-default" onClick={this.removeItem(l.name)}><i className="fa fa-minus" /></button>
                  </td>
                </tr>
              )}
                <Feature path="izanami.example.deleteAll">
                  <Enabled>
                    <tr>
                      <td></td>
                      <td></td>
                      <td></td>
                      <td><button type="button"  className="btn btn-sm btn-default" onClick={this.deleteAll}>Delete done items</button></td>
                    </tr>
                  </Enabled>
                  <Disabled>
                    <tr></tr>
                  </Disabled>
                </Feature>
              </tbody>
            </table>
          </div>
        </div>
      </Layout>
    )
  }
}


class PrivateRoute extends React.Component {

  state = {
    loaded: false,
    userId: null
  };

  componentDidMount() {
    Service.me().then(({email}) => {
      this.setState({
        loaded: true,
        clientId: email
      })
    })
  }

  componentWillReceiveProps(nextProps) {
    // will be true
    const locationChanged = nextProps.location !== this.props.location;
    if (locationChanged) {
      //Reload izanami data on route change
      IzanamiApi.izanamiReload("/api/izanami");
    }
  }

  render() {
    if (this.state.loaded) {
      const { component: Component, ...rest } = this.props;
      return (
        <Route {...rest}  render={props => {
          return (
            this.state.clientId ? (
              <Component user={this.state.clientId} {...props}/>
            ) : (
              <Redirect to={{
                pathname: '/login',
                state: { from: this.props.location }
              }}/>
            )
          )
        }}/>
      );
    } else {
      return <div/>
    }

  }
}

class Login extends React.Component {

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
                <a className="navbar-brand" href="#">Todo list</a>
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
                <h2>Signin and create your todo list</h2>
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

const withprops = (Component, props, props2) => {
  return <Component {...props} {...props2} />
}

const MainApp = props => (

    <Switch>
      <Route exact path="/" component={p => withprops(TodoLists, props, p)} />
      <Route path="/todos/:name" component={p => withprops(Todos, props, p)} />
    </Switch>

);

const IzanamiApp = props => (
  <IzanamiProvider fetchFrom="/api/izanami">
    <Router basename="/">
        <Switch>
          <Route path="/login" component={Login}/>
          <PrivateRoute path="/" component={MainApp}/>
        </Switch>
    </Router>
  </IzanamiProvider>
);

export function init(node) {
  ReactDOM.render(<IzanamiApp />, node);
}