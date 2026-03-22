import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api } from '../../services/api';
import { Router } from '@angular/router';
import { forkJoin, of, Subject } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, map, switchMap, takeUntil, tap } from 'rxjs/operators';

@Component({
  selector: 'app-candidate-form',
  imports: [CommonModule, FormsModule],
  templateUrl: './candidate-form.html',
  styleUrl: './candidate-form.css'
})
export class CandidateForm implements OnInit, OnDestroy {
  candidate: any = {
    name: '',
    email: '',
    resumePath: '',
    skills: []
  };
  searchSkillText: string = '';
  filteredSkills: any[] = [];
  selectedSkills: any[] = [];
  isSearchingSkills: boolean = false;
  private destroy$ = new Subject<void>();
  private skillSearch$ = new Subject<string>();
  private closeSearchHandle: any = null;

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

  constructor(private api: Api, private router: Router) {}

  ngOnInit(): void {
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
        const results = this.extractList<any>(data);
        this.filteredSkills = results.filter((s) => !this.isSkillSelected(s));
        this.isSearchingSkills = false;
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  selectSkill(skill: any) {
    if (!this.selectedSkills.some(s => s?.name?.toLowerCase() === skill?.name?.toLowerCase())) {
      this.selectedSkills.push({ name: skill.name });
    }
    this.clearAndFocus();
  }

  isSkillSelected(skill: any) {
    return !!this.selectedSkills.find(s => s?.name?.toLowerCase() === skill?.name?.toLowerCase());
  }

  onSkillInputChange() {
    this.skillSearch$.next(this.searchSkillText || '');
  }

  onSkillInputFocus() {
    if (this.closeSearchHandle) {
      clearTimeout(this.closeSearchHandle);
      this.closeSearchHandle = null;
    }
  }

  closeSearch() {
    this.closeSearchHandle = setTimeout(() => {
      this.filteredSkills = [];
      this.isSearchingSkills = false;
    }, 200);
  }

  addNewSkill() {
    if (!this.searchSkillText || !this.searchSkillText.trim()) return;
    const newSkillName = this.searchSkillText.trim();
    if (!this.selectedSkills.some(s => s?.name?.toLowerCase() === newSkillName.toLowerCase())) {
      this.selectedSkills.push({ name: newSkillName });
    }
    this.clearAndFocus();
  }

  addSkillOnEnter(event: Event) {
    event.preventDefault();
    const text = this.searchSkillText ? this.searchSkillText.trim() : '';
    if (!text) return;

    const exactMatch = this.filteredSkills.find(s => s.name?.toLowerCase() === text.toLowerCase());
    
    if (exactMatch) {
      this.selectSkill(exactMatch);
    } else {
      this.api.resolveSkillName(text).subscribe({
        next: (resolved) => {
          const canonical = (resolved?.canonical || text).trim();
          if (!this.selectedSkills.some(s => s?.name?.toLowerCase() === canonical.toLowerCase())) {
            this.selectedSkills.push({ name: canonical });
          }
          this.clearAndFocus();
        },
        error: () => {
          if (!this.selectedSkills.some(s => s?.name?.toLowerCase() === text.toLowerCase())) {
            this.selectedSkills.push({ name: text });
          }
          this.clearAndFocus();
        }
      });
    }
  }

  clearAndFocus() {
    this.searchSkillText = '';
    this.filteredSkills = [];
    this.isSearchingSkills = false;
    setTimeout(() => {
      const input = document.getElementById('skillSearchInput');
      if (input) {
        input.focus();
      }
    }, 10);
  }

  isSkillExactMatch() {
    if(!this.searchSkillText) return false;
    const text = this.searchSkillText.trim().toLowerCase();
    return this.filteredSkills.some(s => s.name?.toLowerCase() === text);
  }

  save() {
    const payload = {
      name: this.candidate.name,
      email: this.candidate.email,
      resumePath: this.candidate.resumePath || ''
    };

    console.log('Payload candidat envoyé:', payload);

    this.api.createCandidate(payload).subscribe({
      next: (newCand) => {
        const seenNames = new Set<string>();
        const normalizedSkillNames = this.selectedSkills
          .map(s => (s?.name || '').trim())
          .filter((name: string) => !!name)
          .filter((name: string) => {
            const key = name.toLowerCase();
            if (seenNames.has(key)) {
              return false;
            }
            seenNames.add(key);
            return true;
          });

        if (normalizedSkillNames.length === 0) {
          this.router.navigate(['/candidates']);
          return;
        }

        const attachRequests = normalizedSkillNames.map((skillName: string) =>
          this.api.addSkillNameToCandidate(newCand.id, skillName)
        );

        forkJoin(attachRequests).subscribe({
          next: () => this.router.navigate(['/candidates']),
          error: (err) => console.error('Erreur attachement skills au candidat:', err)
        });
      },
      error: (err) => console.error('Erreur création candidat:', err)
    });
  }
}
