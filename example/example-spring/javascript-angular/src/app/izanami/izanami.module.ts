import {NgModule} from '@angular/core';
import {IzanamiProviderComponent} from './izanami-provider/izanami-provider.component';
import {IzanamiService} from './izanami.service';
import {BrowserModule} from '@angular/platform-browser';
import {FeatureProviderComponent} from './feature-provider/feature-provider.component';
import {ExperimentsProviderComponent} from './experiments-provider/experiments-provider.component';
import {VariantComponent} from './variant/variant.component';
import {WonComponent} from './won/won.component';
import {ExperimentComponent} from './experiment/experiment.component';
import {EnabledComponent} from './enabled/enabled.component';
import {DisabledComponent} from './disabled/disabled.component';
import {FeatureComponent} from './feature/feature.component';

@NgModule({
  imports: [
    BrowserModule
  ],
  declarations: [
    IzanamiProviderComponent,
    FeatureProviderComponent,
    ExperimentsProviderComponent,
    VariantComponent,
    WonComponent,
    ExperimentComponent,
    EnabledComponent,
    DisabledComponent,
    FeatureComponent
  ],
  exports: [IzanamiProviderComponent],
  providers: [IzanamiService]
})
export class IzanamiModule {
}
