import React, {Component} from 'react';
import PropTypes from 'prop-types';
import {Form, Alerts} from '.';
import _ from 'lodash';
import {createTooltip} from './tooltips';
import {SweetModal} from './SweetModal';
import * as Events from '../services/events';

import ReactTable from 'react-table';

function LoadingComponent(props) {
  return (
    <div
      className="loadingPage"
      style={{
        display:
          props.loading && props.loadingText && props.loadingText.trim().length > 0
            ? 'flex'
            : 'none',
      }}>
      {props.loadingText}
    </div>
  );
}

export class Table extends Component {
  static propTypes = {
    parentProps: PropTypes.object,
    user: PropTypes.object.isRequired,
    defaultTitle: PropTypes.string,
    selfUrl: PropTypes.string,
    backToUrl: PropTypes.string,
    itemName: PropTypes.string.isRequired,
    columns: PropTypes.array.isRequired,
    fetchItems: PropTypes.func.isRequired,
    fetchItem: PropTypes.func.isRequired,
    updateItem: PropTypes.func,
    deleteItem: PropTypes.func,
    createItem: PropTypes.func,
    navigateTo: PropTypes.func,
    showActions: PropTypes.bool.isRequired,
    showLink: PropTypes.bool.isRequired,
    formSchema: PropTypes.object,
    formFlow: PropTypes.array,
    extractKey: PropTypes.func.isRequired,
    defaultValue: PropTypes.func,
    rowNavigation: PropTypes.bool.isRequired,
    downloadLinks: PropTypes.array,
    uploadLinks: PropTypes.array,
    onEvent: PropTypes.func,
    eventNames: PropTypes.object,
    compareItem: PropTypes.func,
    convertItem: PropTypes.func,
  };

  static defaultProps = {
    rowNavigation: false,
    pageSize: 20,
    firstSort: null,
    convertItem: e => e,
    compareItem: (a, b) => _.isEqual(a, b),
  };

  state = {
    items: [],
    currentItem: null,
    currentItemOriginal: null,
    showAddForm: false,
    showEditForm: false,
    searched: '',
    defaultFiltered: [],
    possiblePages: 50,
    order: this.props.firstSort,
    orderDir: true,
    loading: false,
    error: false,
    errorList: [],
    nbPages: 1,
    justUpdated: false,
    confirmDelete: false,
    toDelete: null
  };

  lastSearchArgs = {};

  componentDidMount() {
    this.update().then(() => {
      if (this.props.search) {
        this.search({target: {value: this.props.search}});
      }
    });
    this.readRoute();

    if (this.props.eventNames) {
      Events.addCallback(this.handleEvent)
    }
  }

  handleEvent = e => {
    let items;
    switch (e.type) {
      case this.props.eventNames.created:
        if (this.state.items.find(i => this.props.compareItem(i, e.payload))) {
          items = this.state.items;
        } else {
          items = [e.payload, ...this.state.items].splice(0, this.props.pageSize);
        }
        break;
      case this.props.eventNames.updated:
        items = this.state.items.map(i => {
          if (this.props.compareItem(i, e.oldValue)) {
            return e.payload;
          } else {
            return i;
          }
        });
        break;
      case this.props.eventNames.deleted:
        items = this.state.items.filter(i => !this.props.compareItem(i, e.oldValue));
        break;
      default:
        items = this.state.items;
    }

    this.setState({items, justUpdated: true});
  };

  componentWillUnmount() {
    this.unmountShortcuts();
    if (this.props.eventNames) {
      Events.removeCallback(this.handleEvent)
    }
  }

  readRoute = () => {
    if (this.props.parentProps.params.taction) {
      const action = this.props.parentProps.params.taction;
      if (action === 'add') {
        this.showAddForm();
      } else if (action === 'edit') {
        const item = this.props.parentProps.params.titem;
        this.props.fetchItem(item).then(data => {
          this.showEditForm(null, data);
        });
      }
    }
    if (this.props.parentProps.location.query && this.props.parentProps.location.query.search) {
      const searched = this.props.parentProps.location.query.search;
      const defaultFiltered = this.props.columns.filter(c => !c.notFilterable).map(c => ({id: c.title, value: searched}));
      this.setState({ defaultFiltered });
    }
  };

  mountShortcuts = () => {
    document.body.addEventListener('keydown', this.saveShortcut);
  };

  unmountShortcuts = () => {
    document.body.removeEventListener('keydown', this.saveShortcut);
  };

  saveShortcut = e => {
    if (e.keyCode === 83 && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      if (this.state.showEditForm) {
        this.updateItem();
      }
      if (this.state.showAddForm) {
        this.createItem();
      }
    }
  };

