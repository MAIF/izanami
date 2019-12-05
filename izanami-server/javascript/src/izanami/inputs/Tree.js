import React, { Component } from "react";
import PropTypes from "prop-types";
import { Link } from "react-router-dom";
import Popover from "react-popover";
import {KeyInput} from "./KeyInput";
import {BooleanInput} from "./BooleanInput";

const Key = props => {
  const values = props.value.split(":").filter(e => !!e);
  return (
      <div className="btn-group btn-breadcrumb" style={{marginTop:'10px'}}>
        {values.map((part, i) => (
            <div className="key-value-value-big" key={`key-value-${props.value}-${i}`}>
              <span>{part}</span>
              {i < values.length - 1 && <i className="fas fa-caret-right" />}
            </div>
        ))}
      </div>
  );
};

class CopyNodeWindow extends Component {

  state = {
    defaultValue: false
  };

  clone = () => {
    if (this.props.copyNodes) {
      this.props.copyNodes(this.props.nodekey, this.state.key, this.state.defaultValue)
          .then(_ => this.props.close());
    }
  };

  render() {
    return (
        <div onMouseOut={_ => this.props.close() }
             className={'copy-node-window'} onClick={_ => false}>
          <form className="form-horizontal" style={this.props.style}>
            <div className="form-group">
              <label htmlFor={`input-From`} className="col-sm-2 control-label">
                From
              </label>
              <div className="col-sm-10">
                <Key value={this.props.nodekey || ''} />
              </div>
            </div>
            <KeyInput label={'To'} autoFocus={true} search={this.props.searchKeys} value={''} onChange={key => this.setState({key})} />
            <BooleanInput label={'Active'}
                          value={this.state.defaultValue}
                          onChange={defaultValue => this.setState({defaultValue})} />
            <div className="form-group">
              <div className="col-sm-12">
                <div className="btn-group pull-right">
                  <button type="button" className="btn btn-danger" onClick={_ => this.props.close()}>Cancel</button>
                  <button type="button" className="btn btn-primary"
                          onClick={_ => this.clone()}>
                    Clone
                  </button>
                </div>
              </div>
            </div>
          </form>
        </div>
    );
  }
}


class Node extends Component {

  state = {
  };

  search = e => {
    this.setState({ search: e.target.value });
    this.props.onSearchChange(e.target.value);
  };

  toggleChilds(e) {
    if (e.childNodes) {
      for (let i = 0; i < e.childNodes.length; i++) {
        const childNode = e.childNodes[i];
        if (
            childNode.classList &&
            (childNode.classList.contains("open-close") ||
                childNode.classList.contains("content"))
        ) {
          childNode.classList.add("open");
        }
        this.toggleChilds(childNode);
      }
    }
  }

  toggleChild = id => e => {
    const elt = document.getElementById(id);
    for (let i = 0; i < elt.childNodes.length; i++) {
      const childNode = elt.childNodes[i];
      if (childNode.classList.contains("content")) {
        childNode.classList.toggle("open");

        for (let j = 0; j < childNode.childNodes.length; j++) {
          const childNode2 = childNode.childNodes[i];
          if (childNode2.classList.contains("open-close")) {
            childNode2.classList.toggle("open");
          }
        }
      }
    }
  };

  toggleChildOrEdit = (id, n) => e => {
    if (n.nodes && n.nodes.length > 0) {
      this.toggleChild(id)(e);
    } else {
      this.props.editAction(e, n.value);
    }
  };

