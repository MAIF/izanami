import {Component, OnInit} from '@angular/core';
import {Show} from "../model/Show";
import {AppServicesService} from "../app-services.service";
import {ActivatedRoute} from "@angular/router";
import {User} from "../model/User";

@Component({
  selector: 'app-my-tv-shows',
  templateUrl: './my-tv-shows.component.html',
  styleUrls: ['./my-tv-shows.component.scss']
})
export class MyTvShowsComponent implements OnInit {

  currentUser: User;
  shows: Show[];

  constructor(private appServices: AppServicesService, private route: ActivatedRoute) {
  }

  ngOnInit() {
    this.route.data.subscribe(
      data => {
        if (data && data.consumer && data.consumer.userId) {
          this.currentUser = data.consumer;
          this.shows = this.currentUser.shows;
        }
      }
    );
  }

  markAsWon() {
    this.appServices.notifyWon("mytvshows:gotoepisodes:button");
  }

  remove(show: Show) {
    this.appServices.removeTvShow(show.id)
      .subscribe(() =>
        this.reload()
      );
  }

  private reload() {
    this.appServices.me()
      .subscribe(user => {
        this.currentUser = user;
        this.shows = this.currentUser.shows;
      });
  }

  onSelected() {
    this.reload();
  }

}
