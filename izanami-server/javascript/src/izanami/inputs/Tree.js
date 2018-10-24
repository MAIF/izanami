import React, {Component} from 'react';
import PropTypes from "prop-types";
import {Key} from "./Key";
import {Link} from "react-router-dom";


class Node extends Component {

}

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

  displayNode = (n, i) => {
    const link = this.props.itemLink(n.value);
    return (
      <li className="node-tree" key={`node-${n.text}-${i}`}>

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
            <div className="btn-group btn-group-sm open-close">
              <button type="button" className="btn btn-xs btn-primary" data-toggle="tooltip" data-placement="top"
                      onClick={e => {
                        e.target.parentNode.classList.toggle('open');
                        e.target.parentNode.parentNode.classList.toggle('open');
                      }} title="Add item">
                <i className="glyphicon glyphicon-minus-sign"/>
                <i className="glyphicon glyphicon-plus-sign"/>
              </button>
            </div>
          }
          <div className="main-content">
            <div className="content-value">
              {n.value && this.props.renderValue(n.value)}
            </div>
            {n.value && <div className="action-button btn-group btn-group-xs">
              <button onClick={e => this.props.editAction(e, n.value)} type="button" className="btn btn-xs btn-success" data-toggle="tooltip" data-placement="top"
                      title="Edit this Configuration">
                <i className="glyphicon glyphicon-pencil"/>
              </button>
              <button onClick={e => this.props.removeAction(e, n.value)} type="button" className="btn btn-xs btn-danger" data-toggle="tooltip" data-placement="top"
                      title="Delete this Configuration">
                <i className="glyphicon glyphicon-trash"/>
              </button>
            </div>
            }
          </div>
        </div>
        {n.nodes && n.nodes.length > 0 &&
          <ul className="root-node">
            {n.nodes.map(this.displayNode)}
          </ul>
        }
      </li>
    );
  };


  displayNodeTmp = (node, level = 0) => {
    if (node) {
      return node.flatMap((n, i) => {
        const spans = [];

        for (let i = 0; i < level; i++) {
          spans.push(<span className="indent"/>);
        }

        let hasNode;
        if (!n.nodes) {
          hasNode = "icon expand-icon glyphicon glyphicon-minus";
        } else {
          hasNode = "icon glyphicon";
        }

        const currentNode = [
          <li className="list-group-item node-tree" key={`node-${n.text}-${level}-${i}`}>
            {spans}
            <span className={hasNode}/>
            <div className="pull-right btn-group btn-group-xs">
              <button type="button" className="btn btn-xs btn-success" data-toggle="tooltip" data-placement="top"
                      title="Edit this Configuration">
                <i className="glyphicon glyphicon-pencil"/>
              </button>
              <button type="button" className="btn btn-xs btn-danger" data-toggle="tooltip" data-placement="top"
                      title="Delete this Configuration">
                <i className="glyphicon glyphicon-trash"/>
              </button>
            </div>
            <span className="icon node-icon"/>
            {n.text}
            {n.value && this.props.renderValue(n.value)}
            <div className="btn-group btn-group-sm">
              {!n.isOpened &&
                <button type="button" className="btn btn-xs btn-primary" data-toggle="tooltip" data-placement="top"
                        title="Add item">
                  <i className="glyphicon glyphicon-plus-sign"/>
                </button>
              }
              {n.isOpened &&
                <button type="button" className="btn btn-xs btn-primary" data-toggle="tooltip" data-placement="top"
                        title="Add item">
                  <i className="glyphicon glyphicon-minus-sign"/>
                </button>
              }
            </div>
          </li>
        ];

        if (n.isOpened) {
          return currentNode.concat(this.displayNode(n.nodes, level + 1));
        } else {
          return currentNode;
        }
      });
    } else {
      return [];
    }
  };

  render() {
    return (
        <div id="tree" className="treeview">
          <div className="root-node">
            <ul className="root-node-tree">
              {this.state.nodes.map(this.displayNode)}
            </ul>
          </div>
        </div>
    );
  }
}