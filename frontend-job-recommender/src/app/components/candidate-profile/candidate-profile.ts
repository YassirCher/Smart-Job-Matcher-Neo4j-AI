import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import {
  Api,
  BehavioralProfileResponse,
  CareerPathPredictionSkill,
  InterviewQuestion,
  InterviewScriptJobResponse,
  SoftSkillAnalysisJobResponse,
  SoftSkillEvidence,
  GitHubProfileAnalysisResult,
  ResumePortfolioDelta,
  ResumePortfolioUploadResponse,
  SemanticRecommendation,
  SkillGapRoadmapRow
} from '../../services/api';

@Component({
  selector: 'app-candidate-profile',
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './candidate-profile.html',
  styleUrl: './candidate-profile.css'
})
export class CandidateProfile implements OnInit, OnDestroy {
  candidate: any = null;
  candidateId: string | null = null;
  isCandidateLoading: boolean = false;
  candidateLoadError: string = '';
  recommendations: any[] = [];
  semanticRecommendations: SemanticRecommendation[] = [];
  skillGapRoadmap: SkillGapRoadmapRow[] = [];
  careerPathPredictions: CareerPathPredictionSkill[] = [];
  careerCoachMessage: string = '';
  coachThinkDetected: boolean = false;
  coachReasoning: string = '';
  showCoachReasoning: boolean = false;
  coachReasoningDisplay: string = '';
  isCoachReasoningTyping: boolean = false;
  isCareerPredictionLoading: boolean = false;
  isCareerCoachLoading: boolean = false;
  careerPredictorError: string = '';
  topSkillsDemand: any[] = [];
  rarityScore: number = 0;

  resumeFile: File | null = null;
  isResumeDragOver: boolean = false;
  autoAnalyzeGithubAfterCv: boolean = false;
  isResumeProcessing: boolean = false;
  isDetectedGithubAnalyzing: boolean = false;
  isPortfolioApplying: boolean = false;
  portfolioProgressMessage: string = '';
  portfolioError: string = '';
  portfolioSuccess: string = '';
  resumeIntel: ResumePortfolioUploadResponse | null = null;
  resumeDelta: ResumePortfolioDelta | null = null;
  portfolioGithubAnalysis: GitHubProfileAnalysisResult | null = null;
  selectedPortfolioSkills: Set<string> = new Set<string>();

  selectedInterviewQuestionCount: number = 4;
  isInterviewGenerating: boolean = false;
  interviewError: string = '';
  interviewInfoMessage: string = '';
  interviewJob: InterviewScriptJobResponse | null = null;
  interviewQuestions: InterviewQuestion[] = [];

  isBehavioralProfileLoading: boolean = false;
  behavioralProfileError: string = '';
  behavioralProfileInfoMessage: string = '';
  behavioralAttachSuccess: string = '';
  behavioralAttachError: string = '';
  isBehavioralAttachAllLoading: boolean = false;
  attachingBehavioralSkillNames: Set<string> = new Set<string>();
  behavioralProfileJob: SoftSkillAnalysisJobResponse | null = null;
  behavioralProfile: BehavioralProfileResponse | null = null;
  expandedSoftSkillEvidence: Set<string> = new Set<string>();

