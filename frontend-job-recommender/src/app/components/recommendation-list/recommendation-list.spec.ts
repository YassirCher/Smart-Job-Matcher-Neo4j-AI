import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { RecommendationList } from './recommendation-list';

describe('RecommendationList', () => {
  let component: RecommendationList;
  let fixture: ComponentFixture<RecommendationList>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RecommendationList],
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

    fixture = TestBed.createComponent(RecommendationList);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
