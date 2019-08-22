import React, { Component } from "react";
import PropTypes from "prop-types";
import { Link } from "react-router-dom";

export class Tree extends Component {
  static propTypes = {
    datas: PropTypes.array.isRequired,
    renderValue: PropTypes.func.isRequired,
    onSearchChange: PropTypes.func.isRequired,
    initialSearch: PropTypes.string,
    itemLink: PropTypes.func,
    search: PropTypes.func,
    editAction: PropTypes.func,
    removeAction: PropTypes.func
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

  search = e => {
    this.setState({ search: e.target.value });
    this.props.onSearchChange(e.target.value);
  };

  displayNode = () => (n, i) => {
    const id = `node-${n.id}-${i}`;
    const link = this.props.itemLink(n);
    return (
      <li className="node-tree" key={`node-${n.text}-${i}`} id={id}>
        <div className="content ">
          {n.nodes && n.nodes.length > 0 && (
            <div className={`btn-group btn-group-xs open-close`}>
              <button
                type="button"
                className={`btn  openbtn`}
                data-toggle="tooltip"
                data-placement="top"
                title="Expand / collapse"
                onClick={this.toggleChild(id)}
              >
                <i className="fas fa-caret-down" />
              </button>
              <button
                type="button"
                className={`btn open-all`}
                data-toggle="tooltip"
                data-placement="top"
                title="Expand / collapse"
                onClick={e => this.toggleChilds(document.getElementById(id))}
              >
                <i className="fas fa-caret-right" />
              </button>
            </div>
          )}
          {(!n.nodes || n.nodes.length === 0) && (
            <div className="tree--marginLeftUniqueKey" />
          )}

          <div
            className="btn-group btn-breadcrumb breadcrumb-info"
            onClick={this.toggleChildOrEdit(id, n)}
          >
            <div className="key-value-value">
              <span>{n.text}</span>
              <div className="btn-group btn-group-xs btn-submenu">
                <Link
                  to={link}
                  type="button"
                  className={`btn btn-primary`}
                  data-toggle="tooltip"
                  data-placement="top"
                  title="Add childnote"
                >
                  + child
                </Link>
                <button
                  onClick={_ => this.props.search(n.id)}
                  type="button"
                  className="btn btn-sm btn-success"
                  data-toggle="tooltip"
                  data-placement="top"
                  title="Open on table view"
                >
                  <i className="glyphicon glyphicon-th-list" />
                </button>
                {n.value && (
                  <div className="action-button btn-group btn-group-xs">
                    <button
                      onClick={e => this.props.editAction(e, n.value)}
                      type="button"
                      className="btn btn-sm btn-success"
                      data-toggle="tooltip"
                      data-placement="top"
                      title="Edit this Configuration"
                    >
                      <i className="glyphicon glyphicon-pencil" />
                    </button>
                  </div>
                )}
                {n.value && (
                  <div className="action-button btn-group btn-group-xs">
                    <button
                      onClick={e => this.props.removeAction(e, n.value)}
                      type="button"
                      className="btn btn-sm btn-danger"
                      data-toggle="tooltip"
                      data-placement="top"
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
              {n.value && this.props.renderValue(n.value)}
            </div>
          </div>
        </div>
        {n.nodes && n.nodes.length > 0 && (
          <ul className="root-node">{n.nodes.map(this.displayNode())}</ul>
        )}
      </li>
    );
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
              {this.state.nodes.map(this.displayNode())}
            </ul>
          </div>
        </div>
      </div>
    );
  }
}
