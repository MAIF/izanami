import React from 'react';
import * as Service from "../services";
import SearchTvShow from './SearchTvShow'
import Layout from './Layout';
import {Experiment, Variant} from 'react-izanami';
import {Link} from 'react-router-dom';
import _ from "lodash";

export default class MyTvShows extends React.Component {

  remove = id => e => {
    Service.removeTvShow(id);
  };

  markAsWon = () => {
    Service.notifyWon("mytvshows:gotoepisodes:button");
  };

  render() {
    return (
      <Layout user={this.props.user}>
        <div className="row" >
          <div className="col-md-12" >
            <div className="row">
              <div className="col-md-12">
                <h2>My Tv Shows</h2>
                <SearchTvShow />
                {this.props.user.shows.map(({image, title, description, id}) =>
                  <div className="media" key={`shows-${id}`}>
                    <div className="media-left media-middle">
                        <img className="media-object" width="540px" src={`${image}`} />
                    </div>
                    <div className="media-body">
                      <h3 className="media-heading">{title}</h3>
                      <Experiment path={"mytvshows:gotoepisodes:button"} notifyDisplay="/api/izanami/experiments/displayed" >
                        <Variant id={"A"}>
                          <Link to={`/tvshow/${id}`} onClick={this.markAsWon} className="btn pull-right" alt="consulter"><i className="fa fa-eye"></i></Link>
                        </Variant>
                        <Variant id={"B"}>
                          <Link to={`/tvshow/${id}`} onClick={this.markAsWon} className="btn pull-right" alt="consulter"><i className="glyphicon glyphicon-chevron-right"></i></Link>
                        </Variant>
                      </Experiment>
                      <button type="button" className="btn pull-right" onClick={this.remove(id)} alt="supprimer"><i className="glyphicon glyphicon-trash"/></button>
                      <p className="description">{_.truncate(description, {length:350})}</p>
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      </Layout>
    )
  }
}
