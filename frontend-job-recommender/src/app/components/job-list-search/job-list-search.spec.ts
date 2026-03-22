import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { JobListSearch } from './job-list-search';

describe('JobListSearch', () => {
  let component: JobListSearch;
  let fixture: ComponentFixture<JobListSearch>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [JobListSearch],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    })
    .compileComponents();

    fixture = TestBed.createComponent(JobListSearch);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
