import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { EnabledComponent } from './enabled.component';

describe('EnabledComponent', () => {
  let component: EnabledComponent;
  let fixture: ComponentFixture<EnabledComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ EnabledComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(EnabledComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
