import {Component, OnInit} from '@angular/core';
import {Show} from "../model/Show";
import {AppServicesService} from "../app-services.service";
import {ActivatedRoute} from "@angular/router";
import {Season} from "../model/Season";

@Component({
  selector: 'app-tv-show',
  templateUrl: './tv-show.component.html',
  styleUrls: ['./tv-show.component.scss']
})
export class TvShowComponent implements OnInit {

  show: Show;
  seasons: Season[];
  selectedSeason: Number;

  constructor(private appService: AppServicesService, private route: ActivatedRoute) {
  }

  ngOnInit() {
    this.reload();
  }

  private reload() {
    const showId = this.route.snapshot.paramMap.get('id');

    this.appService.me()
      .subscribe(user => {
        this.show = user.shows.find(s => s.id === showId);

        this.seasons = (this.show.seasons || [])
          .filter(s =>
            s.number !== 0
          )
          .sort((s1, s2) => s1.number - s2.number);

        this.selectedSeason = this.calcExpandId(this.show.seasons);

      });
  }

  markSeasonWatched(id: Number, bool: boolean) {
    this.appService.markSeasonWatched(this.show.id, id, bool)
      .subscribe(() => this.reload());
  }

  markEpisodeWatched(id: Number, bool: boolean) {
    this.appService.markEpisodeWatched(this.show.id, id, bool)
      .subscribe(() => this.reload());
  }

  private calcExpandId(seasons: Season[]) {
    const lastAllWatched = seasons.reduce((acc, elt, idx) => {
      if (acc === -1 && elt.allWatched) {
        return idx;
      } else if (acc === idx - 1 && elt.allWatched) {
        return idx;
      } else {
        return acc;
      }
    }, -1);

    if (lastAllWatched === -1) {
      return 0;
    } else if (lastAllWatched === seasons.length) {
      return -1;
    } else {
      return lastAllWatched + 1;
    }
  }

}
