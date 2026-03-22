import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { GraphVisualizer } from './graph-visualizer';

describe('GraphVisualizer', () => {
  let component: GraphVisualizer;
  let fixture: ComponentFixture<GraphVisualizer>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GraphVisualizer],
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

    fixture = TestBed.createComponent(GraphVisualizer);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
