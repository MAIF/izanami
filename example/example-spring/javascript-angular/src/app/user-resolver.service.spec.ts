import { TestBed, inject } from '@angular/core/testing';

import { UserResolver } from './user-resolver.service';

describe('UserResolver', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [UserResolver]
    });
  });

  it('should be created', inject([UserResolver], (service: UserResolver) => {
    expect(service).toBeTruthy();
  }));
});
