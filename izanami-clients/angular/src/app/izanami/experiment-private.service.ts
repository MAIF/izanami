import {Injectable} from '@angular/core';
import {HttpClient, HttpHeaders} from "@angular/common/http";
import {of} from "rxjs/observable/of";
import {catchError} from "rxjs/operators";
import {Observable} from "rxjs/Observable";

@Injectable()
export class ExperimentPrivateService {
  constructor(private httpClient: HttpClient) {
  }

  private static options(): {} {
    let headers: HttpHeaders = new HttpHeaders();
    headers.set('Accept', 'application/json');
    headers.set('Content-Type', 'application/json');
    return {
      headers,
      withCredentials: true
    };
  }

  notifyDisplay(notifyDisplayPath: string, path: string) {
    const url = notifyDisplayPath.indexOf('experiment=') > -1 ? notifyDisplayPath : (notifyDisplayPath + '?experiment=' + path);

    return this.httpClient.post(url, {
      experiment: path
    }, ExperimentPrivateService.options())
      .pipe(catchError(this.handleError(url, {error: true})));
  }

  private handleError<T>(operation = 'operation', result?: T) {
    return (error: any): Observable<T> => {
      console.error(`${operation} failed: ${error.message}`);
      return of(result as T);
    };
  }
}
