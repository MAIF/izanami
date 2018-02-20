import {Component, Input, OnInit, TemplateRef} from '@angular/core';

@Component({
  selector: 'app-experiments-provider',
  templateUrl: './experiments-provider.component.html',
  styleUrls: ['./experiments-provider.component.scss']
})
export class ExperimentsProviderComponent implements OnInit {

  @Input("experiments")
  experiments: any;

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
    if (!this.experiments)
      throw new Error("Experiments is required");
  }
}
