import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { CandidateProfile } from './candidate-profile';

describe('CandidateProfile', () => {
  let component: CandidateProfile;
  let fixture: ComponentFixture<CandidateProfile>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CandidateProfile],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ id: 'test-candidate' })
            }
          }
        }
      ]
    })
    .compileComponents();

    fixture = TestBed.createComponent(CandidateProfile);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
