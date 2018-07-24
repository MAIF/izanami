import {Component, EventEmitter, OnInit, Output, ViewChild} from '@angular/core';
import {IOptions} from "tslint";
import {AppServicesService} from "../app-services.service";
import {SelectComponent} from 'ng-select';


@Component({
  selector: 'app-search-tv-show',
  templateUrl: './search-tv-show.component.html',
  styleUrls: ['./search-tv-show.component.scss']
})
export class SearchTvShowComponent implements OnInit {

  options: Array<IOptions>;
  @Output() onSelected = new EventEmitter<any>();
  @ViewChild("mySelect") mySelect: SelectComponent;

  constructor(private appService: AppServicesService) {
  }

  ngOnInit() {
  }

  onNoOptionsFound(event) {
    this.searchTvShows(event);
  }

  selected(event) {
    this.mySelect.clear();

    this.appService.addTvShow(event.id)
      .subscribe(() => {
        this.onSelected.emit(event);
      });
  }

  private searchTvShows(inputSearch: string) {
    this.appService.searchTvShows(inputSearch)
      .subscribe(options => {
        this.options = options.map(o => {
          return {
            ...o,
            value: o.id,
            label: o.title
          }
        });
      });
  }
}
