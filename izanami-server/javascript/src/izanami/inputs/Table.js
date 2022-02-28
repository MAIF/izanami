import React, {Component} from "react";
import PropTypes from "prop-types";
import {Alerts, Form} from ".";
import isEqual from "lodash/isEqual";
import {createTooltip} from "./tooltips";
import { SweetModal } from "./SweetModal";
import * as Events from "../services/events";
import {Tree} from "./Tree";
import * as TreeHelper from "../helpers/TreeData";
import * as Persistence from "../helpers/persistence";
import * as Abilitations from "../helpers/Abilitations"

import ReactTable from "react-table";

function LoadingComponent(props) {
  return (
    <div
      className="loadingPage"
      style={{
        display:
          props.loading &&
          props.loadingText &&
          props.loadingText.trim().length > 0
            ? "flex"
            : "none"
      }}
    >
      {props.loadingText}
    </div>
  );
}

const SearchInput = ({filter, onChange}) => {
  return (
    <input
      type="text"
      className="form-control input-sm"
      value={filter ? filter.value : ""}
      onChange={e => onChange(e.target.value)}
      placeholder="Search ..." />
  );
};

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
    fetchItemsTree: PropTypes.func,
    fetchItem: PropTypes.func.isRequired,
    updateItem: PropTypes.func,
    deleteItem: PropTypes.func,
    createItem: PropTypes.func,
    confirmUpdate: PropTypes.bool,
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
    treeModeEnabled: PropTypes.bool,
    renderTreeLeaf: PropTypes.func,
    itemLink: PropTypes.func,
    searchColumnName: PropTypes.string,
    copyNodeWindow: PropTypes.bool,
    copyNodes: PropTypes.func,
    searchKeys: PropTypes.func,
    lockable: PropTypes.bool,
    lockType: PropTypes.string
  };

  static defaultProps = {
    searchColumnName: "key",
    copyNodeWindow: false,
    rowNavigation: false,
    pageSize: 20,
    firstSort: null,
    disableAddButton: false,
    confirmUpdate: false,
    convertItem: e => e,
    compareItem: (a, b) => isEqual(a, b)
  };

  state = {
    table: (Persistence.get("table-render") || "table") === "table",
    items: [],
    currentItem: null,
    currentItemOriginal: null,
    showAddForm: false,
    showEditForm: false,
    searched: "",
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
    confirmUpdate: false,
    toDelete: null
  };

  lastSearchArgs = {};

  componentDidUpdate(prevProps, prevState) {
    const location = this.props.parentProps.location;
    const prevLocation = prevProps.parentProps.location;
    if(location.pathname !== prevLocation.pathname) {
      this.readRoute()
    }
  }

  componentDidMount() {
    this.update().then(() => {
      if (this.props.search) {
        this.search({target: {value: this.props.search}});
      }
    });
    this.readRoute();

    if (this.props.eventNames) {
      Events.addCallback(this.handleEvent);
    }
  }

  handleEvent = e => {
    if (this.isTable()) {
      this.handleEventInTable(e);
    } else {
      this.handleEventInTree(e);
    }
  };

  handleEventInTable = e => {
    let items;
    switch (e.type) {
      case this.props.eventNames.created:
        if (this.state.items.find(i => this.props.compareItem(i, e.payload))) {
          items = this.state.items;
        } else {
          items = [e.payload, ...this.state.items].splice(
            0,
            this.props.pageSize
          );
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
        items = this.state.items.filter(
          i => !this.props.compareItem(i, e.oldValue)
        );
        break;
      default:
        items = this.state.items;
    }

    this.setState({items, justUpdated: true});
  };

  handleEventInTree = e => {
    let tree;
    const key = this.props.extractKey(e.payload);
    const segments = key.split(":");
    switch (e.type) {
      case this.props.eventNames.created:
        tree = TreeHelper.addOrUpdateInTree(
          segments,
          e.payload,
          this.state.tree
        );
        break;
      case this.props.eventNames.updated:
        tree = TreeHelper.addOrUpdateInTree(
          segments,
          e.payload,
          this.state.tree
        );
        break;
      case this.props.eventNames.deleted:
        tree = TreeHelper.deleteInTree(segments, this.state.tree);
        break;
      default:
        tree = this.state.tree;
    }
    this.setState({tree, justUpdated: true});
  };

  componentWillUnmount() {
    this.unmountShortcuts();
    if (this.props.eventNames) {
      Events.removeCallback(this.handleEvent);
    }
  }

  readRoute = () => {
    if (this.props.parentProps.params.taction) {
      const action = this.props.parentProps.params.taction;
      if (action === "add") {
        this.showAddForm(null, this.props.parentProps.params.titem);
      } else if (action === "edit") {
        const item = this.props.parentProps.params.titem;
        this.props.fetchItem(item).then(data => {
          this.showEditForm(null, data);
        });
      }
    }
    if (
      this.props.parentProps.location.query &&
      this.props.parentProps.location.query.search
    ) {
      const searched = this.props.parentProps.location.query.search;
      const defaultFiltered = this.props.columns
        .filter(c => !c.notFilterable)
        .map(c => ({id: c.title, value: searched}));
      this.setState({defaultFiltered, searchQuery: searched}, () =>
        this.update({filtered: defaultFiltered})
      );
    }
    if(this.props.parentProps.location.pathname === `/${this.props.backToUrl}`) {
      this.setState({
        currentItem: null,
        currentItemOriginal: null,
        showAddForm: false,
        showEditForm: false
      })
    }
  };

  mountShortcuts = () => {
    document.body.addEventListener("keydown", this.saveShortcut);
  };

  unmountShortcuts = () => {
    document.body.removeEventListener("keydown", this.saveShortcut);
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
    if (!isEqual(this.lastSearchArgs, args)) {
      this.lastSearchArgs = args;
      this.update(initialArgs);
    }
  };

  isTable = () => {
    return !this.props.treeModeEnabled || this.state.table;
  };

  isTree = () => {
    return this.props.treeModeEnabled && !this.state.table;
  };

  update = (initialArgs = {}) => {
    this.setState({loading: true});
    const args = {
      search: initialArgs.filtered,
      pageSize: initialArgs.pageSize || this.props.pageSize,
      page: initialArgs.page ? initialArgs.page + 1 : 1
    };
    const s =
      (
        (initialArgs.filtered || []).find(
          o => o.id === this.props.searchColumnName
        ) || {}
      ).value || ((initialArgs.filtered || [])[0] || {}).value;
    if (s) {
      const url = new URL(window.location);
      url.searchParams.set("search", s);
      this.props.navigate(url);
    } else {
      const url = new URL(window.location);
      url.searchParams.delete("search");
      this.props.navigate(url);
    }
    if (this.isTable()) {
      return this.props.fetchItems(args).then(
        ({nbPages, results}) => {
          this.setState({
            items: results || [],
            nbPages: nbPages === 0 ? 1 : nbPages,
            loading: false
          });
        },
        () => this.setState({loading: false})
      );
    } else {
      return this.props.fetchItemsTree
        ? this.props.fetchItemsTree({search: initialArgs.filtered}).then(
          tree => {
            this.setState({tree, loading: false});
          },
          () => this.setState({loading: false})
        )
        : this.setState({loading: false});
    }
  };

  gotoItem = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    if (this.props.navigateTo) {
      this.props.navigateTo(item);
    } else {
      this.showEditForm(e, item);
    }
  };

  closeAddForm = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.unmountShortcuts();
    this.props.parentProps.setTitle(this.props.defaultTitle);
    this.setState({
      currentItem: null,
      showAddForm: false,
      error: false,
      errorList: []
    });
    this.props.backToUrl
      ? this.props.navigate(`${window.__contextPath}/${this.props.backToUrl}`)
      : window.history.back();
  };

  showAddForm = (e, initialId) => {
    if (e && e.preventDefault) e.preventDefault();
    this.mountShortcuts();
    this.props.parentProps.setTitle(`Create a new ${this.props.itemName}`);
    const id = initialId ? `/${initialId}` : "";
    this.props.navigate(`${window.__contextPath}/${this.props.selfUrl}/add${id}`);
    console.log(this.props.defaultValue(initialId))
    this.setState({
      currentItem: this.props.defaultValue(initialId),
      currentItemOriginal: this.props.defaultValue(),
      showAddForm: true,
      error: false,
      errorList: []
    });
  };

  closeEditForm = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.unmountShortcuts();
    this.props.parentProps.setTitle(this.props.defaultTitle);
    this.setState({
      currentItem: null,
      currentItemOriginal: null,
      showEditForm: false,
      error: false,
      errorList: []
    });
    this.props.backToUrl
      ? this.props.navigate(`${window.__contextPath}/${this.props.backToUrl}`)
      : window.history.back();
  };

  showEditForm = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    this.mountShortcuts();
    this.props.navigate(`${window.__contextPath}/${this.props.selfUrl}/edit/${this.props.extractKey(item)}`);
    this.props.parentProps.setTitle(`Update a ${this.props.itemName}`);
    const currentItem = this.props.convertItem(item);
    this.setState({
      currentItem,
      currentItemOriginal: currentItem,
      showEditForm: true,
      error: false,
      errorList: []
    });
  };

  deleteItem = (e, item, i) => {
    if (e && e.preventDefault) e.preventDefault();
    //if (confirm('Are you sure you want to delete that item ?')) {
    this.setState({
      showEditForm: false,
      showAddForm: false,
      confirmDelete: false,
      confirmDeleteTable: false,
      toDelete: null
    }, () => this.props.deleteItem(item).then(res => {
      if (res.status === 403) {
        res.json().then(data => {
          this.setState({
            items: this.state.items,
            error: true,
            errorList: data.errors ? data.errors : [{message: `error.forbiddenaction`}]
          });
        })
      } else {
        const items = this.state.items.filter(i => !isEqual(i, item));
        this.setState({ items });
        this.props.parentProps.setTitle(this.props.defaultTitle);
        this.props.backToUrl
          ? this.props.navigate(`${window.__contextPath}/${this.props.backToUrl}`)
          : window.history.back();
      }
    }));
  };

  createItem = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.parentProps.setTitle(this.props.defaultTitle);

    this.setState({error: false, errorList: []});

    this.props
      .createItem(this.state.currentItem)
      .then(res => {
        return res.json().then(data => {
          if (res.status === 403) {
            const errorData = {
              error: true,
              errorList: data.errors ? data.errors : [{message: `error.forbiddenaction`}]
            };
            this.setState(errorData);
            return errorData
          } else if (res.status === 201) {
            this.setState({
              currentItem: null,
              currentItemOriginal: null,
              showAddForm: false
            });
            return {
              data: data,
              error: false
            };
          } else {
            const errorData = {
              error: true,
              errorList: this.buildErrorList(data)
            };
            this.setState(errorData);
            return errorData;
          }
        })
      })
      .then(res => {
        if (res && !res.error) {
          let items;
          if (this.state.items.find(i => this.props.compareItem(i, res.data))) {
            items = [...this.state.items];
          } else {
            items = [res.data, ...this.state.items].splice(
              0,
              this.props.pageSize
            );
          }
          this.setState({error: false, items, justUpdated: true});
          this.props.backToUrl
            ? this.props.navigate(`${window.__contextPath}/${this.props.backToUrl}`)
            : window.history.back();
        }
      });
  };

  updateItem = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.parentProps.setTitle(this.props.defaultTitle);

    this.setState({error: false, errorList: [], confirmUpdate: false});

    const currentItem = this.state.currentItem;
    const currentItemOriginal = this.state.currentItemOriginal;
    this.props
      .updateItem(currentItem, currentItemOriginal)
      .then(res => {
        return res.json().then(data => {
          if (res.status === 403) {
            const errorData = {
              error: true,
              errorList: data.errors ? data.errors : [{message: `error.forbiddenaction`}]
            };
            this.setState(errorData);
            return errorData;
          } else if (res.status === 200) {
            return {
              data: data,
              error: false
            };
          } else {
            const errorData = {
              error: true,
              errorList: this.buildErrorList(data)
            };
            this.setState(errorData);
            return errorData;
          }
        })
      })
      .then(res => {
        if (res && !res.error) {
          const items = this.state.items.map(i => {
            if (isEqual(i, currentItemOriginal)) {
              return res.data;
            } else {
              return i;
            }
          });
          this.setState({items, showEditForm: false, justUpdated: true});
          this.props.backToUrl
            ? this.props.navigate(`${window.__contextPath}/${this.props.backToUrl}`)
            : window.history.back();
        }
      });
  };

  toggleRender = () => {
    const table = !this.state.table;
    if (table) {
      Persistence.set("table-render", "table");
    } else {
      Persistence.set("table-render", "tree");
    }
    this.setState({table}, () => this.update());
  };

  renderLeaf = value => {
    return this.props.renderTreeLeaf(value);
  };

  uploadFile = link => e => {
    const upload = e => {
      fetch(link, {
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/nd-json"
        },
        body: e.currentTarget.result
      }).then(res => {
        if (res.status === 200) {
          return res.json().then(({success}) => {
            this.setState({
              successMessages: [
                {message: "file.import.success", args: [success]}
              ]
            });
          });
        } else {
          return res.json().then(({errors}) => {
            this.setState({
              error: true,
              errorList: this.buildErrorList(errors)
            });
          });
        }
      });
    };

    const reader = new FileReader();
    reader.onload = upload;
    reader.readAsText(e.target.files[0]);
  };

  buildErrorList = ({errors = {}, fieldErrors = {}}) => {
    const errorsOnFields = Object.keys(fieldErrors).flatMap(k => {
      const messages = fieldErrors[k] || [];
      return messages.map(({message = "", args = []}) => ({
        message: `${k}.${message}`,
        args
      }));
    });
    return [...errors, ...errorsOnFields];
  };

  search = text => {
    this.props.backToUrl &&
    this.props.navigate(`${window.__contextPath}/${this.props.backToUrl}?search=${text || ""}`);
    const defaultFiltered = this.props.columns
      .filter(c => !c.notFilterable)
      .map(c => ({id: c.title, value: text}));
    this.setState({defaultFiltered, table: true});
  };

  isAllowed = (item, letter) => {
    if (item) {
      const k = this.props.extractKey(item);
      return Abilitations.isAllowed(this.props.user, k, letter);

    }
    return true;
  };

  isDeleteAllowed = (item) => {
    return this.isAllowed(item, "D");
  };

  isUpdateAllowed = (item) => {
    return this.isAllowed(item, "U");
  };

  render() {
    const columns = this.props.columns.map(c => ({
      Header: c.title,
      id: c.title,
      headerStyle: c.style,
      width: c.style && c.style.width ? c.style.width : undefined,
      style: {height: 30, ...c.style},
      sortable: !c.notSortable,
      filterable: !c.notFilterable,
      accessor: d => (c.content ? c.content(d) : d),
      Filter: SearchInput,
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
            style={{cursor: "pointer", width: "100%"}}
          >
            {value}
          </div>
        );
      }
    }));
    if (this.props.showActions) {
      columns.push({
        Header: "Actions",
        id: "actions",
        width: 140,
        style: {textAlign: "center"},
        filterable: false,
        accessor: (item, ___, index) => (
          <div style={{ width: 140, display: "flex", justifyContent:"center" }}>
            <div className="displayGroupBtn">
              <button
                type="button"
                className="btn btn-sm btn-success"
                {...createTooltip(
                  `Edit this ${this.props.itemName}`,
                  "top",
                  true
                )}
                onClick={e => this.showEditForm(e, item)}
              >
                <i className="fas fa-pencil-alt"/>
              </button>
              {this.props.showLink && (
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  {...createTooltip(
                    `Open this ${this.props.itemName} in a new window`,
                    "top",
                    true
                  )}
                  onClick={e => this.gotoItem(e, item)}
                >
                  <i className="fas fa-link"/>
                </button>
              )}
              {this.isDeleteAllowed(item) && <button
                type="button"
                className="btn btn-sm btn-danger"
                {...createTooltip(
                  `Delete this ${this.props.itemName}`,
                  "top",
                  true
                )}
                onClick={e => {
                  this.setState({confirmDeleteTable: true, toDelete: item})
                }
                }>
                <i className="fas fa-trash-alt"/>
              </button>}
            </div>
          </div>
        )
      });
    }
    return (
      <div className="col-12">
        {!this.state.showEditForm && !this.state.showAddForm && (
          <div>
            <div className="row mb-2">
              <div className="col-md-12">
                {this.state.error && (
                  <Alerts
                    display={this.state.error}
                    messages={this.state.errorList}
                  />
                )}
                {this.state.successMessages &&
                this.state.successMessages.length > 0 && (
                  <Alerts
                    type="success"
                    display={true}
                    messages={this.state.successMessages}
                    onClose={() => this.setState({successMessages: []})}
                  />
                )}
              </div>
            </div>
            <div className="row mb-2">
              <div className="col-md-12">
                {this.props.treeModeEnabled && this.isTable() && (
                  <button
                    type="button"
                    className="btn btn-primary ms-2"
                    onClick={this.toggleRender}
                    {...createTooltip("Switch the view")}
                  >
                    <i className="fas fa-signal" style={{transform: "rotate(90deg)"}}/>
                  </button>
                )}
                {this.isTree() && (
                  <button
                    type="button"
                    className="btn btn-primary ms-2"
                    onClick={this.toggleRender}
                    {...createTooltip("Switch the view")}
                  >
                    <i className="fas fa-list"/>
                  </button>
                )}
                <button
                  type="button"
                  className="btn btn-primary"
                  {...createTooltip("Reload the current table")}
                  onClick={this.update}>
                  <i className="fas fa-sync-alt"/>
                </button>

                {this.props.showActions && !this.props.disableAddButton && (
                  <button
                    type="button"
                    className="btn btn-primary ms-2"
                    onClick={this.showAddForm}
                    {...createTooltip(`Create a new ${this.props.itemName}`)}>
                    <i className="fas fa-plus-circle me-2" />Add item
                  </button>
                )}
                {this.props.showActions && this.props.user.admin && (
                  <div
                    className="dropdown menuActions">
                    <button
                      className="btn"
                      data-toggle="dropdown"
                      type="button"
                      data-bs-toggle="dropdown"
                      aria-expanded="false">
                      <i className="fas fa-cog" aria-hidden="true"/>
                    </button>
                    <ul className="dropdown-menu p-3" aria-labelledby="dropdownMenu2">
                      {this.props.downloadLinks &&
                      this.props.downloadLinks.map(({title, link}, i) => (
                        <li key={`download-${i}`}>
                          <a href={link} {...createTooltip(`${title}`)}>
                            <i className="fas fa-file-download"/>{" "}
                            {title}
                          </a>
                        </li>
                      ))}
                      {this.props.uploadLinks && !this.props.disableAddButton &&
                      this.props.uploadLinks.map(({title, link}, i) => (
                        <li key={`upload-${i}`}>
                          <div
                            className="cursor-pointer"
                            onClick={e => {
                              document.getElementById(`upload${i}`).click();
                            }}>
                            <i className="fas fa-file-upload"/>{" "}
                            {title}
                            <input
                              id={`upload${i}`}
                              type="file"
                              className="d-none"
                              onChange={this.uploadFile(link)}
                              {...createTooltip(`${title}`)}
                            />
                          </div>
                        </li>
                      ))}
                      <li></li>
                    </ul>
                  </div>
                )}
              </div>
            </div>

            {this.isTable() && (
              <div className="rrow">
                <ReactTable
                  className="fulltable -striped -highlight"
                  data={this.state.items}
                  loading={this.state.loading}
                  sortable={true}
                  filterable={true}
                  filterAll={true}
                  defaultSorted={[
                    {id: this.props.columns[0].title, desc: false}
                  ]}
                  manual
                  pages={this.state.nbPages}
                  defaultPageSize={this.props.pageSize}
                  columns={columns}
                  LoadingComponent={LoadingComponent}
                  onFetchData={this.onFetchData}
                  defaultFiltered={this.state.defaultFiltered}
                />
              </div>
            )}
            {!this.isTable() && (
              <div>
                <Tree
                  datas={this.state.tree || []}
                  renderValue={this.renderLeaf}
                  copyNodeWindow={this.props.copyNodeWindow || false}
                  copyNodes={this.props.copyNodes}
                  searchKeys={this.props.searchKeys}
                  itemLink={this.props.itemLink}
                  onSearchChange={text => {
                    this.update({filtered: [{id: "key", value: text}]});
                  }}
                  openOnTable={id => {
                    this.setState(
                      {table: !this.state.table},
                      () => this.update({filtered: [{id: "key", value: id}]}));
                  }}
                  editAction={(e, item) => this.showEditForm(e, item)}
                  removeAction={(e, item) =>
                    this.setState({confirmDeleteTable: true, toDelete: item})
                  }
                  lockable={this.props.lockable}
                  lockType={this.props.lockType}
                />
              </div>
            )}
          </div>
        )}

        {this.state.showAddForm && (
          <div className="" role="dialog">
            <Form
              value={this.state.currentItem}
              onChange={currentItem => this.setState({currentItem})}
              flow={this.props.formFlow}
              schema={this.props.formSchema}
              errorReportKeys={this.state.errorList}
            />
            <hr/>
            {this.state.error && (
              <div className="offset-sm-2 panel-group">
                <Alerts
                  display={this.state.error}
                  messages={this.state.errorList}
                />
              </div>
            )}
            <div className="form-buttons float-end">
              <button
                type="button"
                className="btn btn-danger"
                onClick={this.closeAddForm}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-success"
                onClick={this.createItem}>
                <i className="fas fa-hdd"/> Create{" "}
                {this.props.itemName}
              </button>
            </div>
          </div>
        )}
        {this.state.showEditForm && (
          <div className="" role="dialog">
            <Form
              value={this.state.currentItem}
              onChange={currentItem => this.setState({currentItem})}
              flow={this.props.formFlow}
              schema={this.props.formSchema}
              errorReportKeys={this.state.errorList}
              edit={this.state.showEditForm}
            />
            <hr/>
            {this.state.error && (
              <div className="offset-sm-2 panel-group">
                <Alerts
                  display={this.state.error}
                  messages={this.state.errorList}
                />
              </div>
            )}
            <div className="form-buttons float-end updateConfig">
              {this.isDeleteAllowed(this.state.currentItem) && <button
                type="button"
                className="btn btn-danger"
                title="Delete current item"
                onClick={e => this.setState({confirmDelete: true})}>
                <i className="far fa-trash-alt"></i> Delete
              </button>}
              <button
                type="button"
                className="btn btn-danger"
                onClick={this.closeEditForm}>
                Cancel
              </button>
              {this.isUpdateAllowed(this.state.currentItem) && <button
                type="button"
                className="btn btn-success"
                onClick={e => this.props.confirmUpdate ? this.setState({confirmUpdate: true}) : this.updateItem(e)}>
                <i className="fas fa-hdd"/> Update{" "}
                {this.props.itemName}
              </button>}
              <SweetModal
                type="confirm"
                confirm={e => this.deleteItem(e, this.state.currentItem)}
                id={"confirmDelete"}
                open={this.state.confirmDelete}
                onDismiss={__ => this.setState({confirmDelete: false})}
                labelValid="Delete">
                <div>Are you sure you want to delete that item ?</div>
              </SweetModal>
              <SweetModal
                type="confirm"
                confirm={e => this.updateItem(e, this.state.currentItem)}
                id={"confirmUpdate"}
                title={"Confirm this changes"}
                open={this.state.confirmUpdate}
                onDismiss={__ => this.setState({confirmUpdate: false})}
                labelValid="Update">
                {this.props.summarizeUpdate &&
                  this.props.summarizeUpdate(this.state.currentItem, this.state.currentItemOriginal)}
              </SweetModal>
            </div>
          </div>
        )}
        <SweetModal
          type="confirm"
          confirm={e => this.deleteItem(e, this.state.toDelete)}
          id={"confirmDeleteTable"}
          open={this.state.confirmDeleteTable}
          onDismiss={__ =>
            this.setState({confirmDeleteTable: false, toDelete: null})
          }
          labelValid="Delete">
          <div>Are you sure you want to delete that item ?</div>
        </SweetModal>
      </div>
    )
  };
}
