import React from "react";
import {Feature, Enabled, Disabled, Experiment, Variant} from 'izanami';
import * as Service from "../services";
import Layout from './Layout';

export default class TvShow extends React.Component {

  state = {
    show: {}
  };

  componentDidMount() {
    this.initShow(this.props);
  }

  componentWillReceiveProps(nextProps) {
    this.initShow(nextProps);
  }

  initShow = props => {
    const { match: { params: name}, user: {tvshows = []} } = props;

    const id = parseInt(name.id);
    const show = tvshows.find(tvshow => tvshow.id === id) || {};
    this.setState({show});
  };

  markWatched = (id, bool) => e => {
    Service.markWatched(this.state.show.id, id, bool);
  };

  calcExpandId(seasons) {
    const lastAllWatched = seasons.reduce((acc, elt, idx) => {
      if (acc === -1 && elt.allWatched) {
        return idx;
      } else if (acc === idx - 1 && elt.allWatched) {
        return idx;
      } else {
        return acc;
      }
    }, -1);

    if (lastAllWatched === -1) {
      return 0;
    } else if (lastAllWatched === seasons.length) {
      return -1;
    } else {
      return lastAllWatched + 1;
    }
  }

  render() {

    const seasons = (this.state.show.seasons || []).sort((s1, s2) => s1.number - s2.number);
    const expandId = this.calcExpandId(seasons);
    console.log('expand id', expandId);
    return (
      <Layout user={this.props.user}>
        <div className="row">
          <div className="col-md-12">
            <h2>{this.state.show.seriesName}</h2>

            <div className="panel-group" id="accordion" role="tablist" aria-multiselectable="true">
              { seasons.map( (s, idx) =>

                <div className="panel panel-default" key={`season-${s.number}`}>
                  <div className="panel-heading" role="tab" id={`heading-${s.number}`}>
                    <h4 className="panel-title">
                      <a
                        role="button"
                        data-toggle="collapse"
                        data-parent="#accordion"
                        href={`#collapse-${s.number}`}
                        aria-controls={`collapse-${s.number}`}
                        { ...(idx === expandId ? {'aria-expanded':"true"} :  {'aria-expanded':"false"} ) }
                      >
                        {`Season ${s.number}`}
                      </a>
                    </h4>
                  </div>
                  <div id={`collapse-${s.number}`} className={idx === expandId ? "panel-collapse collapse in": "panel-collapse collapse"} role="tabpanel" aria-labelledby={`heading-${s.number}`}>
                    <div className="panel-body">
                      <table className="table">
                        <thead>
                          <tr>
                            <th>Number</th>
                            <th>Title</th>
                            <th>Description</th>
                            <th>Watched</th>
                          </tr>
                        </thead>
                        <tbody>
                          {s.episodes.map(e =>
                            <tr key={`episode-${e.id}`}>
                              <td>{e.airedEpisodeNumber}</td>
                              <td>{e.episodeName}</td>
                              <td>{e.overview}</td>
                              <td>
                                {!e.watched &&
                                  <button type="button" className="btn btn-default" onClick={this.markWatched(e.id, true)}><i className="fa fa-eye" /></button>
                                }
                                {e.watched &&
                                  <button type="button" className="btn btn-default" onClick={this.markWatched(e.id, false)}><i className="glyphicon glyphicon-ok" /></button>
                                }
                              </td>
                            </tr>
                          )}
                        </tbody>
                      </table>
                    </div>
                  </div>
                </div>
              )}
            </div>

          </div>
        </div>
      </Layout>
    )
  }
}