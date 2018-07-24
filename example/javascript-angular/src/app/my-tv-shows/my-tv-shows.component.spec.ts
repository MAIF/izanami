import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { MyTvShowsComponent } from './my-tv-shows.component';

describe('MyTvShowsComponent', () => {
  let component: MyTvShowsComponent;
  let fixture: ComponentFixture<MyTvShowsComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ MyTvShowsComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(MyTvShowsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