  render() {
    const id = `node-${this.props.node.id}-${this.props.index}`;
    const link = this.props.itemLink(this.props.node);
    const styleDisplay = this.state.openMenu ? {display: 'inline-block'} : {};
    return (
        <li className="node-tree" key={`node-${this.props.node.text}-${this.props.index}`} id={id}>
          <div className="content ">
            {this.props.node.nodes && this.props.node.nodes.length > 0 && (
                <div className={`btn-group btn-group-xs open-close`}>
                  <button
                      type="button"
                      className={`btn  openbtn`}
                      title="Expand / collapse"
                      onClick={this.toggleChild(id)}
                  >
                    <i className="fas fa-caret-down" />
                  </button>
                  <button
                      type="button"
                      className={`btn open-all`}
                      title="Expand / collapse"
                      onClick={e => this.toggleChilds(document.getElementById(id))}
                  >
                    <i className="fas fa-caret-right" />
                  </button>
                </div>
            )}
            {(!this.props.node.nodes || this.props.node.nodes.length === 0) && (
                <div className="tree--marginLeftUniqueKey" />
            )}

            <div
                className="btn-group btn-breadcrumb breadcrumb-info"
            >
              <div className="key-value-value">
                <span onClick={this.toggleChildOrEdit(id, this.props.node)}>{this.props.node.text}</span>
                <div className={`btn-group btn-group-xs btn-submenu`}
                     style={styleDisplay}
                     onMouseOver={ _ => this.setState({openMenu: true}) }
                     onMouseOut={ _ => this.setState({openMenu: false}) }
                >
                  <Link
                      to={link}
                      type="button"
                      className={`btn btn-primary`}
                      onMouseOver={ _ => this.setState({openCopy:false})}
                      title="Add childnote"
                  >
                    + child
                  </Link>
                  {this.props.copyNodeWindow &&
                  <button
                      onMouseOver={_ => this.setState({ openCopy: true})}
                      onMouseOut={_ => this.setState({ openCopy: false})}
                      type="button"
                      className="btn btn-sm btn-success"
                      title="Open on table view">

                    <Popover isOpen={this.state.openCopy}
                             preferPlace={'below'}
                             refreshIntervalMs={0}
                             enterExitTransitionDurationMs={0}
                             onOuterAction={_ => this.setState({openCopy:false})}
                             body={
                               <CopyNodeWindow nodekey={this.props.node.id}
                                               copyNodes={this.props.copyNodes}
                                               searchKeys={this.props.searchKeys}
                                               close={_ => this.setState({openCopy:false})}
                                               open={_ => this.setState({openCopy:true})}
                               />
                             } >
                      <i className="glyphicon glyphicon-duplicate" />
                    </Popover>
                  </button>}
                  <button
                      onClick={_ => this.search(this.props.node.id)}
                      onMouseOver={ _ => this.setState({openCopy:false}) }
                      type="button"
                      className="btn btn-sm btn-success"
                      title="Open on table view"
                  >
                    <i className="glyphicon glyphicon-th-list" />
                  </button>

                  {this.props.node.value && (
                      <div className="action-button btn-group btn-group-xs">
                        <button
                            onClick={e => this.props.editAction(e, this.props.node.value)}
                            onMouseOver={ _ => this.setState({openCopy:false})}
                            type="button"
                            className="btn btn-sm btn-success"
                            title="Edit this Configuration"
                        >
                          <i className="glyphicon glyphicon-pencil" />
                        </button>
                      </div>
                  )}
                  {this.props.node.value && (
                      <div className="action-button btn-group btn-group-xs">
                        <button
                            onClick={e => this.props.removeAction(e, this.props.node.value)}
                            onMouseOver={ _ => this.setState({openCopy:false})}
                            type="button"
                            className="btn btn-sm btn-danger"
                            title="Delete this Configuration"
                        >
                          <i className="glyphicon glyphicon-trash" />
                        </button>
                      </div>
                  )}
                </div>
              </div>
            </div>

            <div className="main-content">
              <div className="content-value">
                {this.props.node.value && this.props.renderValue(this.props.node.value)}
              </div>
            </div>
          </div>
          {this.props.node.nodes && this.props.node.nodes.length > 0 && (
              <ul className="root-node">{this.props.node.nodes.map( (n, i) =>
                  <Node key={`node-${this.props.index}-${i}`}
                        node={n}
                        index={i}
                        copyNodeWindow={this.props.copyNodeWindow}
                        copyNodes={this.props.copyNodes}
                        searchKeys={this.props.searchKeys}
                        renderValue={this.props.renderValue}
                        removeAction={this.props.removeAction}
                        editAction={this.props.editAction}
                        itemLink={this.props.itemLink}
                  />)
                }
              </ul>
          )}
        </li>
    );
  }

}

export class Tree extends Component {
  static propTypes = {
    datas: PropTypes.array.isRequired,
    renderValue: PropTypes.func.isRequired,
    onSearchChange: PropTypes.func.isRequired,
    initialSearch: PropTypes.string,
    itemLink: PropTypes.func,
    search: PropTypes.func,
    editAction: PropTypes.func,
    removeAction: PropTypes.func,
    copyNodeWindow: PropTypes.bool,
    copyNodes: PropTypes.func,
    searchKeys: PropTypes.func
  };

  state = {
    nodes: []
  };

  componentDidMount() {
    this.setState({
      nodes: this.convertDatas(this.props.datas),
      search: this.props.initialSearch
    });
  }

  componentWillReceiveProps(nextProps) {
    this.setState({ nodes: this.convertDatas(nextProps.datas || []) });
  }

  convertDatas = (d = []) => {
    return d.map(this.convertNode);
  };

  convertNode = (node, i) => {
    return {
      id: node.id,
      text: node.key,
      value: node.value,
      nodes: (node.childs || []).map(this.convertNode)
    };
  };
  render() {
    return (
      <div className="col-xs-12">
        <form className="form-horizontal">
          <div className="form-group">
            <div className="input-group dark-input">
              <span className="input-group-addon back-intermediate-color">
                <span className="back-color glyphicon glyphicon-search" />
              </span>
              <input
                id={`input-search`}
                className="form-control left-border-none"
                value={this.state.search}
                type="text"
                onChange={this.search}
              />
            </div>
          </div>
        </form>
        <div className="treeview">
          <div className="root-node">
            <ul className="root-node-tree">
              {this.state.nodes.map((n, i) =>
                  <Node key={`node-0-${i}`}
                        node={n}
                        index={i}
                        renderValue={this.props.renderValue}
                        removeAction={this.props.removeAction}
                        editAction={this.props.editAction}
                        itemLink={this.props.itemLink}
                        copyNodeWindow={this.props.copyNodeWindow}
                        copyNodes={this.props.copyNodes}
                        searchKeys={this.props.searchKeys}
                  />
                )}
            </ul>
          </div>
        </div>
      </div>
    );
  }
}
