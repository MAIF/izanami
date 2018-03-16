import {Directive, Input, OnDestroy, OnInit, TemplateRef, ViewContainerRef} from '@angular/core';
import {IzanamiService} from "./izanami.service";
import {IzanamiProviderComponent} from "./izanami-provider/izanami-provider.component";
import {Subscription} from "rxjs/Subscription";
import _ from 'lodash';
import deepEqual from 'deep-equal';
import {ExperimentPrivateService} from "./experiment-private.service";

@Directive({
  selector: '[appExperiment]'
})
export class ExperimentDirective implements OnInit, OnDestroy {

  @Input("path")
  path: string;

  @Input("debug")
  debug: boolean;

  @Input("variant")
  variant: string;

  @Input("defaultVariant")
  defaultVariant: string;

  @Input("notifyDisplayPath")
  notifyDisplayPath: string;

  subscription: Subscription;
  experiments: any;
  lastStateVariantActive: boolean;
  first: boolean;

  constructor(private templateRef: TemplateRef<any>, private viewContainer: ViewContainerRef, private izanamiService: IzanamiService, private experimentService: ExperimentPrivateService, private izanamiProvider: IzanamiProviderComponent) {
  }

  onExperimentsChanged = ({experiments}) => {
    if (!deepEqual(this.experiments, experiments)) {
      this.experiments = experiments;
      let value = _.get(experiments, this.path.replace(/:/g, '.')) || {variant: this.defaultVariant};

      const variantActive = value.variant === this.variant;

      if (this.lastStateVariantActive !== variantActive) {
        if (this.debug) {
          console.log(`experiment \"${this.path}\" is active : ${variantActive}`);
        }

        if (variantActive) {
          if (this.debug)
            console.log(`Enable experiment \"${this.path}\" for variant \"${this.variant}\"`);

          this.notifyDisplay();

          this.viewContainer.clear();
          this.viewContainer.createEmbeddedView(this.templateRef);

        } else {
          if (this.debug)
            console.log(`Disable experiment \"${this.path}\" for variant \"${this.variant}\"`);

          this.viewContainer.clear();
        }
      } else if (this.debug)
        console.log(`experiment \"${this.path}\" for variant \"${this.variant}\" no changes`);

      this.lastStateVariantActive = variantActive;
    } else if (this.debug)
      console.log(`experiment \"${this.path}\" for variant \"${this.variant}\" no changes`);

  };

  notifyDisplay() {
    if (this.first)
      if (this.notifyDisplayPath) {
        if (this.debug)
          console.log(`Notify display for experiment \"${this.path}\" with variant \"${this.variant}\"`);

        this.experimentService.notifyDisplay(this.notifyDisplayPath, this.path);
        this.first = false;
      }

  }

  ngOnInit(): void {
    this.first = true;
    this.experiments = this.izanamiProvider.experimentsFallback;

    if (!this.path)
      throw new Error("Path is required");
    if (!this.variant)
      throw new Error("Variant is required");

    this.debug = this.debug || false;

    if (this.debug)
      console.log(`Init experiment \"${this.path}\" for variant \"${this.variant}\"`);

    if (this.izanamiProvider.fetchFrom)
      this.subscription = this.izanamiService.register(this.izanamiProvider.fetchFrom).subscribe(this.onExperimentsChanged);
    else
      this.onExperimentsChanged({experiments: this.izanamiProvider.experiments || this.izanamiProvider.experimentsFallback});
  }

  ngOnDestroy(): void {
    if (this.subscription)
      this.subscription.unsubscribe();
  }
}
