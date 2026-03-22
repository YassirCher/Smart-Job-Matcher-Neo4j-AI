import { Routes } from '@angular/router';
import { Dashboard } from './components/dashboard/dashboard';
import { CandidateList } from './components/candidate-list/candidate-list';
import { CandidateProfile } from './components/candidate-profile/candidate-profile';
import { CandidateForm } from './components/candidate-form/candidate-form';
import { JobListSearch } from './components/job-list-search/job-list-search';
import { JobForm } from './components/job-form/job-form';
import { RecommendationList } from './components/recommendation-list/recommendation-list';
import { GraphVisualizer } from './components/graph-visualizer/graph-visualizer';

export const routes: Routes = [
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' },
  { path: 'dashboard', component: Dashboard },
  { path: 'candidates', component: CandidateList },
  { path: 'candidates/new', component: CandidateForm },
  { path: 'candidates/:id', component: CandidateProfile },
  { path: 'jobs', component: JobListSearch },
  { path: 'jobs/new', component: JobForm },
  { path: 'jobs/edit', component: JobForm },
  { path: 'jobs/edit/:id', component: JobForm },
  { path: 'recommendations/:id', component: RecommendationList },
  { path: 'graph/:id', component: GraphVisualizer }
];
