import {NgModule} from '@angular/core';
import {RouterModule, Routes} from '@angular/router';
import {AppComponent} from "./app.component";
import {MyTvShowsComponent} from "./my-tv-shows/my-tv-shows.component";
import {LoginComponent} from "./login/login.component";
import {LayoutComponent} from "./layout/layout.component";
import {UserResolver} from "./user-resolver.service";
import {TvShowComponent} from "./tv-show/tv-show.component";

const routes: Routes = [
  {path: 'login', component: LoginComponent},
  {
    path: '', component: LayoutComponent, children: [
      {path: '', component: MyTvShowsComponent},
      {path: 'tvshow/:id', component: TvShowComponent}
    ], resolve: {consumer: UserResolver}
  }

];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule {
}
