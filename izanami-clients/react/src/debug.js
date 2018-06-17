import { arrayOf, bool, node, oneOfType, string } from 'prop-types'
import React, { Component } from 'react'

class Debug extends Component {

  static propTypes = {
    path: oneOfType([string, arrayOf(string)]),
    isActive: bool,
    children: node
  };

  render() {
    const { isActive, path, children } = this.props;
    return (
      <div className={`izanami-feature-${isActive ? 'enabled' : 'disabled'}`} title={`Feature ${path} is ${isActive ? 'enabled' : 'disabled'}`} style={{ position: 'relative', outline: '1px solid green' }}>
        <span style={{ padding: 2, fontFamily: 'Arial', color: 'white', border: '1px solid black',  borderRadius: '5px', backgroundColor: isActive ? 'green' : 'grey', position: 'absolute', top: -17, left: -1, zIndex: 100000, boxShadow: '0 4px 8px 0 rgba(0, 0, 0, 0.3), 0 6px 20px 0 rgba(0, 0, 0, 0.19)' }}>
          Feature <span style={{ fontWeight: 'bold' }}>{path}</span> is {isActive ? 'enabled' : 'disabled'}
        </span>
        { children }
      </div>
    );
  }
}

export default Debug;