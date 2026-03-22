import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { JobForm } from './job-form';
import { Api } from '../../services/api';

describe('JobForm', () => {
  let component: JobForm;
  let fixture: ComponentFixture<JobForm>;
  let apiSpy: jasmine.SpyObj<Api>;
  let routerSpy: jasmine.SpyObj<Router>;

  beforeEach(async () => {
    apiSpy = jasmine.createSpyObj<Api>('Api', ['createJob', 'updateJob', 'updateJobByLink', 'getJob', 'getJobByLink', 'parseJobFromDescription']);
    routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);

    apiSpy.createJob.and.returnValue(of({
      jobLink: 'JOB-1',
      title: 'Data Engineer',
      type: 'Full-time',
      level: 'Senior',
      skills: []
    } as any));

    apiSpy.parseJobFromDescription.and.returnValue(of({
      success: true,
      fromLlm: true,
      job: { jobLink: '', title: 'Data Engineer', type: 'Full-time', level: 'Senior', skills: [] },
      evidences: { title: 'Data Engineer' },
      skillSuggestions: [],
      warnings: [],
      inferredFields: ['title']
    } as any));

    apiSpy.getJob.and.returnValue(of({
      jobLink: 'JOB-1',
      title: 'Data Engineer',
      type: 'Full-time',
      level: 'Senior',
      skills: []
    } as any));

    apiSpy.getJobByLink.and.returnValue(of({
      jobLink: 'JOB-1',
      title: 'Data Engineer',
      type: 'Full-time',
      level: 'Senior',
      skills: []
    } as any));

    await TestBed.configureTestingModule({
      imports: [JobForm],
      providers: [
        { provide: Api, useValue: apiSpy },
        { provide: Router, useValue: routerSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({}),
              queryParamMap: convertToParamMap({})
            }
          }
        }
      ]
    })
    .compileComponents();

    fixture = TestBed.createComponent(JobForm);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should keep manual save available when AI parsing fails', () => {
    apiSpy.parseJobFromDescription.and.returnValue(throwError(() => new Error('groq down')));

    component.aiRawText = 'random text from recruiter';
    component.magicFill();

    expect(component.magicFillError).toContain('indisponible');
    component.job.title = 'Manual title';
    component.job.type = 'Contract';
    component.job.level = 'Mid';
    component.selectedSkills = [{ name: 'python' }];

    component.save();

    expect(apiSpy.createJob).toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/jobs']);
  });

  it('should apply AI suggestion and evidences when parsing succeeds', () => {
    component.aiRawText = 'Senior Data Engineer full-time';
    component.magicFill();

    expect(component.job.title).toBe('Data Engineer');
    expect(component.getEvidence('title')).toBe('Data Engineer');
  });
});
