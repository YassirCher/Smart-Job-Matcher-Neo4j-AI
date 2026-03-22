import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, SmartJobFieldSuggestion, SmartJobParseResponse } from '../../services/api';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { of, Subject } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, map, switchMap, takeUntil, tap } from 'rxjs/operators';

@Component({
  selector: 'app-job-form',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './job-form.html',
  styleUrl: './job-form.css'
})
export class JobForm implements OnInit, OnDestroy {
  isEditMode: boolean = false;
  editingJobId: string = '';
  editingJobLink: string = '';
  isSaving: boolean = false;
  saveError: string = '';
  job: any = { jobLink: 'JOB-' + Date.now(), title: '', type: '', level: '' };

  aiRawText: string = '';
  isMagicFilling: boolean = false;
  magicFillError: string = '';
  magicFillWarnings: string[] = [];
  fieldEvidences: Record<string, string> = {};
  skillEvidences: Record<string, string> = {};

  searchSkillText: string = '';
  selectedSkills: any[] = [];
  filteredSkills: any[] = [];
  isSearchingSkills: boolean = false;
  private destroy$ = new Subject<void>();
  private skillSearch$ = new Subject<string>();
  private closeSkillSearchHandle: any = null;

  searchCompanyText: string = '';
  selectedCompany: any = null;
  filteredCompanies: any[] = [];
  isSearchingCompanies: boolean = false;
  private companySearch$ = new Subject<string>();
  private closeCompanySearchHandle: any = null;

  searchLocationText: string = '';
  selectedLocation: any = null;
  filteredLocations: any[] = [];
  isSearchingLocations: boolean = false;
  private locationSearch$ = new Subject<string>();
  private closeLocationSearchHandle: any = null;

  private extractList<T>(data: any): T[] {
    if (Array.isArray(data)) {
      return data as T[];
    }
    if (Array.isArray(data?.content)) {
      return data.content as T[];
    }
    if (Array.isArray(data?.items)) {
      return data.items as T[];
    }
    return [];
  }

  constructor(private api: Api, private router: Router, private route: ActivatedRoute) {}

  ngOnInit(): void {
    const jobLinkQuery = this.route.snapshot.queryParamMap.get('jobLink');
    if (jobLinkQuery) {
      this.isEditMode = true;
      this.editingJobLink = jobLinkQuery;
      this.loadJobForEditByLink(jobLinkQuery);
    }

    const id = this.route.snapshot.paramMap.get('id');
    if (id && !this.isEditMode) {
      this.isEditMode = true;
      this.editingJobId = id;
      this.loadJobForEdit(id);
    }

    this.initSkillSearch();
    this.initCompanySearch();
    this.initLocationSearch();
  }

  private loadJobForEdit(id: string): void {
    this.api.getJob(id).subscribe({
      next: (job) => {
        this.job = {
          jobLink: job?.jobLink || id,
          title: job?.title || '',
          type: job?.type || '',
          level: job?.level || ''
        };

        this.selectedCompany = job?.company?.name ? { name: job.company.name } : null;
        this.searchCompanyText = this.selectedCompany?.name || '';

        this.selectedLocation = job?.location?.name ? { name: job.location.name } : null;
        this.searchLocationText = this.selectedLocation?.name || '';

        this.selectedSkills = Array.isArray(job?.skills)
          ? job.skills
              .map((s: any) => ({ name: typeof s === 'string' ? s : s?.name }))
              .filter((s: any) => !!s.name)
          : [];
      },
      error: () => {
        this.router.navigate(['/jobs']);
      }
    });
  }

