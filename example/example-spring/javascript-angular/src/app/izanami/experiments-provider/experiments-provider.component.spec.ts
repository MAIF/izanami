import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { ExperimentsProviderComponent } from './experiments-provider.component';

describe('ExperimentsProviderComponent', () => {
  let component: ExperimentsProviderComponent;
  let fixture: ComponentFixture<ExperimentsProviderComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ ExperimentsProviderComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(ExperimentsProviderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
