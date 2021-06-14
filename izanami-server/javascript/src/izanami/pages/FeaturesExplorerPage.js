import React, { Component } from "react";
import * as IzanamiServices from "../services/index";
import AceEditor from "react-ace";
import "brace/mode/javascript";
import "brace/theme/monokai";

export class FeaturesExplorerPage extends Component {
  state = {
    query: "",
    context: "{}",
    graph: {
      message: "Run the query to get results ..."
    },
    searching: false
  };

  updateGraph = () => {
    this.setState({ searching: true });
    return IzanamiServices.fetchFeatureGraph(
      this.state.query,
      JSON.parse(this.state.context)
    ).then(graph => {
      this.setState({ graph, searching: false });
    });
  };

  componentDidMount() {
    // this.updateGraph();
    this.props.setTitle("Features explorer");
  }

  render() {
    let code = this.state.graph;
    try {
      code = JSON.stringify(this.state.graph, null, 2);
    } catch (e) {
      console.log(e);
    }
    let codeContext = this.state.context;
    return (
      <div className="col-md-12">
        <div
          className="d-flex align-items-center mb-3"
          style={{ marginLeft: -15 }}
        >
          <div>
            <input
              type="text"
              className="form-control col-xs-12"
              placeholder="feature query (ie. project:env:*, mcf:preprod:frontend:*)"
              value={this.state.query}
              onChange={e => this.setState({ query: e.target.value })}
            />
          </div>
          <button
            type="button"
            className="btn btn-success btn-search"
            onClick={this.updateGraph}
          >
            <i className="fas fa-search"></i>{" "}
            {!this.state.searching && "Search"}
            {this.state.searching && "Searching ..."}
          </button>
        </div>
        <div className="row">
          <h5>User context</h5>
          <AceEditor
            mode="javascript"
            theme="monokai"
            onChange={c => this.setState({ context: c })}
            value={codeContext}
            name="contextParam"
            editorProps={{ $blockScrolling: true }}
            height="100px"
            width="100%"
          />
        </div>
        <div className="row" style={{ marginTop: 20 }}>
          <AceEditor
            mode="javascript"
            theme="monokai"
            value={code}
            name="scriptParam"
            editorProps={{ $blockScrolling: true }}
            height="400px"
            width="100%"
          />
        </div>
      </div>
    );
  }
}
