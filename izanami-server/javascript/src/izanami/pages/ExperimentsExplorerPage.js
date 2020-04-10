import React, { Component } from "react";
import * as IzanamiServices from "../services/index";
import AceEditor from "react-ace";
import "brace/mode/javascript";
import "brace/theme/monokai";

export class ExperimentsExplorerPage extends Component {
  state = {
    query: "",
    clientId: "",
    graph: {
      message: "Run the query to get results ..."
    },
    searching: false
  };

  updateGraph = () => {
    this.setState({ searching: true });
    if (this.state.clientId.trim() === "") {
      this.setState({
        graph: { message: "You have to provide a client id" },
        searching: false
      });
    } else {
      return IzanamiServices.fetchExperimentTree(
        this.state.query,
        this.state.clientId
      ).then(graph => {
        this.setState({ graph, searching: false });
      });
    }
  };

  componentDidMount() {
    // this.updateGraph();
    this.props.setTitle("Experiments explorer");
  }

  render() {
    let code = this.state.graph;
    try {
      code = JSON.stringify(this.state.graph, null, 2);
    } catch (e) {
      console.log(e);
    }
    return (
      <div className="col-md-12">
        <div
          className="form-inline"
          style={{ marginBottom: 20, marginLeft: -15 }}
        >
          <div className="form-group">
            <input
              type="text"
              className="form-control col-xs-12"
              placeholder="feature query (ie. project:env:*, mcf:preprod:frontend:*)"
              value={this.state.query}
              onChange={e => this.setState({ query: e.target.value })}
            />
            <input
              type="text"
              className="form-control col-xs-12"
              placeholder="client id (ie. john.doe@maif.fr)"
              value={this.state.clientId}
              onChange={e => this.setState({ clientId: e.target.value })}
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
