import React from "react";
import {Feature, Enabled, Disabled} from 'react-izanami';
import * as Service from "../services";
import Layout from './Layout';
import {Link} from 'react-router-dom';

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
    const { match: { params: name}, user: {shows = []} } = props;
    const id = name.id;
    const show = shows.find(show => show.id === id) || {};
    this.setState({show});
  };

  markEpisodeWatched = (id, bool) => e => {
    Service.markEpisodeWatched(this.state.show.id, id, bool);
  };
  markSeasonWatched = (id, bool) => e => {
    Service.markSeasonWatched(this.state.show.id, id, bool);
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
    const seasons = (this.state.show.seasons || [])
      .filter(s =>
        s.number !== 0
      )
      .sort((s1, s2) =>
        s1.number - s2.number
      );
    const expandId = this.calcExpandId(seasons);
    return (
      <Layout user={this.props.user}>
        <div className="row">
          <div className="col-md-12 details">
              <div className="row">
                    <div className="col-md-1">
                      <Link to={"/"}><i className="fa fa-home fa-2x" aria-hidden="true"></i></Link>
                    </div>
                    <div className="col-md-10">
                      <h1 style={{textAlign: 'center'}}>{this.state.show.title}</h1>
                    </div>
              </div>


            <p>{this.state.show.description}</p>

            <img className="center-block visuel" src={this.state.show.image}/>

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
                        <Feature path={"mytvshows:season:markaswatched"}>
                          <Enabled>
                            {s.allWatched &&
                              <button
                                onClick={this.markSeasonWatched(s.number, false)}
                                className="btn btn default pull-right addBtn">
                                  <i className="glyphicon glyphicon-ok"/>
                              </button>
                            }
                            {!s.allWatched &&
                              <button
                                onClick={this.markSeasonWatched(s.number, true)}
                                className="btn btn default pull-right addBtn">
                                ADD
                              </button>
                            }
                          </Enabled>
                          <Disabled>
                            <div></div>
                          </Disabled>
                        </Feature>
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
                              <td>{e.number}</td>
                              <td>{e.title}</td>
                              <td>{e.description}</td>
                              <td>
                                {!e.watched &&
                                  <button type="button" className="btn addBigBtn" onClick={this.markEpisodeWatched(e.id, true)}> ADD </button>
                                }
                                {e.watched &&
                                  <button type="button"className="btn addBigBtn" onClick={this.markEpisodeWatched(e.id, false)}><i className="glyphicon glyphicon-ok" /></button>
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
