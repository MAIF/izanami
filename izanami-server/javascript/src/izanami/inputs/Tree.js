import React, {Component} from 'react';
import PropTypes from "prop-types";
import {Link} from "react-router-dom";

export class Tree extends Component {

  static propTypes = {
    datas: PropTypes.array.isRequired,
    renderValue: PropTypes.func.isRequired,
    onSearchChange: PropTypes.func.isRequired,
    itemLink: PropTypes.func,
    editAction: PropTypes.func,
    removeAction: PropTypes.func,
  };

  state = {
    nodes: []
  };

  componentDidMount() {
    this.setState({nodes: this.convertDatas(this.props.datas)});
  }

  componentWillReceiveProps(nextProps) {
    this.setState({nodes: this.convertDatas(nextProps.datas || [])});
  }

  convertDatas = (d = []) => {
    return d.map(this.convertNode);
  };

  convertNode = (node, i) => {
    return {
      text: node.key,
      value: node.value,
      nodes: (node.childs || []).map(this.convertNode)
    };
  };

  toggleChilds(e) {
    if (e.childNodes) {
      for (let i = 0; i < e.childNodes.length; i++) {
        const childNode = e.childNodes[i];
        if (childNode.classList && (childNode.classList.contains("open-close") || childNode.classList.contains("content")) ) {
          childNode.classList.add('open');
        }
        this.toggleChilds(childNode);
      }
    }
  }

  displayNode = () => (n, i) => {
    const link = this.props.itemLink(n.value);
    return (
      <li className="node-tree" key={`node-${n.text}-${i}`} id={`node-${n.text}-${i}`}>

        <div className="content ">
          {link &&
            <Link to={link}>
              <div className="btn-group btn-breadcrumb breadcrumb-info">
                <div className="btn btn-info key-value-value">
                  <span>{n.text}</span>
                </div>
              </div>
            </Link>
          }
          {!link &&
            <div className="btn-group btn-breadcrumb breadcrumb-info">
              <div className="btn btn-info key-value-value">
                <span>{n.text}</span>
              </div>
            </div>
          }

          {n.nodes && n.nodes.length > 0 &&
            <div className={`btn-group btn-group-sm open-close`}>
              <button type="button" className={`btn btn-xs btn-primary`} data-toggle="tooltip" data-placement="top" title="Expand / collapse"
                      onClick={e => {
                        e.target.parentNode.classList.toggle('open');
                        e.target.parentNode.parentNode.classList.toggle('open');
                        e.target.parentNode.parentNode.parentNode.classList.toggle('open');
                      }} >
                <i className="fa fa-minus"/>
                <i className="fa fa-plus"/>
              </button>
              <button type="button" className={`btn btn-xs btn-primary open-all`} data-toggle="tooltip" data-placement="top" title="Expand / collapse"
                      onClick={e => {
                        this.toggleChilds(document.getElementById(`node-${n.text}-${i}`));
                      }}>
                <i className="fa fa-plus-circle"/>
              </button>
            </div>
          }
          <div className="main-content">
            <div className="content-value">
              {n.value && this.props.renderValue(n.value)}
            </div>
            {n.value &&
              <div className="action-button btn-group btn-group-sm">
                <button onClick={e => this.props.editAction(e, n.value)} type="button" className="btn btn-sm btn-success" data-toggle="tooltip" data-placement="top"
                        title="Edit this Configuration">
                  <i className="glyphicon glyphicon-pencil"/>
                </button>
                <button onClick={e => this.props.removeAction(e, n.value)} type="button" className="btn btn-sm btn-danger" data-toggle="tooltip" data-placement="top"
                        title="Delete this Configuration">
                  <i className="glyphicon glyphicon-trash"/>
                </button>
              </div>
            }
          </div>
        </div>
        {n.nodes && n.nodes.length > 0 &&
          <ul className="root-node">
            {n.nodes.map(this.displayNode())}
          </ul>
        }
      </li>
    );
  };

  render() {
    return (
      <div>
        <form className="form-horizontal">
          <div className="form-group">
            <div className="input-group">
              <span className="input-group-addon transparent"><span className="glyphicon glyphicon-search" /></span>
              <input id={`input-search`} className="form-control left-border-none" value={this.state.search} type="text"/>
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