  private interviewPollTimer: ReturnType<typeof setTimeout> | null = null;
  private behavioralProfilePollTimer: ReturnType<typeof setTimeout> | null = null;
  private coachReasoningTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private route: ActivatedRoute, private api: Api) {}

  ngOnInit(): void {
    this.candidateId = this.route.snapshot.paramMap.get('id');
    if (this.candidateId) {
      const id = this.candidateId;
      this.isCandidateLoading = true;
      this.candidateLoadError = '';
      this.api.getCandidate(id).subscribe(c => {
        this.candidate = c;
        this.rarityScore = Math.min(100, Math.round((c.skills?.length || 0) * 8.5) + 15);
        this.isCandidateLoading = false;
      }, () => {
        this.candidate = {
          id,
          name: 'Profil indisponible',
          email: '',
          skills: []
        };
        this.isCandidateLoading = false;
        this.candidateLoadError = 'Impossible de charger le profil candidat. Les modules IA restent disponibles si les autres endpoints repondent.';
      });
      this.api.getRecommendations(id).subscribe(recs => {
        this.recommendations = recs;
        this.calculateTopSkills(recs);
      });
      this.api.getSemanticRecommendations(id, { threshold: 0.8, topJobs: 5, topKPerSkill: 20, embeddingDim: 384 }).subscribe({
        next: (rows) => this.semanticRecommendations = rows || [],
        error: () => this.semanticRecommendations = []
      });

      this.api.getSkillGapRoadmap(id, 5).subscribe({
        next: (rows) => this.skillGapRoadmap = rows || [],
        error: () => this.skillGapRoadmap = []
      });

      this.api.getBehavioralProfile(id).subscribe({
        next: (profile) => {
          this.behavioralProfile = profile;
        },
        error: () => {
          this.behavioralProfile = {
            candidateId: id,
            totalSoftSkills: 0,
            softSkills: []
          };
        }
      });

      this.loadCareerPathPredictor(id);
    }
  }

  ngOnDestroy(): void {
    this.clearInterviewPollingTimer();
    this.clearBehavioralProfilePollingTimer();
    this.stopCoachReasoningTyping();
  }

  loadCareerPathPredictor(candidateId: string): void {
    this.careerPredictorError = '';
    this.careerCoachMessage = '';
    this.coachThinkDetected = false;
    this.coachReasoning = '';
    this.coachReasoningDisplay = '';
    this.showCoachReasoning = false;
    this.stopCoachReasoningTyping();
    this.careerPathPredictions = [];
    this.isCareerPredictionLoading = true;
    this.isCareerCoachLoading = true;

    this.api.getCareerPathPrediction(candidateId, { topK: 3, maxRemainingGaps: 1, withCoaching: true }).subscribe({
      next: (res) => {
        this.careerPathPredictions = res.recommendedSkills || [];
        const parsed = this.parseCoachMessage(res.coachingMessage || '');
        this.coachThinkDetected = parsed.thinkDetected;
        this.coachReasoning = parsed.reasoning;
        this.careerCoachMessage = parsed.message;
        if (!this.coachThinkDetected) {
          this.showCoachReasoning = false;
          this.coachReasoningDisplay = '';
          this.stopCoachReasoningTyping();
        }
        this.isCareerPredictionLoading = false;
        this.isCareerCoachLoading = false;
      },
      error: () => {
        this.careerPathPredictions = [];
        this.careerCoachMessage = '';
        this.coachThinkDetected = false;
        this.coachReasoning = '';
        this.coachReasoningDisplay = '';
        this.showCoachReasoning = false;
        this.stopCoachReasoningTyping();
        this.isCareerPredictionLoading = false;
        this.isCareerCoachLoading = false;
        this.careerPredictorError = 'Career Path Predictor indisponible pour le moment.';
      }
    });
  }

  toggleCoachReasoning(): void {
    this.showCoachReasoning = !this.showCoachReasoning;
    if (this.showCoachReasoning) {
      this.startCoachReasoningTyping();
    } else {
      this.stopCoachReasoningTyping();
      this.coachReasoningDisplay = '';
    }
  }

  private startCoachReasoningTyping(): void {
    this.stopCoachReasoningTyping();

    const text = (this.coachReasoning || '').trim();
    if (!text) {
      this.coachReasoningDisplay = '';
      return;
    }

    this.coachReasoningDisplay = '';
    this.isCoachReasoningTyping = true;
    let cursor = 0;

    this.coachReasoningTimer = setInterval(() => {
      cursor = Math.min(text.length, cursor + 3);
      this.coachReasoningDisplay = text.slice(0, cursor);
      if (cursor >= text.length) {
        this.stopCoachReasoningTyping();
      }
    }, 18);
  }

  private stopCoachReasoningTyping(): void {
    if (this.coachReasoningTimer !== null) {
      clearInterval(this.coachReasoningTimer);
      this.coachReasoningTimer = null;
    }
    this.isCoachReasoningTyping = false;
  }

  private parseCoachMessage(raw: string): { message: string; reasoning: string; thinkDetected: boolean } {
    if (!raw) {
      return { message: '', reasoning: '', thinkDetected: false };
    }

    const normalizedRaw = raw.replace(/\r/g, '');
    const thinkPattern = /<think[\s\S]*?>([\s\S]*?)<\/think>/gi;
    const reasoningParts: string[] = [];
    let match: RegExpExecArray | null;
    while ((match = thinkPattern.exec(normalizedRaw)) !== null) {
      const part = (match[1] || '').trim();
      if (part) {
        reasoningParts.push(part);
      }
    }

    const thinkDetected = reasoningParts.length > 0;
    let cleaned = normalizedRaw.replace(/<think[\s\S]*?>[\s\S]*?<\/think>/gi, ' ');
    cleaned = cleaned.replace(/<\/?think>/gi, ' ');
    cleaned = cleaned.replace(/\n{3,}/g, '\n\n').trim();

    return {
      message: cleaned,
      reasoning: reasoningParts.join('\n\n').trim(),
      thinkDetected
    };
  }

  onResumeFileInputChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) {
      return;
    }
    this.resumeFile = input.files[0];
  }

  onResumeDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isResumeDragOver = true;
  }

  onResumeDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.isResumeDragOver = false;
  }

  onResumeDrop(event: DragEvent): void {
    event.preventDefault();
    this.isResumeDragOver = false;
    const dropped = event.dataTransfer?.files;
    if (!dropped || dropped.length === 0) {
      return;
    }
    this.resumeFile = dropped[0];
  }

  startResumePortfolioAnalysis(): void {
    if (!this.candidateId) {
      return;
    }
    if (!this.resumeFile) {
      this.portfolioError = 'Veuillez selectionner un CV (PDF/DOC/DOCX).';
      return;
    }

    this.portfolioError = '';
    this.portfolioSuccess = '';
    this.portfolioProgressMessage = 'Extraction du CV en cours...';
    this.isResumeProcessing = true;
    this.resumeIntel = null;
    this.resumeDelta = null;
    this.portfolioGithubAnalysis = null;
    this.selectedPortfolioSkills.clear();
    this.resetDynamicInterviewState();
    this.resetBehavioralProfileRunState();

    this.api.uploadResumePortfolioIntelligence(this.candidateId, this.resumeFile, this.autoAnalyzeGithubAfterCv).subscribe({
      next: (res) => {
        this.resumeIntel = res;
        this.resumeDelta = {
          validatedSkills: res.validatedSkills || [],
          claimedButUnverified: res.claimedButUnverified || [],
          hiddenGems: res.hiddenGems || [],
          validatedCount: (res.validatedSkills || []).length,
          claimedButUnverifiedCount: (res.claimedButUnverified || []).length,
          hiddenGemsCount: (res.hiddenGems || []).length
        };
        this.portfolioGithubAnalysis = res.githubAnalysis || null;

        this.initializeSelectedSkillsFromDelta();

        if (res.githubDetected && res.githubAnalysisPending) {
          this.portfolioProgressMessage = `GitHub detecte (${res.detectedGithubUsername}). Cliquez sur "Analyser le portfolio GitHub" pour finaliser le delta.`;
        } else if (res.githubAnalysisTriggered) {
          this.portfolioProgressMessage = 'Analyse CV + GitHub terminee. Delta genere.';
        } else {
          this.portfolioProgressMessage = 'Analyse CV terminee. Delta preliminaire genere.';
        }

        this.isResumeProcessing = false;
      },
      error: (err) => {
        this.isResumeProcessing = false;
        this.portfolioProgressMessage = '';
        this.portfolioError = err?.error?.message || 'Echec de l\'analyse Resume & Portfolio.';
      }
    });
  }

  analyzeDetectedGithubPortfolio(): void {
    if (!this.candidateId || !this.resumeIntel?.detectedGithubUsername || !this.resumeIntel.cvClaimedSkills) {
      return;
    }

    this.portfolioError = '';
    this.portfolioSuccess = '';
    this.portfolioProgressMessage = `GitHub detecte : analyse des repositories de ${this.resumeIntel.detectedGithubUsername}...`;
    this.isDetectedGithubAnalyzing = true;

    this.api.analyzeCandidateGitHubProfile(this.candidateId, {
      githubUsername: this.resumeIntel.detectedGithubUsername,
      maxRepos: 12,
      includeForks: false
    }).subscribe({
      next: (analysis) => {
        this.portfolioGithubAnalysis = analysis;
        const githubSkills = (analysis.matched || []).map(m => m.matchedSkillName).filter(Boolean);

        this.api.computeResumePortfolioDelta(this.candidateId as string, this.resumeIntel?.cvClaimedSkills || [], githubSkills).subscribe({
          next: (delta) => {
            this.resumeDelta = delta;
            this.resumeIntel = {
              ...this.resumeIntel as ResumePortfolioUploadResponse,
              githubDetected: true,
              githubAnalysisTriggered: true,
              githubAnalysisPending: false,
              githubSkills,
              validatedSkills: delta.validatedSkills,
              claimedButUnverified: delta.claimedButUnverified,
              hiddenGems: delta.hiddenGems,
              githubAnalysis: analysis
            };
            this.initializeSelectedSkillsFromDelta();
            this.resetDynamicInterviewState();
            this.resetBehavioralProfileRunState();
            this.portfolioProgressMessage = 'Generation du Delta terminee.';
            this.isDetectedGithubAnalyzing = false;
          },
          error: (err) => {
            this.isDetectedGithubAnalyzing = false;
            this.portfolioError = err?.error?.message || 'Impossible de calculer le delta CV/GitHub.';
          }
        });
      },
      error: (err) => {
        this.isDetectedGithubAnalyzing = false;
        this.portfolioError = err?.error?.message || 'Analyse GitHub echouee.';
      }
    });
  }

  initializeSelectedSkillsFromDelta(): void {
    this.selectedPortfolioSkills.clear();
    if (!this.resumeDelta) {
      return;
    }

    for (const skill of this.resumeDelta.validatedSkills || []) {
      this.selectedPortfolioSkills.add(skill);
    }
    for (const skill of this.resumeDelta.hiddenGems || []) {
      this.selectedPortfolioSkills.add(skill);
    }
  }

  togglePortfolioSkill(skill: string, checked: boolean): void {
    if (!skill) {
      return;
    }

    if (checked) {
      this.selectedPortfolioSkills.add(skill);
    } else {
      this.selectedPortfolioSkills.delete(skill);
    }
  }

  isPortfolioSkillSelected(skill: string): boolean {
    return this.selectedPortfolioSkills.has(skill);
  }

  applyPortfolioEnrichment(): void {
    if (!this.candidateId) {
      return;
    }

    const selectedSkills = Array.from(this.selectedPortfolioSkills.values());
    if (selectedSkills.length === 0) {
      this.portfolioError = 'Selectionnez au moins une competence a enrichir.';
      return;
    }

    this.portfolioError = '';
    this.portfolioSuccess = '';
    this.isPortfolioApplying = true;

    this.api.applyResumePortfolioSkills(this.candidateId, selectedSkills).subscribe({
      next: (res) => {
        this.isPortfolioApplying = false;
        this.candidate = res.candidate;
        this.rarityScore = Math.min(100, Math.round((this.candidate.skills?.length || 0) * 8.5) + 15);
        this.portfolioSuccess = `${res.attachedSkills} competence(s) ont ete validees dans le graphe Neo4j.`;
      },
      error: (err) => {
        this.isPortfolioApplying = false;
        this.portfolioError = err?.error?.message || 'Validation finale de l\'enrichissement echouee.';
      }
    });
  }

  startDynamicInterviewGeneration(): void {
    if (!this.candidateId || !this.resumeDelta) {
      return;
    }

    const claimedButUnverified = this.resumeDelta.claimedButUnverified || [];
    if (claimedButUnverified.length === 0) {
      this.interviewError = 'Aucune competence "Claimed but Unverified" disponible pour generer un entretien.';
      return;
    }

    this.interviewError = '';
    this.interviewInfoMessage = 'Generation du script d\'entretien en file asynchrone...';
    this.isInterviewGenerating = true;
    this.interviewQuestions = [];
    this.interviewJob = null;
    this.clearInterviewPollingTimer();

    this.api.startDynamicInterviewScript(this.candidateId, {
      claimedButUnverified,
      targetQuestions: this.selectedInterviewQuestionCount
    }).subscribe({
      next: (job) => {
        this.handleInterviewJobUpdate(job);
      },
      error: (err) => {
        this.isInterviewGenerating = false;
        this.interviewError = err?.error?.message || 'Generation d\'entretien indisponible pour le moment.';
      }
    });
  }

  private handleInterviewJobUpdate(job: InterviewScriptJobResponse): void {
    this.interviewJob = job;
    this.interviewInfoMessage = job.message || '';

    if (job.status === 'completed') {
      this.isInterviewGenerating = false;
      this.interviewQuestions = job.questions || [];
      this.clearInterviewPollingTimer();
      return;
    }

    this.isInterviewGenerating = true;
    this.scheduleInterviewPolling(job.jobId, job.pollAfterMs || 1200);
  }

  private scheduleInterviewPolling(jobId: string, delayMs: number): void {
    this.clearInterviewPollingTimer();
    const safeDelay = Math.max(700, delayMs || 1200);

    this.interviewPollTimer = setTimeout(() => {
      if (!this.candidateId) {
        this.isInterviewGenerating = false;
        return;
      }

      this.api.getDynamicInterviewScriptJob(this.candidateId, jobId).subscribe({
        next: (job) => this.handleInterviewJobUpdate(job),
        error: (err) => {
          this.isInterviewGenerating = false;
          this.interviewError = err?.error?.message || 'Echec du suivi de generation d\'entretien.';
          this.clearInterviewPollingTimer();
        }
      });
    }, safeDelay);
  }

  private clearInterviewPollingTimer(): void {
    if (this.interviewPollTimer !== null) {
      clearTimeout(this.interviewPollTimer);
      this.interviewPollTimer = null;
    }
  }

  private resetDynamicInterviewState(): void {
    this.clearInterviewPollingTimer();
    this.isInterviewGenerating = false;
    this.interviewError = '';
    this.interviewInfoMessage = '';
    this.interviewJob = null;
    this.interviewQuestions = [];
  }

  startBehavioralProfileAnalysis(): void {
    if (!this.candidateId) {
      return;
    }

    this.behavioralProfileError = '';
    this.behavioralProfileInfoMessage = 'Analyse comportementale asynchrone demarree...';
    this.behavioralAttachError = '';
    this.behavioralAttachSuccess = '';
    this.isBehavioralProfileLoading = true;
    this.isBehavioralAttachAllLoading = false;
    this.attachingBehavioralSkillNames.clear();
    this.behavioralProfileJob = null;
    this.expandedSoftSkillEvidence.clear();
    this.clearBehavioralProfilePollingTimer();

    this.api.startBehavioralProfileAnalysis(this.candidateId, {}).subscribe({
      next: (job) => this.handleBehavioralProfileJobUpdate(job),
      error: (err) => {
        this.isBehavioralProfileLoading = false;
        this.behavioralProfileError = err?.error?.message || 'Impossible de lancer l\'analyse comportementale.';
      }
    });
  }

  toggleSoftSkillEvidence(name: string): void {
    if (!name) {
      return;
    }
    if (this.expandedSoftSkillEvidence.has(name)) {
      this.expandedSoftSkillEvidence.delete(name);
    } else {
      this.expandedSoftSkillEvidence.add(name);
    }
  }

  isSoftSkillEvidenceExpanded(name: string): boolean {
    return this.expandedSoftSkillEvidence.has(name);
  }

  softSkillConfidencePercent(confidence: number | undefined): number {
    if (confidence === undefined || confidence === null) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round(confidence * 100)));
  }

  isBehavioralSkillAlreadyInCandidate(skillName: string): boolean {
    const normalized = (skillName || '').trim().toLowerCase();
    if (!normalized) {
      return false;
    }
    return (this.candidate?.skills || []).some((s: any) => (s?.name || '').trim().toLowerCase() === normalized);
  }

  addBehavioralSkillToCandidate(skillName: string): void {
    if (!this.candidateId || !skillName || this.isBehavioralSkillAlreadyInCandidate(skillName)) {
      return;
    }

    this.behavioralAttachError = '';
    this.attachingBehavioralSkillNames.add(skillName);

    this.api.addSkillNameToCandidate(this.candidateId, skillName).subscribe({
      next: (candidate) => {
        this.attachingBehavioralSkillNames.delete(skillName);
        this.candidate = candidate;
        this.rarityScore = Math.min(100, Math.round((this.candidate.skills?.length || 0) * 8.5) + 15);
        this.behavioralAttachSuccess = `${skillName} ajoute aux competences du candidat.`;
      },
      error: (err) => {
        this.attachingBehavioralSkillNames.delete(skillName);
        this.behavioralAttachError = err?.error?.message || `Impossible d'ajouter ${skillName}.`;
      }
    });
  }

  addAllBehavioralSkillsToCandidate(): void {
    if (!this.candidateId || this.isBehavioralAttachAllLoading) {
      return;
    }

    const allNames = (this.behavioralProfile?.softSkills || [])
      .map((s) => (s.softSkillName || '').trim())
      .filter((name) => !!name)
      .filter((name, idx, arr) => arr.findIndex((n) => n.toLowerCase() === name.toLowerCase()) === idx)
      .filter((name) => !this.isBehavioralSkillAlreadyInCandidate(name));

    if (allNames.length === 0) {
      this.behavioralAttachSuccess = 'Toutes les soft skills detectees sont deja presentes dans le profil candidat.';
      this.behavioralAttachError = '';
      return;
    }

    this.behavioralAttachError = '';
    this.behavioralAttachSuccess = '';
    this.isBehavioralAttachAllLoading = true;
    allNames.forEach((name) => this.attachingBehavioralSkillNames.add(name));

    forkJoin(allNames.map((name) => this.api.addSkillNameToCandidate(this.candidateId as string, name))).subscribe({
      next: (responses) => {
        allNames.forEach((name) => this.attachingBehavioralSkillNames.delete(name));
        this.isBehavioralAttachAllLoading = false;
        const last = responses[responses.length - 1];
        if (last) {
          this.candidate = last;
          this.rarityScore = Math.min(100, Math.round((this.candidate.skills?.length || 0) * 8.5) + 15);
        }
        this.behavioralAttachSuccess = `${allNames.length} soft skill(s) ajoutee(s) au profil candidat.`;
      },
      error: (err) => {
        allNames.forEach((name) => this.attachingBehavioralSkillNames.delete(name));
        this.isBehavioralAttachAllLoading = false;
        this.behavioralAttachError = err?.error?.message || 'Ajout global des soft skills echoue.';
      }
    });
  }

  private handleBehavioralProfileJobUpdate(job: SoftSkillAnalysisJobResponse): void {
    this.behavioralProfileJob = job;
    this.behavioralProfileInfoMessage = job.message || '';

    if (job.status === 'completed') {
      this.isBehavioralProfileLoading = false;
      this.clearBehavioralProfilePollingTimer();

      const profile: BehavioralProfileResponse = {
        candidateId: job.candidateId,
        totalSoftSkills: (job.softSkills || []).length,
        softSkills: (job.softSkills || []) as SoftSkillEvidence[]
      };
      this.behavioralProfile = profile;
      return;
    }

    this.scheduleBehavioralProfilePolling(job.jobId, job.pollAfterMs || 1200);
  }

  private scheduleBehavioralProfilePolling(jobId: string, delayMs: number): void {
    this.clearBehavioralProfilePollingTimer();
    const safeDelay = Math.max(700, delayMs || 1200);

    this.behavioralProfilePollTimer = setTimeout(() => {
      if (!this.candidateId) {
        this.isBehavioralProfileLoading = false;
        return;
      }

      this.api.getBehavioralProfileAnalysisJob(this.candidateId, jobId).subscribe({
        next: (job) => this.handleBehavioralProfileJobUpdate(job),
        error: (err) => {
          this.isBehavioralProfileLoading = false;
          this.behavioralProfileError = err?.error?.message || 'Echec du suivi de l\'analyse comportementale.';
          this.clearBehavioralProfilePollingTimer();
        }
      });
    }, safeDelay);
  }

  private clearBehavioralProfilePollingTimer(): void {
    if (this.behavioralProfilePollTimer !== null) {
      clearTimeout(this.behavioralProfilePollTimer);
      this.behavioralProfilePollTimer = null;
    }
  }

  private resetBehavioralProfileRunState(): void {
    this.clearBehavioralProfilePollingTimer();
    this.isBehavioralProfileLoading = false;
    this.behavioralProfileError = '';
    this.behavioralProfileInfoMessage = '';
    this.behavioralProfileJob = null;
    this.expandedSoftSkillEvidence.clear();
  }

  asPercent(score: number | undefined): number {
    if (score === undefined || score === null) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round(score * 100)));
  }

  semanticToneClass(score: number | undefined): string {
    const pct = this.asPercent(score);
    if (pct >= 90) {
      return 'tone-excellent';
    }
    if (pct >= 80) {
      return 'tone-strong';
    }
    return 'tone-medium';
  }

  explainToneClass(score: number | undefined): string {
    const pct = this.asPercent(score);
    if (pct >= 90) {
      return 'bg-success';
    }
    if (pct >= 80) {
      return 'bg-primary';
    }
    return 'bg-warning text-dark';
  }

  calculateTopSkills(recs: any[]) {
    const skillCount: any = {};
    for (let r of recs) {
      const sourceSkills = (r.matchedSkillsList && r.matchedSkillsList.length > 0)
        ? r.matchedSkillsList.map((name: string) => ({ name }))
        : (r.job?.skills || []);

      if (sourceSkills.length > 0) {
        for (let s of sourceSkills) {
          if (!s?.name) {
            continue;
          }
          skillCount[s.name] = (skillCount[s.name] || 0) + 1;
        }
      }
    }

    this.topSkillsDemand = Object.keys(skillCount)
      .map(k => ({ name: k, count: skillCount[k] }))
      .sort((a,b) => b.count - a.count)
      .slice(0, 5);
  }
}
