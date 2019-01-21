import React, { Component } from 'react';
import * as IzanamiServices from "../services/index";
import AceEditor from 'react-ace';
import 'brace/mode/javascript';
import 'brace/theme/monokai';

export class ConfigExplorerPage extends Component {

  state = {
    query: '',
    graph: {
      "message": "Run the query to get results ..."
    },
    searching: false,
  };

  updateGraph = () => {
    this.setState({ searching: true });
    return IzanamiServices.fetchConfigGraph(this.state.query).then(graph => {
      this.setState({ graph, searching: false });
    });
  };

  componentDidMount() {
    // this.updateGraph();
  }

  componentDidMount() {
    this.props.setTitle("Configurations explorer");
  }

  render() {
    let code = this.state.graph;
    try {
      code = JSON.stringify(this.state.graph, null, 2);
    } catch (e) {
      console.error(e);
    }
    return (
      <div className="col-md-12">
        <div className="form-inline" style={{ marginBottom: 20, marginLeft: -15  }}>
          <div className="form-group">
            <input type="text" className="form-control col-xs-12" placeholder="configuration query (ie. project:env:*, mcf:preprod:frontend:*)" value={this.state.query} onChange={e => this.setState({ query: e.target.value })} />
          </div>
          <button type="button" className="btn btn-success btn-search" onClick={this.updateGraph}>
            <i className="glyphicon glyphicon-search" />
            {' '}
            {!this.state.searching && 'Search'}
            {this.state.searching && 'Searching ...'}
          </button>
        </div>
        <div className="row">
          <AceEditor mode="javascript"
                     theme="monokai"
                     value={code}
                     name="scriptParam"
                     editorProps={{$blockScrolling: true}}
                     height="600px"
                     width="100%" />
        </div>
      </div>
    );
  }
}
