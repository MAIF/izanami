import {Component, Input, OnDestroy, OnInit} from '@angular/core';
import {FeatureProviderComponent} from "../feature-provider/feature-provider.component";
import {IzanamiService} from "../izanami.service";
import {deepEqual} from "assert";
import _ from 'lodash';

@Component({
  selector: 'app-feature',
  templateUrl: './feature.component.html',
  styleUrls: ['./feature.component.scss']
})
export class FeatureComponent implements OnInit, OnDestroy {

  @Input("path")
  path: string;

  @Input("debug")
  debug: boolean;

  features: any;

  constructor(private featureProvider: FeatureProviderComponent, private izanamiService: IzanamiService) {
  }

  ngOnInit() {
    if (!this.path)
      throw new Error("Path is required");

    const fetchFrom = this.featureProvider.fetchFrom;
    if (fetchFrom)
      this.izanamiService.register(fetchFrom, this.onFeaturesChanged);
  }

  ngOnDestroy(): void {
    const fetchFrom = this.featureProvider.fetchFrom;
    if (fetchFrom)
      this.izanamiService.unregister(fetchFrom, this.onFeaturesChanged);
  }


  onFeaturesChanged = ({features}) => {
    if (!deepEqual(this.features, features)) {
      this.features = features;
    }
  };

}
