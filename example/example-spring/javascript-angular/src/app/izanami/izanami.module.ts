import {NgModule} from '@angular/core';
import {IzanamiProviderComponent} from './izanami-provider/izanami-provider.component';
import {IzanamiService} from './izanami.service';
import {BrowserModule} from '@angular/platform-browser';
import {FeatureDirective} from './feature.directive';

@NgModule({
  imports: [
    BrowserModule
  ],
  declarations: [
    IzanamiProviderComponent,
    FeatureDirective
  ],
  exports: [IzanamiProviderComponent, FeatureDirective],
  providers: [IzanamiService]
})
export class IzanamiModule {
}
