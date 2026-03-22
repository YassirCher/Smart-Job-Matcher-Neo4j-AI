import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { CandidateForm } from './candidate-form';

describe('CandidateForm', () => {
  let component: CandidateForm;
  let fixture: ComponentFixture<CandidateForm>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CandidateForm],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    })
    .compileComponents();

    fixture = TestBed.createComponent(CandidateForm);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
