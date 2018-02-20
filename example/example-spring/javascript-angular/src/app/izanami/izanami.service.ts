import { Injectable } from '@angular/core';
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs/Observable";
import {catchError} from "rxjs/operators";
import {of} from "rxjs/observable/of";

@Injectable()
export class IzanamiService {
  izanamiListeners: any = {};

  constructor(private httpClient: HttpClient) {

  }

  izanamiReload(path: string, fetchHeaders: any) {
    this.httpClient.get(path, {headers: fetchHeaders, withCredentials: true})
      .pipe(catchError(this.handleError(path, {error: true})))
      .subscribe(data => {
        const listeners = this.izanamiListeners[path] || [];
        listeners.forEach(l => {
          try {
            l(data)
          } catch (err) {
            console.error(err);
          }
        });
      });
  }

  register(path: string, callback: Function) {
    const listeners = this.izanamiListeners[path] || [];
    const index = listeners.indexOf(callback);
    if (index > -1) {
      listeners.splice(index, 1);
    }
    listeners.push(callback);
    this.izanamiListeners = {...this.izanamiListeners, [path]: listeners}
  }

  unregister(path: string, callback: Function) {
    if (callback && path) {

      const listeners = this.izanamiListeners[path] || [];
      const index = listeners.indexOf(callback);
      if (index > -1) {
        listeners.splice(index, 1);
        this.izanamiListeners = {...this.izanamiListeners, [path]: listeners}
      }
    } else if (path) {
      delete this.izanamiListeners[path];
    }
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
