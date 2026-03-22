import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CandidateList } from './candidate-list';

describe('CandidateList', () => {
  let component: CandidateList;
  let fixture: ComponentFixture<CandidateList>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CandidateList]
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
