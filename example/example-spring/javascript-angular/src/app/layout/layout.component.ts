import {Component, OnInit} from '@angular/core';
import {AppServicesService} from "../app-services.service";
import {ActivatedRoute, Router} from "@angular/router";
import {User} from "../model/User";

@Component({
  selector: 'app-layout',
  templateUrl: './layout.component.html',
  styleUrls: ['./layout.component.scss']
})
export class LayoutComponent implements OnInit {
  currentUser: User;

  constructor(private router: Router, private appService: AppServicesService, private route: ActivatedRoute) {
  }

  ngOnInit() {
    this.route.data.subscribe(data => {
      if (data && data.consumer && data.consumer.userId)
        this.currentUser = data.consumer;
    });
  }

  logout() {
    this.appService.logout();
  }

}
