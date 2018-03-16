import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { SearchTvShowComponent } from './search-tv-show.component';

describe('SearchTvShowComponent', () => {
  let component: SearchTvShowComponent;
  let fixture: ComponentFixture<SearchTvShowComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ SearchTvShowComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SearchTvShowComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
