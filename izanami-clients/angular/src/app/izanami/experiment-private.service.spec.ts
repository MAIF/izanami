import { TestBed, inject } from '@angular/core/testing';

import { ExperimentPrivateService } from './experiment-private.service';

describe('ExperimentPrivateService', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ExperimentPrivateService]
    });
  });

  it('should be created', inject([ExperimentPrivateService], (service: ExperimentPrivateService) => {
    expect(service).toBeTruthy();
  }));
});
