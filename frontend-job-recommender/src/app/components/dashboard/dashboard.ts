import { Component, OnDestroy, OnInit, ElementRef, ViewChild, AfterViewInit } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { RouterLink } from "@angular/router";
import { Api, CareerPath, DataDrift, DataFunnel, Skill, SkillCentrality, SkillCommunity, SkillSimilarityResult } from "../../services/api";
import { of, Subject } from "rxjs";
import { catchError, debounceTime, distinctUntilChanged, map, switchMap, takeUntil, tap } from "rxjs/operators";
import * as d3 from "d3";

@Component({
  selector: "app-dashboard",
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: "./dashboard.html",
  styleUrl: "./dashboard.css",
})
export class Dashboard implements OnInit, OnDestroy {
  @ViewChild("miniGraph", { static: false }) graphContainer!: ElementRef;
  
  stats: any = { totalJobs: 0, totalSkills: 0, totalCandidates: 0 };
  dataQuality: any = null;
  dataFunnel: DataFunnel | null = null;
  dataDrift: DataDrift | null = null;
  candidates: any[] = [];
  selectedCandidateId: string = "";
  recommendations: any[] = [];
  careerPaths: CareerPath[] = [];
  isLoadingRecommendations: boolean = false;
  isLoadingCareerPaths: boolean = false;

  skillCentrality: SkillCentrality[] = [];
  skillCommunities: SkillCommunity[] = [];
  isLoadingGraphAnalytics: boolean = false;

  semanticSkillQuery: string = "";
  semanticSkillSuggestions: Skill[] = [];
  selectedSemanticSkill: Skill | null = null;
  similarSkills: SkillSimilarityResult[] = [];
  isSearchingSemanticSkills: boolean = false;
  isLoadingSimilarSkills: boolean = false;
  private destroy$ = new Subject<void>();
  private semanticSkillSearch$ = new Subject<string>();
  private closeSemanticSuggestionsHandle: any = null;

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

  constructor(private api: Api) {}

