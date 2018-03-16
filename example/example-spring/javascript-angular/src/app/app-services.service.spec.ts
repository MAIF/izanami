import { TestBed, inject } from '@angular/core/testing';

import { AppServicesService } from './app-services.service';

describe('AppServicesService', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AppServicesService]
    });
  });

  it('should be created', inject([AppServicesService], (service: AppServicesService) => {
    expect(service).toBeTruthy();
  }));
});
