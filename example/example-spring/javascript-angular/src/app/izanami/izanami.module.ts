import {NgModule} from '@angular/core';
import {IzanamiProviderComponent} from './izanami-provider/izanami-provider.component';
import {IzanamiService} from './izanami.service';
import {BrowserModule} from '@angular/platform-browser';
import {FeatureDirective} from './feature.directive';
import {ExperimentDirective} from './experiment.directive';
import {ExperimentPrivateService} from './experiment-private.service';

@NgModule({
  imports: [
    BrowserModule
  ],
  declarations: [
    IzanamiProviderComponent,
    FeatureDirective,
    ExperimentDirective
  ],
  exports: [IzanamiProviderComponent, FeatureDirective, ExperimentDirective],
  providers: [IzanamiService, ExperimentPrivateService]
})
export class IzanamiModule {
}
