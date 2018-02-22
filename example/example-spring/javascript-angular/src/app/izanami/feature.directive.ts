import {Directive, ElementRef, Input, OnDestroy, OnInit, TemplateRef, ViewContainerRef} from '@angular/core';
import {IzanamiProviderComponent} from "./izanami-provider/izanami-provider.component";
import {IzanamiService} from "./izanami.service";
import _ from 'lodash';
import deepEqual from 'deep-equal';

import {Subscription} from "rxjs/Subscription";

@Directive({
  selector: '[appFeature]'
})
export class FeatureDirective implements OnInit, OnDestroy {

  @Input("path")
  path: string;

  @Input("debug")
  debug: boolean;

  @Input("activeIfEnabled")
  isEnabled: Boolean;

  subscription: Subscription;
  features: any;
  lastStateActive: boolean;

  constructor(private templateRef: TemplateRef<any>, private viewContainer: ViewContainerRef, private el: ElementRef, private izanamiService: IzanamiService, private izanamiProvider: IzanamiProviderComponent) {
  }

  onFeaturesChanged = ({features}) => {
    if (!deepEqual(this.features, features)) {
      this.features = features;
      let value = _.get(features, this.path) || {active: false};

      const active = !((!value.active && this.isEnabled) || (value.active && !this.isEnabled));

      if (this.lastStateActive !== active) {
        if (this.debug) {
          console.log(`feature \"${this.path}\" is active : ${active}`);
        }

        if (active) {
          if (this.debug)
            console.log(`Enable feature \"${this.path}\"`);

          this.viewContainer.clear();
          this.viewContainer.createEmbeddedView(this.templateRef);

        } else {
          if (this.debug)
            console.log(`Disable feature \"${this.path}\"`);

          this.viewContainer.clear();

        }
      } else if (this.debug)
        console.log(`feature \"${this.path}\" no changes`);

      this.lastStateActive = active;
    } else if (this.debug)
      console.log(`feature \"${this.path}\" no changes`);
  };

  ngOnInit(): void {
    this.features = this.izanamiProvider.featuresFallback;

    if (!this.path)
      throw new Error("Path is required");

    if (!this.debug)
      this.debug = false;

    if (typeof this.isEnabled === 'undefined')
      this.isEnabled = true;

    if (this.debug)
      console.log(`Init feature \"${this.path}\"`);

    this.subscription = this.izanamiService.register(this.izanamiProvider.fetchFrom).subscribe(this.onFeaturesChanged);
  }

  ngOnDestroy(): void {
    if (this.subscription)
      this.subscription.unsubscribe();
  }

}
