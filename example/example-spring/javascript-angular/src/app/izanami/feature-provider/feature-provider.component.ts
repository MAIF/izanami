import {Component, Input, OnInit, TemplateRef} from '@angular/core';

@Component({
  selector: 'app-feature-provider',
  templateUrl: './feature-provider.component.html',
  styleUrls: ['./feature-provider.component.scss']
})
export class FeatureProviderComponent implements OnInit {

  @Input("features")
  features: any;

  @Input("fallback")
  fallback: any;

  @Input("fetchFrom")
  fetchFrom: string;

  @Input("debug")
  debug: boolean;

  @Input("children")
  children: TemplateRef<any>;

  constructor() {
  }

  ngOnInit() {
    if (!this.features)
      throw new Error("features is required");

  }
  //
  // ngOnChanges(changes: SimpleChanges): void {
  //   if (changes["features"] && !deepEqual(changes["features"].currentValue, changes["features"].previousValue)) {
  //     this.features = changes["features"].currentValue;
  //   }
  //   if (changes["fallback"] && !deepEqual(changes["fallback"].currentValue, changes["fallback"].previousValue)) {
  //     this.fallback = changes["fallback"].currentValue;
  //   }
  //   if (changes["debug"] && changes["debug"].currentValue !== changes["debug"].previousValue) {
  //     this.debug = changes["debug"].currentValue;
  //   }
  // }

}
