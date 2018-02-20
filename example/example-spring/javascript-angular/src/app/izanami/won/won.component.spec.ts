import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { WonComponent } from './won.component';

describe('WonComponent', () => {
  let component: WonComponent;
  let fixture: ComponentFixture<WonComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ WonComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(WonComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
