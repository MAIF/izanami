import { TestBed, inject } from '@angular/core/testing';

import { IzanamiService } from './izanami.service';

describe('IzanamiService', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [IzanamiService]
    });
  });

  it('should be created', inject([IzanamiService], (service: IzanamiService) => {
    expect(service).toBeTruthy();
  }));
});
