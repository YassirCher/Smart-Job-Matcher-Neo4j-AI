import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { CandidateList } from './candidate-list';

describe('CandidateList', () => {
  let component: CandidateList;
  let fixture: ComponentFixture<CandidateList>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CandidateList],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    })
    .compileComponents();

    fixture = TestBed.createComponent(CandidateList);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