  onFetchData = (initialArgs = {}) => {
    const {filtered, pageSize, page} = initialArgs;
    const args = {filtered, pageSize, page};
    if (!_.isEqual(this.lastSearchArgs, args)) {
      this.lastSearchArgs = args;
      this.update(initialArgs);
    }
  };

  update = (initialArgs = {}) => {
    this.setState({loading: true});
    const args = {
      search: initialArgs.filtered,
      pageSize: initialArgs.pageSize || this.props.pageSize,
      page: initialArgs.page ? initialArgs.page + 1 : 1
    }
    return this.props.fetchItems(args).then(
      ({nbPages, results}) => {
        this.setState({ items: results || [], nbPages: nbPages == 0 ? 1 : nbPages, loading: false});
      },
      () => this.setState({loading: false})
    );
  };

  gotoItem = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.navigateTo(item);
  };

  closeAddForm = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.unmountShortcuts();
    this.props.parentProps.setTitle(this.props.defaultTitle);
    this.setState({currentItem: null, showAddForm: false, error: false, errorList: []});
    this.props.backToUrl ? window.history.pushState({}, '', `/${this.props.backToUrl}`) : window.history.back();
  };

  showAddForm = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.mountShortcuts();
    this.props.parentProps.setTitle(`Create a new ${this.props.itemName}`);
    window.history.pushState({}, '', `/${this.props.selfUrl}/add`);
    this.setState({currentItem: this.props.defaultValue(), currentItemOriginal: this.props.defaultValue(), showAddForm: true, error: false, errorList: []});
  };

  closeEditForm = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.unmountShortcuts();
    this.props.parentProps.setTitle(this.props.defaultTitle);
    this.setState({currentItem: null, currentItemOriginal: null, showEditForm: false, error: false, errorList: []});
    this.props.backToUrl ? window.history.pushState({}, '', `/${this.props.backToUrl}`) : window.history.back();
  };

  showEditForm = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    this.mountShortcuts();
    window.history.pushState(
      {},
      '',
      `/${this.props.selfUrl}/edit/${this.props.extractKey(item)}`
    );
    this.props.parentProps.setTitle(`Update a ${this.props.itemName}`);
    const currentItem = this.props.convertItem(item);
    this.setState({currentItem, currentItemOriginal: currentItem, showEditForm: true, error: false, errorList: []});
  };

  deleteItem = (e, item, i) => {
    if (e && e.preventDefault) e.preventDefault();
    //if (confirm('Are you sure you want to delete that item ?')) {
      this.props
        .deleteItem(item)
        .then(__ => {
          const items = this.state.items.filter(i => !_.isEqual(i, item));
          this.setState({
            items,
            showEditForm: false,
            showAddForm: false,
            confirmDelete: false,
            confirmDeleteTable: false,
            toDelete: null
          });
          this.props.parentProps.setTitle(this.props.defaultTitle);
          this.props.backToUrl ? window.history.pushState({}, '', `/${this.props.backToUrl}`) : window.history.back();
        });
    //}
  };

  createItem = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.parentProps.setTitle(this.props.defaultTitle);

    this.setState({error: false, errorList: []});

    this.props
      .createItem(this.state.currentItem)
      .then(res => {
        if (res.status === 201 ) {
          this.setState({currentItem: null, currentItemOriginal: null, showAddForm: false});
          return res.json().then(elt => ({
              data: elt,
              error: false
          }));
        } else {
          return res.json().then(errorList => {
            return {
              error: true,
              errorList
            }
          });
        }
      })
      .then(res => {
        if (res.error) {
          this.setState({error: true, errorList: this.buildErrorList(res.errorList)});
        } else {
          let items;
          if (this.state.items.find(i => _.isEqual(i, res.data))) {
            items = [...this.state.items];
          } else {
            items = [res.data, ...this.state.items].splice(0, this.props.pageSize);
          }
          this.setState({error: false, items, justUpdated: true});
          this.props.backToUrl ? window.history.pushState({}, '', `/${this.props.backToUrl}`) : window.history.back();
        }
      });
  };

  updateItem = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.parentProps.setTitle(this.props.defaultTitle);

    this.setState({error: false, errorList: []});

    const currentItem = this.state.currentItem;
    const currentItemOriginal = this.state.currentItemOriginal;
    this.props
      .updateItem(currentItem, currentItemOriginal)
      .then(res => {
        if (res.status === 200 ) {
          return res.json().then(elt => ({
            data: elt,
            error: false
          }));
        } else {
          return res.json().then(j => {
            return {
              error: true,
              errorList: j
            }
          });
        }
      })
      .then(res => {
        if (res.error) {
          this.setState({error: true, errorList: this.buildErrorList(res.errorList)});
        } else {
          const items = this.state.items.map(i => {
            if (_.isEqual(i, currentItemOriginal)) {
              return res.data;
            } else {
              return i;
            }
          });
          this.setState({items, showEditForm: false, justUpdated: true});
          this.props.backToUrl ? window.history.pushState({}, '', `/${this.props.backToUrl}`) : window.history.back();
        }
      });
  };

  uploadFile = link => e => {
    const upload = e => {
      fetch(link, {
        method: 'POST',
        credentials: 'include',
        headers: {
          "Content-Type": "application/nd-json"
        },
        body: e.currentTarget.result
      })
      .then(res => {
        if (res.status === 200 ) {
          return res.json().then(({success}) => {
            this.setState({successMessages: [{message:'file.import.success', args: [success]}]});
          });
        } else {
          return res.json().then(({errors}) => {
            this.setState({error: true, errorList: this.buildErrorList(errors)});
          });
        }
      });
    };

    const reader = new FileReader();
    reader.onload = upload;
    reader.readAsText(e.target.files[0]);
  };

  buildErrorList = ({errors = {}, fieldErrors = {}}) => {
    const errorsOnFields = Object.keys(fieldErrors)
      .flatMap(k => {
        const messages = fieldErrors[k] || [];
        return messages.map(({message = "", args = []}) =>
          ({message: `${k}.${message}`, args})
        )
      });
    return [...errors, ...errorsOnFields];
  };

  render() {
    console.log('State', this.state);
    const columns = this.props.columns.map(c => ({
        Header: c.title,
        id: c.title,
        headerStyle: c.style,
        width: c.style && c.style.width ? c.style.width : undefined,
        style: { height: 30, ...c.style },
        sortable: !c.notSortable,
        filterable: !c.notFilterable,
        accessor: d => (c.content ? c.content(d) : d),
        Filter: d => (
          <input
            type="text"
            className="form-control input-sm"
            value={d.filter ? d.filter.value : ''}
            onChange={e => d.onChange(e.target.value)}
            placeholder="Search ..."
          />
        ),
        Cell: r => {
          const value = r.value;
          const original = r.original;
          return c.cell ? (
            c.cell(value, original, this)
          ) : (
            <div
              onClick={e => {
                if (this.props.rowNavigation) {
                  this.gotoItem(e, original);
                }
              }}
              style={{ cursor: 'pointer', width: '100%' }}>
              {value}
            </div>
          );
        }
      }
    ));
    if (this.props.showActions) {
      columns.push({
        Header: 'Actions',
        id: 'actions',
        width: 140,
        style: { textAlign: 'center' },
        filterable: false,
        accessor: (item, ___, index) => (
          <div style={{ width: 140, textAlign: 'center' }}>
            <div className="displayGroupBtn">
              <button
                type="button"
                className="btn btn-sm btn-success"
                {...createTooltip(`Edit this ${this.props.itemName}`, 'top', true)}
                onClick={e => this.showEditForm(e, item)}>
                <i className="glyphicon glyphicon-pencil" />
              </button>
              {this.props.showLink && (
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  {...createTooltip(
                    `Open this ${this.props.itemName} in a new window`,
                    'top',
                    true
                  )}
                  onClick={e => this.gotoItem(e, item)}>
                  <i className="glyphicon glyphicon-link" />
                </button>
              )}
              <button
                type="button"
                className="btn btn-sm btn-danger"
                {...createTooltip(`Delete this ${this.props.itemName}`, 'top', true)}
                onClick={e => this.setState({confirmDeleteTable: true, toDelete: item})}>
                <i className="glyphicon glyphicon-trash" />
              </button>

            </div>
          </div>
        ),
      });
    }
    return (
      <div>
          {!this.state.showEditForm &&
          !this.state.showAddForm && (
            <div>
              <div className="row" style={{ marginBottom: 10 }}>
                <div className="col-md-12">
                  {this.state.error && <Alerts display={this.state.error} messages={this.state.errorList}/>}
                  {this.state.successMessages &&
                    this.state.successMessages.length > 0 &&
                     <Alerts type="success" display={true} messages={this.state.successMessages} onClose={() => this.setState({successMessages:[]})}/>
                  }
                </div>
              </div>
              <div className="row" style={{ marginBottom: 10 }}>
                <div className="col-md-12">
                  <button
                    type="button"
                    className="btn btn-primary"
                    {...createTooltip('Reload the current table')}
                    onClick={this.update}>
                    <span className="glyphicon glyphicon-refresh" />
                  </button>
                  {this.props.showActions && (
                    <button
                      type="button"
                      className="btn btn-primary"
                      style={{ marginLeft: 10 }}
                      onClick={this.showAddForm}
                      {...createTooltip(`Create a new ${this.props.itemName}`)}>
                      <span className="glyphicon glyphicon-plus-sign" /> Add item
                    </button>
                  )}
                  {this.props.showActions && this.props.user.admin &&
                    <div className="dropdown" style={{display: 'inline-block', marginLeft: 10}}>
                        <button
                          className="dropdown-toggle btn-dropdown"
                          data-toggle="dropdown"
                          type="button"
                          aria-haspopup="true"
                          aria-expanded="false"
                          style={{display: 'inline-block'}}
                        >
                          <i className="fa fa-cog fa-2" aria-hidden="true"/>
                        </button>
                        <ul className="dropdown-menu">
                          {
                            this.props.downloadLinks && this.props.downloadLinks.map(({title, link}, i) =>
                              <li key={`download-${i}`}>
                                <a
                                  href={link}
                                  {...createTooltip(`${title}`)}>
                                  <span className="glyphicon glyphicon-download" /> {title}
                                </a>
                              </li>
                            )}
                            {this.props.uploadLinks && this.props.uploadLinks.map(({title, link}, i) =>
                              <li key={`upload-${i}`}>
                                <a
                                  style={{cursor: 'pointer'}}
                                  onClick={e => {
                                    document.getElementById(`upload${i}`).click()
                                  }}
                                >
                                    <span className="glyphicon glyphicon-upload" /> {title}
                                    <input
                                      id={`upload${i}`}
                                      type="file"
                                      style={{ display:'none' }}
                                      onChange={this.uploadFile(link)}
                                      {...createTooltip(`${title}`)} />
                                </a>
                              </li>
                            )}
                          <li>
                          </li>
                        </ul>
                    </div>
                  }
                </div>
              </div>
              <div className="rrow">
                <ReactTable
                  className="fulltable -striped -highlight"
                  data={this.state.items}
                  loading={this.state.loading}
                  sortable={true}
                  filterable={true}
                  filterAll={true}
                  defaultSorted={[{ id: this.props.columns[0].title, desc: false }]}
                  manual
                  pages={this.state.nbPages}
                  defaultPageSize={this.props.pageSize}
                  columns={columns}
                  LoadingComponent={LoadingComponent}
                  onFetchData={this.onFetchData}
                  defaultFiltered={this.state.defaultFiltered}
                />
              </div>
            </div>
        )}

        {this.state.showAddForm &&
        <div className="" role="dialog">
          <Form
            value={this.state.currentItem}
            onChange={currentItem => this.setState({currentItem})}
            flow={this.props.formFlow}
            schema={this.props.formSchema}
            errorReportKeys={this.state.errorList}
          />
          <hr/>
          {this.state.error &&
            <div className="col-sm-offset-2 panel-group">
              <Alerts display={this.state.error} messages={this.state.errorList}/>
            </div>
          }
          <div className="form-buttons pull-right">
            <button type="button" className="btn btn-danger" onClick={this.closeAddForm}>
              Cancel
            </button>
            <button type="button" className="btn btn-primary" onClick={this.createItem}>
              <i className="glyphicon glyphicon-hdd"/> Create {this.props.itemName}
            </button>
          </div>
        </div>}
        {this.state.showEditForm &&
        <div className="" role="dialog">
          <Form
            value={this.state.currentItem}
            onChange={currentItem => this.setState({currentItem})}
            flow={this.props.formFlow}
            schema={this.props.formSchema}
            errorReportKeys={this.state.errorList}
          />
          <hr/>
          {this.state.error &&
            <div className="col-sm-offset-2 panel-group">
              <Alerts display={this.state.error} messages={this.state.errorList}/>
            </div>
          }
          <div className="form-buttons pull-right updateConfig">
            <button
              type="button"
              className="btn btn-danger"
              title="Delete current item"
              onClick={e => this.setState({confirmDelete: true})}>
              <i className="glyphicon glyphicon-trash"/> Delete
            </button>
            <button type="button" className="btn btn-danger" onClick={this.closeEditForm}>
              Cancel
            </button>
            <button type="button" className="btn btn-success" onClick={this.updateItem}>
              <i className="glyphicon glyphicon-hdd"/> Update {this.props.itemName}
            </button>
            <SweetModal type="confirm"
                        confirm={e => this.deleteItem(e, this.state.currentItem)}
                        id={"confirmDelete"}
                        open={this.state.confirmDelete}
                        onDismiss={__ => this.setState({confirmDelete: false})}
                        labelValid="Delete"
            >
              <div>Are you sure you want to delete that item ?</div>
            </SweetModal>
          </div>
        </div>}
        <SweetModal type="confirm"
                    confirm={e => this.deleteItem(e, this.state.toDelete)}
                    id={"confirmDeleteTable"}
                    open={this.state.confirmDeleteTable}
                    onDismiss={__ => this.setState({confirmDeleteTable: false, toDelete: null})}
                    labelValid="Delete"
        >
          <div>Are you sure you want to delete that item ?</div>
        </SweetModal>
      </div>
    );
  }
}