  ngOnInit(): void {
    this.loadStats();
    this.loadDataQuality();
    this.loadAdvancedAnalytics();
    this.loadCandidates();
    this.loadGraphAnalytics();
    this.initSemanticSkillSearch();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initSemanticSkillSearch(): void {
    this.semanticSkillSearch$
      .pipe(
        map((value) => (value || "").trim()),
        debounceTime(300),
        distinctUntilChanged(),
        tap((query) => {
          this.isSearchingSemanticSkills = query.length >= 1;
        }),
        switchMap((query) => {
          if (query.length < 1) {
            return of({ content: [] as Skill[] });
          }
          return this.api.searchSkillsByName(query, 0, 10).pipe(
            catchError(() => of({ content: [] as Skill[] }))
          );
        }),
        takeUntil(this.destroy$)
      )
      .subscribe((data) => {
        this.semanticSkillSuggestions = this.extractList<Skill>(data);
        this.isSearchingSemanticSkills = false;
      });
  }

  loadAdvancedAnalytics(): void {
    this.api.getDataFunnel().subscribe({
      next: (rows) => {
        this.dataFunnel = rows;
      },
      error: () => {
        this.dataFunnel = null;
      }
    });

    this.api.getDataDrift().subscribe({
      next: (rows) => {
        this.dataDrift = rows;
      },
      error: () => {
        this.dataDrift = null;
      }
    });
  }

  driftRiskClass(risk: string | undefined): string {
    switch (risk) {
      case "LOW":
        return "text-success";
      case "MEDIUM":
        return "text-warning";
      case "HIGH":
        return "text-danger";
      default:
        return "text-muted";
    }
  }

  loadGraphAnalytics(): void {
    this.isLoadingGraphAnalytics = true;

    this.api.getSkillCentrality(6).subscribe({
      next: (rows) => {
        this.skillCentrality = rows || [];
      },
      error: () => {
        this.skillCentrality = [];
      }
    });

    this.api.getSkillCommunities({ limit: 4, minCooccurrence: 3, minDegree: 3, topNeighbors: 5 }).subscribe({
      next: (rows) => {
        this.skillCommunities = rows || [];
        this.isLoadingGraphAnalytics = false;
      },
      error: () => {
        this.skillCommunities = [];
        this.isLoadingGraphAnalytics = false;
      }
    });
  }

  onSemanticSkillInputChange(): void {
    this.semanticSkillSearch$.next(this.semanticSkillQuery || "");
  }

  onSemanticSkillInputFocus(): void {
    if (this.closeSemanticSuggestionsHandle) {
      clearTimeout(this.closeSemanticSuggestionsHandle);
      this.closeSemanticSuggestionsHandle = null;
    }
  }

  closeSemanticSkillSuggestions(): void {
    this.closeSemanticSuggestionsHandle = setTimeout(() => {
      this.semanticSkillSuggestions = [];
      this.isSearchingSemanticSkills = false;
    }, 200);
  }

  selectSemanticSkill(skill: Skill): void {
    this.selectedSemanticSkill = skill;
    this.semanticSkillQuery = skill.name;
    this.semanticSkillSuggestions = [];
    this.fetchSimilarSkills(skill.name);
  }

  runSemanticSkillAnalysisFromInput(event: Event): void {
    event.preventDefault();
    const text = this.semanticSkillQuery?.trim();
    if (!text) {
      return;
    }

    const exact = this.semanticSkillSuggestions.find(s => s.name?.toLowerCase() === text.toLowerCase());
    if (exact) {
      this.selectSemanticSkill(exact);
      return;
    }

    const synthetic: Skill = { name: text };
    this.selectSemanticSkill(synthetic);
  }

  fetchSimilarSkills(skillName: string): void {
    this.isLoadingSimilarSkills = true;
    this.api.getSimilarSkillsByName(skillName, 8).subscribe({
      next: (rows) => {
        this.similarSkills = (rows || []).filter(r => r?.skill?.name);
        this.isLoadingSimilarSkills = false;
      },
      error: () => {
        this.similarSkills = [];
        this.isLoadingSimilarSkills = false;
      }
    });
  }

  similarityPercent(score: number | undefined): number {
    if (score === undefined || score === null) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round(score * 100)));
  }

  similarityBadgeClass(score: number | undefined): string {
    const pct = this.similarityPercent(score);
    if (pct >= 90) {
      return "bg-success";
    }
    if (pct >= 80) {
      return "bg-primary";
    }
    if (pct >= 70) {
      return "bg-warning text-dark";
    }
    return "bg-secondary";
  }

  loadStats(): void {
    this.api.getStats().subscribe({
      next: (data) => this.stats = data,
      error: (err) => console.error("Error loading stats", err)
    });
  }

  loadDataQuality(): void {
    this.api.getDataQualitySnapshot().subscribe({
      next: (data) => this.dataQuality = data,
      error: (err) => console.error("Error loading data quality", err)
    });
  }

  getDataQualityStatusClass(status: string | undefined): string {
    switch (status) {
      case "HEALTHY":
        return "text-success";
      case "WARNING":
        return "text-warning";
      case "CRITICAL":
        return "text-danger";
      default:
        return "text-muted";
    }
  }

  loadCandidates(): void {
    this.api.getCandidates(0, 100).subscribe({
      next: (data) => {
        this.candidates = data.content || [];
      },
      error: (err) => console.error("Error loading candidates", err)
    });
  }

  onCandidateSelected(): void {
    if (!this.selectedCandidateId) {
      this.recommendations = [];
      this.careerPaths = [];
      d3.select(this.graphContainer.nativeElement).selectAll("*").remove();
      return;
    }
    
    this.isLoadingRecommendations = true;
    this.api.getRecommendations(this.selectedCandidateId).subscribe({
      next: (data) => {
        this.recommendations = data;
        this.isLoadingRecommendations = false;
        setTimeout(() => this.drawMiniGraph(), 100);
      },
      error: (err) => {
        console.error("Error loading recommendations", err);
        this.isLoadingRecommendations = false;
      }
    });

    this.isLoadingCareerPaths = true;
    this.api.getCareerPaths(this.selectedCandidateId, 5).subscribe({
      next: (rows) => {
        this.careerPaths = rows || [];
        this.isLoadingCareerPaths = false;
      },
      error: () => {
        this.careerPaths = [];
        this.isLoadingCareerPaths = false;
      }
    });
  }

  hasSkill(reqSkill: any): boolean {
    const candidate = this.candidates.find(c => c.id === this.selectedCandidateId);
    if (!candidate || !candidate.skills) return false;
    return candidate.skills.some((s: any) => s.name === reqSkill.name);
  }

  drawMiniGraph(): void {
    if (!this.graphContainer) return;
    
    const container = this.graphContainer.nativeElement;
    d3.select(container).selectAll("*").remove();

    if (!this.recommendations || this.recommendations.length === 0) return;

    const width = container.clientWidth || 400;
    const height = 300;

    const svg = d3.select(container).append("svg")
      .attr("width", "100%")
      .attr("height", height);

    const nodes: any[] = [];
    const links: any[] = [];

    const candidate = this.candidates.find(c => c.id === this.selectedCandidateId);
    const candidateName = candidate ? candidate.name || "Candidat" : "Candidat";
    
    // Add central candidate node
    nodes.push({ id: "candidate", name: candidateName, group: 1 });
    
    if (candidate && candidate.skills) {
      candidate.skills.forEach((s: any) => {
        const sid = "skill_" + s.name;
        if (!nodes.find(n => n.id === sid)) {
          nodes.push({ id: sid, name: s.name, group: 2 });
        }
        links.push({ source: "candidate", target: sid });
      });
    }

    this.recommendations.slice(0, 5).forEach((rec, index) => {
      const jobId = "job_" + index;
      nodes.push({ id: jobId, name: rec.job.title, group: 3 });

      const explicitSkillNames = Array.isArray(rec?.job?.skills)
        ? rec.job.skills
            .map((s: any) => typeof s === "string" ? s : s?.name)
            .filter((name: string) => !!name)
        : [];

      const fallbackSkillNames = Array.isArray(rec?.matchedSkillsList)
        ? rec.matchedSkillsList.filter((name: string) => !!name)
        : [];

      const jobSkillNames = explicitSkillNames.length > 0 ? explicitSkillNames : fallbackSkillNames;

      jobSkillNames.forEach((name: string) => {
        const sid = "skill_" + name;
        if (!nodes.find(n => n.id === sid)) {
          nodes.push({ id: sid, name, group: 2 });
        }
        links.push({ source: jobId, target: sid });
      });
    });

    const simulation = d3.forceSimulation(nodes)
      .force("link", d3.forceLink(links).id((d: any) => d.id).distance(60))
      .force("charge", d3.forceManyBody().strength(-150))
      .force("center", d3.forceCenter(width / 2, height / 2));

    const link = svg.append("g")
      .selectAll("line")
      .data(links)
      .enter().append("line")
      .attr("stroke", "#999")
      .attr("stroke-width", 1);

    const node = svg.append("g")
      .selectAll("circle")
      .data(nodes)
      .enter().append("circle")
      .attr("r", 8)
      .attr("fill", (d: any) => d.group === 1 ? "#0dcaf0" : (d.group === 2 ? "#198754" : "#fd7e14"));

    node.append("title").text((d: any) => d.name);

    simulation.on("tick", () => {
      link
        .attr("x1", (d: any) => d.source.x)
        .attr("y1", (d: any) => d.source.y)
        .attr("x2", (d: any) => d.target.x)
        .attr("y2", (d: any) => d.target.y);

      node
        .attr("cx", (d: any) => d.x = Math.max(8, Math.min(width - 8, d.x)))
        .attr("cy", (d: any) => d.y = Math.max(8, Math.min(height - 8, d.y)));
    });
  }
}

