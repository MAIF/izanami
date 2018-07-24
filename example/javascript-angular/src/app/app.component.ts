import {Component, OnInit} from '@angular/core';
import {AppServicesService} from "./app-services.service";
import {User} from "./model/User";
import {Router, RouterLink} from "@angular/router";

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent {
  currentUser: User;

  constructor(private router: Router, private appServices: AppServicesService) {

  }

  ngOnInit() {
    this.appServices.me()
      .subscribe(user => {
        if (!user)
          this.router.navigate(["login"]);

        this.currentUser = user
      })
    ;
  }
}
