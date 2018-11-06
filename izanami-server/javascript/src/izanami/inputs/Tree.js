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
        if (childNode.classList && (childNode.classList.contains("open-close") || childNode.classList.contains("content")) ) {
          childNode.classList.add('open');
        }
        this.toggleChilds(childNode);
      }
    }
  }

  toggleChild = (id) => e => {
    const elt = document.getElementById(id);
    for (let i = 0; i < elt.childNodes.length; i++) {
      const childNode = elt.childNodes[i];
      if (childNode.classList.contains("content")) {
        childNode.classList.toggle('open');

        for (let j = 0; j < childNode.childNodes.length; j++) {
          const childNode2 = childNode.childNodes[i];
          if (childNode2.classList.contains("open-close")) {
            childNode2.classList.toggle('open');
          }
        }
      }
    }
  };

  search = e => {
    this.props.onSearchChange(e.target.value);
  };

  displayNode = () => (n, i) => {
    const id = `node-${n.id}-${i}`;
    const link = this.props.itemLink(n);
    return (
      <li className="node-tree" key={`node-${n.text}-${i}`} id={id}>

        <div className="content ">
          {n.nodes && n.nodes.length > 0 &&
            <div className={`btn-group btn-group-xs open-close`}>
              <button style={{border:'none'}} type="button" className={`btn btn-primary openbtn`} data-toggle="tooltip" data-placement="top" title="Expand / collapse"
                      onClick={this.toggleChild(id)} >
                <i className="fa fa-caret-up"/>
              </button>
              <button style={{border:'none'}} type="button" className={`btn btn-primary open-all`} data-toggle="tooltip" data-placement="top" title="Expand / collapse"
                      onClick={this.toggleChild(id)}>
                <i className="fa fa-caret-right"/>
              </button>
            </div>
          }
          { (!n.nodes || n.nodes.length === 0) &&
            <div className={`btn-group btn-group-xs`}>
                <span style={{marginLeft: '38px'}} />
            </div>
          }

          <div className="btn-group btn-breadcrumb breadcrumb-info" onClick={this.toggleChild(id)}>
            <div className="btn btn-info key-value-value">
              <span>{n.text}</span>
            </div>
          </div>
          <div style={{paddingLeft: '15px'}} className="btn-group btn-group-xs">
            <Link to={link} type="button" className={`btn btn-primary`} data-toggle="tooltip" data-placement="top" title="Add childnote">
              add (child)
            </Link>
          </div>
          {n.value &&
          <div className="action-button btn-group btn-group-xs">
            <button onClick={e => this.props.editAction(e, n.value)} type="button" className="btn btn-sm btn-success"
                    data-toggle="tooltip" data-placement="top"
                    title="Edit this Configuration">
              <i className="glyphicon glyphicon-pencil"/>
            </button>
          </div>
          }
          {n.value &&
          <div className="action-button btn-group btn-group-xs">
            <button onClick={e => this.props.removeAction(e, n.value)} type="button" className="btn btn-sm btn-danger" data-toggle="tooltip" data-placement="top"
                    title="Delete this Configuration">
              <i className="glyphicon glyphicon-trash"/>
            </button>
          </div>
          }

          <div className="main-content">
            <div className="content-value">
              {n.value && this.props.renderValue(n.value)}
            </div>
            {/*{n.value &&*/}
              {/*<div className="action-button btn-group btn-group-sm">*/}
                {/*<button onClick={e => this.props.editAction(e, n.value)} type="button" className="btn btn-sm btn-success" data-toggle="tooltip" data-placement="top"*/}
                        {/*title="Edit this Configuration">*/}
                  {/*<i className="glyphicon glyphicon-pencil"/>*/}
                {/*</button>*/}
                {/*<button onClick={e => this.props.removeAction(e, n.value)} type="button" className="btn btn-sm btn-danger" data-toggle="tooltip" data-placement="top"*/}
                        {/*title="Delete this Configuration">*/}
                  {/*<i className="glyphicon glyphicon-trash"/>*/}
                {/*</button>*/}
              {/*</div>*/}
            {/*}*/}
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
            <div className="input-group dark-input">
              <span className="input-group-addon back-intermediate-color"><span className="back-color glyphicon glyphicon-search" /></span>
              <input id={`input-search`} className="form-control left-border-none" value={this.state.search} type="text" onChange={this.search}/>
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
