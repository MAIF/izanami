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
                        <img className="media-object" width="440px" src={`${image}`} />
                    </div>
                    <div className="media-body">
                      <h4 className="media-heading">{title}</h4>

                      <Link to={`/tvshow/${id}`} className="btn pull-right" alt="consulter"><i className="fa fa-eye"></i></Link>
                      <button type="button" className="btn pull-right" onClick={this.remove(id)} alt="supprimer"><i className="glyphicon glyphicon-trash"/></button>
                      <p className="description">{description}</p>
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
