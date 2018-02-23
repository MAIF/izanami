import {Component, Input, OnDestroy, OnInit, TemplateRef} from '@angular/core';
import {IzanamiService} from "../izanami.service";
import {Subscription} from "rxjs/Subscription";

@Component({
  selector: 'app-izanami-provider',
  templateUrl: './izanami-provider.component.html',
  styleUrls: ['./izanami-provider.component.scss']
})
export class IzanamiProviderComponent implements OnInit, OnDestroy {


  @Input("features")
  features: any;

  @Input("featuresFallback")
  featuresFallback: any;

  @Input("experiments")
  experiments: any;

  @Input("experimentsFallback")
  experimentsFallback: any;

  @Input("debug")
  debug: boolean;

  @Input("fetchFrom")
  fetchFrom: string;

  @Input("fetchData")
  fetchData: Function;

  @Input("fetchHeaders")
  fetchHeaders: any;

  @Input("children")
  children: TemplateRef<any>;

  loadInProgress: boolean;
  fetched: any;
  subscription: Subscription;

  constructor(private izanamiService: IzanamiService) {
  }

  onDataLoaded = (data) => {
    this.fetched = {
      features: data.features || this.features,
      featuresFallback: data.featuresFallback || this.featuresFallback,
      experiments: data.experiments || this.experiments,
      experimentsFallback: data.experimentsFallback || this.experimentsFallback,
      debug: data.debug || this.debug,
    };

    this.loadInProgress = false;


    if (this.debug)
      console.debug(`Data loaded into izanami provider with ${this.fetched}`);
  };

  ngOnInit() {
    this.featuresFallback = this.featuresFallback || {};
    this.experimentsFallback = this.experimentsFallback || {};
    this.debug = this.debug || false;
    this.fetchHeaders = this.fetchHeaders || {};

    if (this.debug)
      console.debug(`Init izanami provider`);

    if (this.fetchFrom) {
      if (this.debug)
        console.debug(`Register izanami service with path ${this.fetchFrom}`);

      this.subscription = this.izanamiService.register(this.fetchFrom).subscribe(this.onDataLoaded);
      this.loadInProgress = true;
      this.izanamiReload();
    } else {
      if (this.debug)
        console.debug(`Init izanami provider with features ${this.features} and experiments ${this.experiments}`);
    }
  }

  izanamiReload() {
    if (this.fetchFrom) {
      this.izanamiService.izanamiReload(this.fetchFrom, this.fetchHeaders);
    }
  }

  ngOnDestroy() {
    if (this.fetchFrom)
      this.izanamiService.unregister(this.fetchFrom);

    if (this.subscription)
      this.subscription.unsubscribe();
  }

}