  private loadJobForEditByLink(jobLink: string): void {
    this.api.getJobByLink(jobLink).subscribe({
      next: (job) => {
        this.job = {
          jobLink: job?.jobLink || jobLink,
          title: job?.title || '',
          type: job?.type || '',
          level: job?.level || ''
        };

        this.selectedCompany = job?.company?.name ? { name: job.company.name } : null;
        this.searchCompanyText = this.selectedCompany?.name || '';

        this.selectedLocation = job?.location?.name ? { name: job.location.name } : null;
        this.searchLocationText = this.selectedLocation?.name || '';

        this.selectedSkills = Array.isArray(job?.skills)
          ? job.skills
              .map((s: any) => ({ name: typeof s === 'string' ? s : s?.name }))
              .filter((s: any) => !!s.name)
          : [];
      },
      error: () => {
        this.router.navigate(['/jobs']);
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initSkillSearch(): void {
    this.skillSearch$
      .pipe(
        map((value) => (value || '').trim()),
        debounceTime(300),
        distinctUntilChanged(),
        tap((query) => {
          this.isSearchingSkills = query.length >= 1;
        }),
        switchMap((query) => {
          if (query.length < 1) {
            return of({ content: [] as any[] });
          }
          return this.api.searchSkillsByName(query, 0, 20).pipe(
            catchError(() => of({ content: [] as any[] }))
          );
        }),
        takeUntil(this.destroy$)
      )
      .subscribe((data) => {
        this.filteredSkills = this.extractList<any>(data).filter((s) =>
          !this.selectedSkills.some((sel) => sel.name?.toLowerCase() === s.name?.toLowerCase())
        );
        this.isSearchingSkills = false;
      });
  }

  private initCompanySearch(): void {
    this.companySearch$
      .pipe(
        map((value) => (value || '').trim()),
        debounceTime(300),
        distinctUntilChanged(),
        tap((query) => {
          this.isSearchingCompanies = query.length >= 1;
        }),
        switchMap((query) => {
          if (query.length < 1) {
            return of({ content: [] as any[] });
          }
          return this.api.searchCompaniesByName(query, 0, 20).pipe(
            catchError(() => of({ content: [] as any[] }))
          );
        }),
        takeUntil(this.destroy$)
      )
      .subscribe((data) => {
        this.filteredCompanies = this.extractList<any>(data);
        this.isSearchingCompanies = false;
      });
  }

  private initLocationSearch(): void {
    this.locationSearch$
      .pipe(
        map((value) => (value || '').trim()),
        debounceTime(300),
        distinctUntilChanged(),
        tap((query) => {
          this.isSearchingLocations = query.length >= 1;
        }),
        switchMap((query) => {
          if (query.length < 1) {
            return of({ content: [] as any[] });
          }
          return this.api.searchLocationsByName(query, 0, 20).pipe(
            catchError(() => of({ content: [] as any[] }))
          );
        }),
        takeUntil(this.destroy$)
      )
      .subscribe((data) => {
        this.filteredLocations = this.extractList<any>(data);
        this.isSearchingLocations = false;
      });
  }

  magicFill() {
    const text = this.aiRawText?.trim() || '';
    this.magicFillError = '';
    this.magicFillWarnings = [];

    if (!text) {
      this.magicFillError = 'Collez une description de poste avant de lancer le parsing IA.';
      return;
    }

    this.isMagicFilling = true;
    this.api.parseJobFromDescription(text).subscribe({
      next: (result) => {
        this.isMagicFilling = false;
        this.applyMagicFill(result);
      },
      error: () => {
        this.isMagicFilling = false;
        this.magicFillError = 'Le parsing IA est temporairement indisponible. Vous pouvez continuer en mode manuel.';
      }
    });
  }

  private applyMagicFill(result: SmartJobParseResponse) {
    this.magicFillError = '';
    this.magicFillWarnings = result?.warnings || [];
    this.fieldEvidences = result?.evidences || {};
    this.skillEvidences = this.indexSkillEvidences(result?.skillSuggestions || []);

    const parsedJob = result?.job;
    if (!parsedJob) {
      if (!result?.success && this.magicFillWarnings.length === 0) {
        this.magicFillError = 'Aucune suggestion exploitable. Continuez en saisie manuelle.';
      }
      return;
    }

    if (parsedJob.jobLink) this.job.jobLink = parsedJob.jobLink;
    if (parsedJob.title) this.job.title = parsedJob.title;
    if (parsedJob.type) this.job.type = parsedJob.type;
    if (parsedJob.level) this.job.level = parsedJob.level;

    if (parsedJob.company?.name) {
      this.selectedCompany = { name: parsedJob.company.name };
      this.searchCompanyText = parsedJob.company.name;
    }

    if (parsedJob.location?.name) {
      this.selectedLocation = { name: parsedJob.location.name };
      this.searchLocationText = parsedJob.location.name;
    }

    if (Array.isArray(parsedJob.skills)) {
      for (const skill of parsedJob.skills) {
        const skillName = (skill?.name || '').trim();
        if (!skillName) continue;
        if (!this.selectedSkills.some(s => s.name?.toLowerCase() === skillName.toLowerCase())) {
          this.selectedSkills.push({ name: skillName });
        }
      }
    }

    if (!result.success && this.magicFillWarnings.length === 0) {
      this.magicFillError = 'Parsing termine sans extraction fiable complete. Vous pouvez ajuster manuellement.';
    }
  }

  private indexSkillEvidences(suggestions: SmartJobFieldSuggestion[]): Record<string, string> {
    const indexed: Record<string, string> = {};
    for (const suggestion of suggestions) {
      const key = (suggestion.value || '').trim().toLowerCase();
      if (!key || indexed[key]) continue;
      indexed[key] = suggestion.evidence || '';
    }
    return indexed;
  }

  getEvidence(fieldPath: string): string {
    return this.fieldEvidences[fieldPath] || '';
  }

  getSkillEvidence(skillName: string): string {
    return this.skillEvidences[(skillName || '').toLowerCase()] || '';
  }

  onSkillInputChange() {
    this.skillSearch$.next(this.searchSkillText || '');
  }

  onSkillInputFocus() {
    if (this.closeSkillSearchHandle) {
      clearTimeout(this.closeSkillSearchHandle);
      this.closeSkillSearchHandle = null;
    }
  }

  closeSkillSearch() {
    this.closeSkillSearchHandle = setTimeout(() => {
      this.filteredSkills = [];
      this.isSearchingSkills = false;
    }, 200);
  }

  selectSkill(skill: any) {
    if (!this.selectedSkills.some(s => s.name?.toLowerCase() === skill.name?.toLowerCase())) {
      this.selectedSkills.push({ name: skill.name });
    }
    this.clearAndFocusSkill();
  }

  addSkillOnEnter(event: Event) {
    event.preventDefault();
    const text = this.searchSkillText?.trim() || '';
    if (!text) return;

    const exactMatch = this.filteredSkills.find(s => s.name?.toLowerCase() === text.toLowerCase());
    if (exactMatch) {
      this.selectSkill(exactMatch);
      return;
    }

    this.api.resolveSkillName(text).subscribe({
      next: (resolved) => {
        const canonical = (resolved?.canonical || text).trim();
        if (!this.selectedSkills.some(s => s.name?.toLowerCase() === canonical.toLowerCase())) {
          this.selectedSkills.push({ name: canonical });
        }
        this.clearAndFocusSkill();
      },
      error: () => {
        if (!this.selectedSkills.some(s => s.name?.toLowerCase() === text.toLowerCase())) {
          this.selectedSkills.push({ name: text });
        }
        this.clearAndFocusSkill();
      }
    });
  }

  clearAndFocusSkill() {
    this.searchSkillText = '';
    this.filteredSkills = [];
    this.isSearchingSkills = false;
    setTimeout(() => {
      const input = document.getElementById('jobSkillSearchInput');
      if (input) {
        input.focus();
      }
    }, 10);
  }

  onCompanyInputChange() {
    this.companySearch$.next(this.searchCompanyText || '');
  }

  onCompanyInputFocus() {
    if (this.closeCompanySearchHandle) {
      clearTimeout(this.closeCompanySearchHandle);
      this.closeCompanySearchHandle = null;
    }
  }

  closeCompanySearch() {
    this.closeCompanySearchHandle = setTimeout(() => {
      this.filteredCompanies = [];
      this.isSearchingCompanies = false;
      this.searchCompanyText = this.selectedCompany?.name || '';
    }, 200);
  }

  selectCompany(company: any) {
    this.selectedCompany = { name: company.name };
    this.searchCompanyText = company.name;
    this.filteredCompanies = [];
    this.isSearchingCompanies = false;
  }

  addCompanyOnEnter(event: Event) {
    event.preventDefault();
    const text = this.searchCompanyText?.trim() || '';
    if (!text) return;

    const exactMatch = this.filteredCompanies.find(c => c.name?.toLowerCase() === text.toLowerCase());
    this.selectCompany(exactMatch || { name: text });
  }

  onLocationInputChange() {
    this.locationSearch$.next(this.searchLocationText || '');
  }

  onLocationInputFocus() {
    if (this.closeLocationSearchHandle) {
      clearTimeout(this.closeLocationSearchHandle);
      this.closeLocationSearchHandle = null;
    }
  }

  closeLocationSearch() {
    this.closeLocationSearchHandle = setTimeout(() => {
      this.filteredLocations = [];
      this.isSearchingLocations = false;
      this.searchLocationText = this.selectedLocation?.name || '';
    }, 200);
  }

  selectLocation(location: any) {
    this.selectedLocation = { name: location.name };
    this.searchLocationText = location.name;
    this.filteredLocations = [];
    this.isSearchingLocations = false;
  }

  addLocationOnEnter(event: Event) {
    event.preventDefault();
    const text = this.searchLocationText?.trim() || '';
    if (!text) return;

    const exactMatch = this.filteredLocations.find(l => l.name?.toLowerCase() === text.toLowerCase());
    this.selectLocation(exactMatch || { name: text });
  }

  save() {
    this.saveError = '';

    const payload = {
      jobLink: this.job.jobLink,
      title: this.job.title,
      type: this.job.type,
      level: this.job.level,
      company: this.selectedCompany ? { name: this.selectedCompany.name } : null,
      location: this.selectedLocation ? { name: this.selectedLocation.name } : null,
      skills: this.selectedSkills.map(s => ({ name: s.name }))
    };

    console.log('Payload job envoyé:', payload);

    if (!payload.jobLink?.trim() || !payload.title?.trim()) {
      this.saveError = 'Le lien et le titre sont obligatoires pour enregistrer le job.';
      return;
    }

    const request$ = this.isEditMode
      ? (this.editingJobLink
          ? this.api.updateJobByLink(this.editingJobLink, payload)
          : this.api.updateJob(this.editingJobId, payload))
      : this.api.createJob(payload);

    this.isSaving = true;
    request$.subscribe({
      next: () => {
        this.isSaving = false;
        this.router.navigate(['/jobs'], {
          queryParams: this.isEditMode ? { updated: 1 } : { created: 1 }
        });
      },
      error: () => {
        this.isSaving = false;
        this.saveError = "L'enregistrement a échoué. Vérifiez les données puis réessayez.";
      }
    });
  }
}
