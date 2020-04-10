import React, { Component } from "react";

const PressedButton = props => {
  const moreClass = props.active ? "btn-active" : "";
  return (
    <button
      className={`btn btn-sm btn-search ${moreClass}`}
      type="button"
      onClick={props.onClick}
    >
      {props.label}
    </button>
  );
};

export class MultiSearch extends Component {
  state = {
    display: false,
    results: [],
    filters: {},
    inputStyle: {},
    query: ""
  };

  componentDidMount() {
    this.setState({ filters: this.props.filters });
    this.toggleTouchOutsideEvent(true);
    document.addEventListener("keydown", this.listenToSlash, false);
  }

  componentWillReceiveProps(props) {
    this.setState({ filters: props.filters });
  }

  applyFilter = name => value => {
    const filters = this.state.filters.map(v => {
      if (v.name === name) {
        return { ...v, active: !v.active };
      } else {
        return v;
      }
    });
    this.setState({ filters });
    this.refreshResults(filters, this.state.query);
  };

  textChange = e => {
    let query = e.target.value;
    this.setState({ query });
    this.refreshResults(this.state.filters, query);
  };

  onFocus = e => {
    this.setState({ display: true, inputStyle: {} });
    this.refreshResults(this.state.filters, this.state.query);
  };

  onBlur = e => {
    this.handleTouchOutside(e);
  };

  close = () => {
    this.setState({
      display: false,
      inputStyle: {},
      query: "",
      filters: this.props.filters
    });
  };

  refreshResults = (currentFilters, query) => {
    const filters = {};
    currentFilters.forEach(f => (filters[f.name] = f.active));
    this.setState({ loading: true });
    this.props
      .query({
        filters,
        query
      })
      .then(results => this.setState({ results, loading: false }));
  };

  onElementSelected = elt => e => {
    this.props.onElementSelected(elt);
    this.close();
  };

  loadingZone = () => {
    if (this.state.loading) {
      return (
        <span
          className="search-loading-zone form-control-feedback"
          aria-hidden="true"
        >
          <span className="Select-loading" />
        </span>
      );
    }
    return;
  };

  componentWillUnmount() {
    this.toggleTouchOutsideEvent(false);
    document.removeEventListener("keydown", this.listenToSlash);
  }

  toggleTouchOutsideEvent = enabled => {
    if (enabled) {
      if (!document.addEventListener && document.attachEvent) {
        document.attachEvent("ontouchstart", this.handleTouchOutside);
      } else {
        document.addEventListener("touchstart", this.handleTouchOutside);
        document.addEventListener("click", this.handleTouchOutside);
      }
    } else {
      if (!document.removeEventListener && document.detachEvent) {
        document.detachEvent("ontouchstart", this.handleTouchOutside);
      } else {
        document.removeEventListener("touchstart", this.handleTouchOutside);
        document.removeEventListener("click", this.handleTouchOutside);
      }
    }
  };

  handleTouchOutside = event => {
    if (this.wrapper && !this.wrapper.contains(event.target)) {
      this.close();
    }
  };

  listenToSlash = e => {
    if (e.keyCode === 191 && e.target.tagName.toLowerCase() !== "input") {
      setTimeout(() => {
        this.onFocus(null);
        if (this.searchInput) this.searchInput.focus();
      });
    }
  };

  render() {
    return (
      <div className="" ref={ref => (this.wrapper = ref)}>
          <div className="form-group row has-feedback">
            <input
              type="text"
              className="form-control search-input"
              placeholder="Search Configurations, Features, etc ..."
              style={this.state.inputStyle}
              value={this.state.query}
              onFocus={this.onFocus}
              onChange={this.textChange}
              onBlur={this.onBlur}
              ref={r => (this.searchInput = r)}
            />

            <span
              className="form-control-feedback"
              onClick={e => {
                this.onFocus(null);
                if (this.searchInput) this.searchInput.focus();
              }}
            >
              <span
                style={{ marginLeft: '-30px' }}
                title="You can jump directly into the search bar from anywhere just by typing '/'"
              >
                <svg xmlns="http://www.w3.org/2000/svg" width="19" height="20">
                  <defs>
                    <rect id="a" width="19" height="20" rx="3" />
                  </defs>
                  <g fill="none" fillRule="evenodd">
                    <rect
                      stroke="#5F6165"
                      x=".5"
                      y=".5"
                      width="18"
                      height="19"
                      rx="3"
                    />
                    <path
                      fill="#979A9C"
                      d="M11.76 5.979l-3.8 9.079h-.91l3.78-9.08z"
                    />
                  </g>
                </svg>
              </span>
            </span>
            {this.loadingZone()}
          </div>
          <div
            className={`search-zone ${
              this.state.display ? "search-zone-display" : ""
            }`}
            style={this.state.display ? {} : { display: "none" }}
          >
            {this.state.display && (
              <div className="buttonsBar">
                {this.state.filters.map(({ name, label, active }, i) => (
                  <PressedButton
                    key={`searchfilters-${i}`}
                    active={active}
                    label={label}
                    onClick={this.applyFilter(name)}
                  />
                ))}
              </div>
            )}
            {this.state.display && (
              <div className="results">
                {this.state.results.map((l, i) => (
                  <p key={`searchres-${i}`} onClick={this.onElementSelected(l)}>
                    {this.props.lineRenderer(l)}
                  </p>
                ))}
                {(!this.state.results || this.state.results.length === 0) && (
                  <p>No results</p>
                )}
              </div>
            )}
        </div>
      </div>
    );
  }
}
