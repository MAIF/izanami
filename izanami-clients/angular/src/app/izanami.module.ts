import { BrowserModule } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';

import { NgModule } from '@angular/core';

import {IzanamiProviderComponent} from './izanami/izanami-provider/izanami-provider.component';
import {IzanamiService} from './izanami/izanami.service';
import {FeatureDirective} from './izanami/feature.directive';
import {ExperimentDirective} from './izanami/experiment.directive';
import {ExperimentPrivateService} from './izanami/experiment-private.service';

@NgModule({
  imports: [
    CommonModule,
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
export class IzanamiModule { }
