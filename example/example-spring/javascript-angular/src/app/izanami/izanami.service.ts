import {Injectable} from '@angular/core';
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs/Observable";
import {catchError} from "rxjs/operators";
import {of} from "rxjs/observable/of";
import {Subject} from "rxjs/Subject";
import {BehaviorSubject} from "rxjs/BehaviorSubject";

@Injectable()
export class IzanamiService {
  izanamiSubject: {
    path: string,
    data: BehaviorSubject<any>
  }[] = [];

  constructor(private httpClient: HttpClient) {
  }

  izanamiReload(path: string, fetchHeaders: any) {
    this.httpClient.get(path, {headers: fetchHeaders, withCredentials: true})
      .pipe(catchError(this.handleError(path, {error: true})))
      .subscribe(data => {
        const izaSubject = this.izanamiSubject.find(i => i.path === path);
        if (izaSubject)
          izaSubject.data.next(data);
      });
  }

  register(path: string): Subject<any> {
    const izanamiSubjectRegister = this.izanamiSubject.find(i => i.path === path);
    if (!izanamiSubjectRegister) {
      let item = {
        path,
        data: new BehaviorSubject<any>({})
      };
      this.izanamiSubject.push(item);

      return item.data;
    }

    return izanamiSubjectRegister.data;
  }

  unregister(path: string) {
    const indexToUnregister = this.izanamiSubject.findIndex(i => i.path === path);
    if (indexToUnregister !== -1)
      this.izanamiSubject.slice(indexToUnregister, 1);
  }

  private handleError<T>(operation = 'operation', result?: T) {
    return (error: any): Observable<T> => {
      console.error(`${operation} failed: ${error.message}`);
      return of(result as T);
    };
  }

}
