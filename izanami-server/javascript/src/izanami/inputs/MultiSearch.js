import React, {Component} from 'react';


const PressedButton = props =>  {
  const moreClass = props.active ? 'btn-active': '';
  return <button className={`btn btn-xs btn-search ${moreClass}`} type="button" onClick={props.onClick}>{props.label}</button>
};

export class MultiSearch extends Component {

  state = {
    display: false,
    results: [],
    filters: {},
    inputStyle: {},
    query: ''
  };

  componentDidMount() {
    this.setState({filters: this.props.filters});
    this.toggleTouchOutsideEvent(true);
  }

  componentWillReceiveProps(props) {
    this.setState({filters: props.filters});
  }

  applyFilter = name => value => {
    const filters = this.state.filters.map(v => {
      if(v.name === name) {
        return {...v, active: !v.active}
      } else {
        return v;
      }
    });
    this.setState({filters});
    this.refreshResults(filters, this.state.query);
  };

  textChange = e => {
    let query = e.target.value;
    this.setState({query});
    this.refreshResults(this.state.filters, query);
  };

  onFocus = e => {
    this.setState({display: true, inputStyle: { width: '400px'}} );
    this.refreshResults(this.state.filters, this.state.query);
  };

  onBlur = e => {
    this.handleTouchOutside(e);
  };

  close = () => {
    this.setState({display: false, inputStyle: {}, query: '', filters: this.props.filters});
  };

  refreshResults = (currentFilters, query) => {
    const filters = {};
    currentFilters.forEach(f => filters[f.name]=f.active);
    this.setState({loading: true});
    this.props.query({
      filters,
      query
    })
    .then(results => this.setState({results, loading: false}))
  };

  onElementSelected = elt => e => {
    this.props.onElementSelected(elt);
    this.close();
  };

  loadingZone = () => {
    if (this.state.loading) {
      //return
      //<i className="fa fa-spinner Select-loading search-loading form-control-feedback" aria-hidden="true"/>;
      return (
        <span className="search-loading-zone form-control-feedback" aria-hidden="true">
          <span className="Select-loading" />
        </span>
      );
    }
    return ;
  }

  componentWillUnmount () {
    this.toggleTouchOutsideEvent(false);
  }

  toggleTouchOutsideEvent = (enabled) => {
    if (enabled) {
      if (!document.addEventListener && document.attachEvent) {
        document.attachEvent('ontouchstart', this.handleTouchOutside);
      } else {
        document.addEventListener('touchstart', this.handleTouchOutside);
        document.addEventListener('click', this.handleTouchOutside);
      }
    } else {
      if (!document.removeEventListener && document.detachEvent) {
        document.detachEvent('ontouchstart', this.handleTouchOutside);
      } else {
        document.removeEventListener('touchstart', this.handleTouchOutside);
        document.removeEventListener('click', this.handleTouchOutside);
      }
    }
  };

  handleTouchOutside = (event) => {
    if (this.wrapper && !this.wrapper.contains(event.target)) {
      this.close();
    }
  };

  render() {
    return (
      <div className="row" ref={ref => this.wrapper = ref}>
        <div className="col-lg-12">
          <div className="form-group has-feedback">
            <input type="text" className="form-control search-input" placeholder="Search Configurations, Features, etc ..."
                   style={this.state.inputStyle}
                   value={this.state.query}
                   onFocus={this.onFocus}
                   onChange={this.textChange}
                   onBlur={this.onBlur}
            />
            {this.loadingZone()}
            <i className="glyphicon glyphicon-chevron-down form-control-feedback"/>
          </div>
          <div className={`search-zone ${this.state.display?'search-zone-display':''}`} style={this.state.display?{}:{display: 'none'}}>
            {this.state.display &&
              <div className="buttonsBar">
              {this.state.filters.map(({name, label, active}, i) =>
                <PressedButton key={`searchfilters-${i}`} active={active} label={label} onClick={this.applyFilter(name)}/>
              )}
              </div>
            }
            {this.state.display &&
              <div className="results">
                {this.state.results.map((l, i) =>
                  <p key={`searchres-${i}`} onClick={this.onElementSelected(l)}>{this.props.lineRenderer(l)}</p>
                )}
                {(!this.state.results || this.state.results.length === 0) && <p>No results</p>}
              </div>
            }
          </div>
        </div>
      </div>
    );
  }
}
