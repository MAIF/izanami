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
                <table className="table">
                  <thead>
                  <tr>
                    <th></th>
                    <th>Name</th>
                    <th>Resume</th>
                    <th></th>
                  </tr>
                  </thead>
                  <tbody>
                  {this.props.user.shows.map(({image, title, description, id}) =>
                    <tr key={`tvshows-${id}`}>
                      <td>{image && <Link to={`/tvshow/${id}`} ><img width="300px" src={`${image}`} /></Link>}</td>
                      <td><Link to={`/tvshow/${id}`}>{title}</Link></td>
                      <td><Link to={`/tvshow/${id}`}>{description}</Link></td>
                      <td>
                        <button type="button" className="btn btn-default" onClick={this.remove(id)}><i className="glyphicon glyphicon-trash"/></button>
                      </td>
                    </tr>
                  )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </Layout>
    )
  }
}
