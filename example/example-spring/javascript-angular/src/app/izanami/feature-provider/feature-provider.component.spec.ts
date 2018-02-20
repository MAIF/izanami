import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { FeatureProviderComponent } from './feature-provider.component';

describe('FeatureProviderComponent', () => {
  let component: FeatureProviderComponent;
  let fixture: ComponentFixture<FeatureProviderComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ FeatureProviderComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(FeatureProviderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
