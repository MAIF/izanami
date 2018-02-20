import {Injectable} from '@angular/core';
import {Observable} from "rxjs/Observable";
import {Show} from "./model/Show";
import {of} from "rxjs/observable/of";
import {HttpClient, HttpHeaders} from "@angular/common/http";
import {User} from "./model/User";
import {catchError} from 'rxjs/operators';

@Injectable()
export class AppServicesService {

  constructor(private httpClient: HttpClient) {
  }

  private options(): {} {
    let headers: HttpHeaders = new HttpHeaders();
    headers.set('Accept', 'application/json');
    headers.set('Content-Type', 'application/json');
    return {
      headers,
      withCredentials: true
    };
  }

  removeTvShow(id: string): Observable<any> {
    return this.httpClient.delete(`/api/me/${id}`, this.options())
      .pipe(catchError(this.handleError<User>(`/api/me/${id}`, null)));
  }

  me(): Observable<User> {
    return this.httpClient.get<User>("/api/me", this.options())
      .pipe(catchError(this.handleError<User>("/api/me", null)));
  }

  login(email: string): Observable<Object> {
    return this.httpClient.post("/api/login", {email}, this.options())
      .pipe(catchError(this.handleError("/api/login", {error: true})));
  }

  searchTvShows(input: string): Observable<any> {
    const time = new Date().getTime();
    return this.httpClient.get(`/api/shows/_search?name=${input}&ts=${time}`, this.options())
      .pipe(catchError(this.handleError(`/api/shows/_search?name=${input}&ts=${time}`, [])));

  }

  addTvShow(id: Number): Observable<any> {
    return this.httpClient.post(`/api/me/${id}`, {}, this.options())
      .pipe(catchError(this.handleError(`/api/me/${id}`, {error: true})));
  }

  markSeasonWatched(tvdbid: string, number: Number, bool: boolean): Observable<any> {
    return this.httpClient.post(`/api/me/${tvdbid}/seasons/${number}?watched=${bool}`, {}, this.options())
      .pipe(catchError(this.handleError(`/api/me/${tvdbid}/seasons/${number}?watched=${bool}`, {error: true})));
  }

  markEpisodeWatched(tvdbid: string, number: Number, bool: boolean): Observable<any> {
    return this.httpClient.post(`/api/me/${tvdbid}/episodes/${number}?watched=${bool}`, {}, this.options())
      .pipe(catchError(this.handleError(`/api/me/${tvdbid}/episodes/${number}?watched=${bool}`, {error: true})));
  }

  logout() {
    document.cookie = 'userId=; expires=Thu, 01 Jan 1970 00:00:01 GMT;';
  }

  private handleError<T>(operation = 'operation', result?: T) {
    return (error: any): Observable<T> => {

      // TODO: better job of transforming error for user consumption
      console.error(`${operation} failed: ${error.message}`);

      // Let the app keep running by returning an empty result.
      return of(result as T);
    };
  }
}
