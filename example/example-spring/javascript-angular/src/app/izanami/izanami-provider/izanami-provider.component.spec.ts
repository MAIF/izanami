import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { IzanamiProviderComponent } from './izanami-provider.component';

describe('IzanamiProviderComponent', () => {
  let component: IzanamiProviderComponent;
  let fixture: ComponentFixture<IzanamiProviderComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ IzanamiProviderComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(IzanamiProviderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
