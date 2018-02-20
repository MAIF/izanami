import {Injectable} from '@angular/core';
import {Resolve, ActivatedRouteSnapshot} from "@angular/router";
import {AppServicesService} from "./app-services.service";
import {Observable} from "rxjs/Observable";

@Injectable()
export class UserResolver implements Resolve<any> {

  constructor(private appService: AppServicesService) {
  }

  resolve(route: ActivatedRouteSnapshot): Observable<any> | any {
    return this.appService.me();
  }
}
