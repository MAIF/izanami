import {BrowserModule} from '@angular/platform-browser';
import {NgModule} from '@angular/core';

import {AppComponent} from './app.component';
import {AppRoutingModule} from './app-routing.module';
import {MyTvShowsComponent} from './my-tv-shows/my-tv-shows.component';
import {SearchTvShowComponent} from './search-tv-show/search-tv-show.component';
import {AppServicesService} from './app-services.service';
import {HttpClientModule} from "@angular/common/http";
import {LoginComponent} from './login/login.component';
import {LayoutComponent} from './layout/layout.component';
import {FormsModule, ReactiveFormsModule} from "@angular/forms";
import {UserResolver} from './user-resolver.service';
import {SelectModule} from 'ng-select';
import {TvShowComponent} from './tv-show/tv-show.component';
import {IzanamiModule} from "angular-izanami";

@NgModule({
  declarations: [
    AppComponent,
    MyTvShowsComponent,
    SearchTvShowComponent,
    LoginComponent,
    LayoutComponent,
    TvShowComponent
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    HttpClientModule,
    FormsModule,
    ReactiveFormsModule,
    SelectModule,
    IzanamiModule
  ],
  providers: [AppServicesService, UserResolver],
  bootstrap: [AppComponent]
})
export class AppModule {
}
