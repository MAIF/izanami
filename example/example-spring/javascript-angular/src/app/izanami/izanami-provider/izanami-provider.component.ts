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

  @Input("loading")
  loading: Function;

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
  };

  ngOnInit() {
    this.featuresFallback = this.featuresFallback || {};
    this.experimentsFallback = this.experimentsFallback || {};
    this.debug = this.debug || false;
    this.fetchHeaders = this.fetchHeaders || {};

    if (!this.loading)
      this.loading = () => null;

    if (this.fetchFrom) {
      this.subscription = this.izanamiService.register(this.fetchFrom).subscribe(this.onDataLoaded);
      this.loadInProgress = true;
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
