import React from 'react';
import * as Service from "../services";
import SearchTvShow from './SearchTvShow'
import Layout from './Layout';
import {Link} from 'react-router-dom';

export default class MyTvShows extends React.Component {

  remove = id => e => {
    Service.removeTvShow(id);
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
                      <a href="#">
                        <img className="media-object" width="300px" src={`${image}`} />
                      </a>
                    </div>
                    <div className="media-body">
                      <h4 className="media-heading">{title}</h4>
                      <p>{description}</p>
                      <button type="button" className="btn btn-default pull-right" onClick={this.remove(id)}><i className="glyphicon glyphicon-trash"/></button>
                      <Link to={`/tvshow/${id}`} className="btn btn-primary pull-right" >Go to episodes<i className="glyphicon glyphicon-chevron-right"></i></Link>
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
