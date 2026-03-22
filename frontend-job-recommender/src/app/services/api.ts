import { Injectable } from "@angular/core";
import { HttpClient, HttpParams } from "@angular/common/http";
import { Observable } from "rxjs";
import { map } from "rxjs/operators";

export interface Skill {
  id?: string;
  name: string;
}

export interface Company {
  id?: string;
  name: string;
}

export interface Location {
  id?: string;
  name: string;
}

export interface Job {
  jobLink: string;
  title: string;
  type: string;
  level: string;
  skills: Skill[];
  company?: Company;
  location?: Location;
}

export interface JobListItem {
  jobLink: string;
  title: string;
  type: string;
  level: string;
  companyName?: string;
  locationName?: string;
  skills: string[];
}

export interface SmartJobFieldSuggestion {
  path: string;
  value: string;
  evidence: string;
}

export interface SmartJobParseResponse {
  success: boolean;
  fromLlm: boolean;
  job: Job;
  evidences: Record<string, string>;
  skillSuggestions: SmartJobFieldSuggestion[];
  warnings: string[];
  inferredFields: string[];
}

export interface Candidate {
  id: string;
  name: string;
  email: string;
  resumePath?: string;
  skills: Skill[];
}

export interface CandidateListItem {
  id: string;
  name: string;
  email?: string;
  resumePath?: string;
  skillCount: number;
  topSkills: string[];
}

export interface PageResponse<T> {
  content: T[];
  size?: number;
  totalElements?: number;
  totalPages?: number;
  number?: number;
  page?: {
    size: number;
    totalElements: number;
    totalPages: number;
    number: number;
  };
}

export interface JobRecommendation {
  job: Job;
  matchingSkills: number;
  totalSkills: number;
  score: number;
}

export interface SemanticMatchExplanation {
  candidateSkillName: string;
  requiredSkillName: string;
  similarityScore: number;
  narrative?: string;
}

export interface SemanticRecommendedJob {
  jobLink: string;
  title: string;
  type: string;
  level: string;
}

export interface SemanticRecommendation {
  matchedJob: SemanticRecommendedJob;
  semanticScore: number;
  semanticMatchedSkills: number;
  totalRequiredSkills: number;
  explanations: SemanticMatchExplanation[];
}

export interface SkillSimilarityResult {
  skill: Skill;
  score: number;
}

export interface DataQualitySnapshot {
  generatedAt: string;
  totalSkills: number;
  potentialDuplicateSkillGroups: number;
  potentialDuplicateSkillNodes: number;
  hasSkillRelationships: number;
  requiresRelationships: number;
  qualityScore: number;
  status: "HEALTHY" | "WARNING" | "CRITICAL";
  potentialDuplicateRatio: number;
}

export interface SkillCentrality {
  skillName: string;
  jobCount: number;
  marketCoveragePct: number;
  criticality: string;
}

export interface SkillNeighbor {
  skillName: string;
  cooccurrenceWeight: number;
}

export interface SkillCommunity {
  seedSkill: string;
  degree: number;
  topNeighbors: SkillNeighbor[];
}

export interface CareerPathStep {
  sourceSkill: string;
  bridgeSkill: string;
  targetSkill: string;
  supportCount: number;
}

export interface CareerPath {
  jobLink: string;
  title: string;
  type: string;
  level: string;
  missingSkills: number;
  bridgeSteps: CareerPathStep[];
}

export interface DataFunnel {
  generatedAt: string;
  totalSkillNodes: number;
  potentialDuplicateSkillNodes: number;
  deduplicatedSkillNodes: number;
  requiredSkillMentions: number;
  marketValidatedSkills: number;
  candidateValidatedSkills: number;
  sharedSkills: number;
  marketCoveragePct: number;
  candidateCoveragePct: number;
  sharedCoveragePct: number;
}

export interface DataDrift {
  generatedAt: string;
  isProxy: boolean;
  note: string;
  totalJobs: number;
  totalRequiredSkillMentions: number;
  top10SkillConcentrationPct: number;
  levelEntropy: number;
  typeEntropy: number;
  imbalanceRisk: "LOW" | "MEDIUM" | "HIGH";
}

export interface CandidateQualityRow {
  candidateId: string;
  candidateName: string;
  skillCount: number;
  completeness: number;
  qualityScore: number;
  signalClass: "LOW_SIGNAL" | "MEDIUM_SIGNAL" | "HIGH_SIGNAL";
}

export interface SkillGapRoadmapRow {
  skillName: string;
  supportedJobs: number;
  impactScore: number;
  exampleJobs: string[];
}

export interface RecommendationComparisonRow {
  jobLink: string;
  title: string;
  lexicalScore: number;
  semanticScore: number;
  delta: number;
}

export interface CounterfactualRow {
  skillName: string;
  unlockableJobs: number;
  priorityScore: number;
  exampleJobs: string[];
}

export interface CareerPathPredictionSkill {
  skillName: string;
  cooccurrenceSupport: number;
  unlockableJobs: number;
  seniorUnlockableJobs: number;
  compensationLiftScore: number;
  linkPredictionScore: number;
  sampleJobs: string[];
}

export interface CareerPathPredictionResult {
  candidateId: string;
  candidateName: string;
  currentSkills: string[];
  recommendedSkills: CareerPathPredictionSkill[];
  coachingMessage: string;
  coachingFromLlm: boolean;
  generatedAtEpochMs: number;
}

export interface GitHubProfileAnalyzeRequest {
  githubUsername?: string;
  repositoryUrls?: string[];
  maxRepos?: number;
  includeForks?: boolean;
}

export interface GitHubMatchedSkillResult {
  sourceLabel: string;
  canonicalCandidate: string;
  matchedSkillId: string;
  matchedSkillName: string;
  matchStrategy: string;
  llmConfidence: number;
  similarityScore?: number;
  evidence: string[];
}

export interface GitHubRejectedSkillResult {
  sourceLabel: string;
  canonicalCandidate: string;
  reason: string;
  llmConfidence: number;
  bestSimilarityScore?: number;
  bestSkillName?: string;
  evidence: string[];
}

export interface GitHubProfileAnalysisResult {
  candidateId: string;
  analysisId: string;
  githubUsername?: string;
  repositoriesAnalyzed: number;
  repositoriesWithReadme: number;
  extractedSkills: number;
  skillsReadyToAttach: number;
  matched: GitHubMatchedSkillResult[];
  rejected: GitHubRejectedSkillResult[];
  categories: { category: string; count: number; averageConfidence: number }[];
  confidenceBuckets: { bucket: string; count: number }[];
  durationMs: number;
}

export interface GitHubSkillApplyRequest {
  analysisId: string;
}

export interface GitHubSkillApplyResult {
  candidateId: string;
  analysisId: string;
  attachedSkills: number;
  appliedAtEpochMs: number;
}

export interface ResumePortfolioDelta {
  validatedSkills: string[];
  claimedButUnverified: string[];
  hiddenGems: string[];
  validatedCount: number;
  claimedButUnverifiedCount: number;
  hiddenGemsCount: number;
}

export interface ResumePortfolioUploadResponse {
  candidateId: string;
  fileName: string;
  detectedGithubUsername?: string;
  detectedGithubUrl?: string;
  githubDetected: boolean;
  githubAnalysisTriggered: boolean;
  githubAnalysisPending: boolean;
  cvClaimedSkills: string[];
  githubSkills: string[];
  validatedSkills: string[];
  claimedButUnverified: string[];
  hiddenGems: string[];
  githubAnalysis?: GitHubProfileAnalysisResult;
}

export interface ResumePortfolioApplyResult {
  candidateId: string;
  attachedSkills: number;
  candidate: Candidate;
}

export interface InterviewScriptStartRequest {
  claimedButUnverified: string[];
  targetQuestions?: number;
}

export interface InterviewQuestion {
  skill: string;
  question: string;
  expectedSignals: string[];
}

export interface InterviewScriptJobResponse {
  jobId: string;
  candidateId: string;
  status: 'queued' | 'in_progress' | 'completed';
  pollAfterMs: number;
  message: string;
  startedAtEpochMs: number;
  completedAtEpochMs?: number;
  model: string;
  fallbackUsed: boolean;
  questions: InterviewQuestion[];
}

export interface SoftSkillEvidence {
  softSkillName: string;
  confidence: number;
  evidence: string;
}

export interface BehavioralProfileResponse {
  candidateId: string;
  totalSoftSkills: number;
  softSkills: SoftSkillEvidence[];
}

export interface SoftSkillAnalyzeStartRequest {
  cvSummary?: string;
  githubUsername?: string;
  repositoryUrls?: string[];
  maxRepos?: number;
  includeForks?: boolean;
}

export interface SoftSkillAnalysisJobResponse {
  jobId: string;
  candidateId: string;
  status: 'queued' | 'in_progress' | 'completed';
  pollAfterMs: number;
  message: string;
  startedAtEpochMs: number;
  completedAtEpochMs?: number;
  model: string;
  fallbackUsed: boolean;
  softSkills: SoftSkillEvidence[];
}

@Injectable({
  providedIn: "root",
})
export class Api {
  private baseUrl = "http://localhost:8080/api";

  constructor(private http: HttpClient) {}

  getStats(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/stats`);
  }

  getDataQualitySnapshot(): Observable<DataQualitySnapshot> {
    return this.http.get<DataQualitySnapshot>(`${this.baseUrl}/admin/data-quality`);
  }

  getCandidates(page: number = 0, size: number = 50): Observable<PageResponse<Candidate>> {
    let params = new HttpParams().set("page", page).set("size", size);
    return this.http.get<PageResponse<Candidate>>(`${this.baseUrl}/candidates`, { params });
  }

  getCandidateSummaries(page: number = 0, size: number = 50): Observable<PageResponse<CandidateListItem>> {
    let params = new HttpParams().set("page", page).set("size", size);
    return this.http.get<PageResponse<CandidateListItem>>(`${this.baseUrl}/candidates/list`, { params });
  }

  getCandidate(id: string): Observable<Candidate> {
    return this.http.get<Candidate>(`${this.baseUrl}/candidates/${id}`);
  }

  createCandidate(candidate: any): Observable<Candidate> {
    return this.http.post<Candidate>(`${this.baseUrl}/candidates`, candidate);
  }

  updateCandidate(id: string, candidate: any): Observable<Candidate> {
    return this.http.put<Candidate>(`${this.baseUrl}/candidates/${id}`, candidate);
  }

  deleteCandidate(id: string): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/candidates/${id}`);
  }

  addSkillToCandidate(candidateId: string, skillId: string): Observable<Candidate> {
    return this.http.post<Candidate>(`${this.baseUrl}/candidates/${candidateId}/skills/${skillId}`, {});
  }

  addSkillNameToCandidate(candidateId: string, skillName: string): Observable<Candidate> {
    return this.http.post<Candidate>(`${this.baseUrl}/candidates/${candidateId}/skills`, { name: skillName });
  }

  getJobs(
    page: number = 0,
    size: number = 50,
    filters?: { title?: string; level?: string; skill?: string }
  ): Observable<PageResponse<Job>> {
    let params = new HttpParams().set("page", page).set("size", size);
    if (filters?.title?.trim()) {
      params = params.set("title", filters.title.trim());
    }
    if (filters?.level?.trim()) {
      params = params.set("level", filters.level.trim());
    }
    if (filters?.skill?.trim()) {
      params = params.set("skill", filters.skill.trim());
    }
    return this.http.get<PageResponse<Job>>(`${this.baseUrl}/jobs`, { params });
  }

  getJobSummaries(
    page: number = 0,
    size: number = 50,
    filters?: { title?: string; level?: string; skill?: string }
  ): Observable<PageResponse<JobListItem>> {
    let params = new HttpParams().set("page", page).set("size", size);
    if (filters?.title?.trim()) {
      params = params.set("title", filters.title.trim());
    }
    if (filters?.level?.trim()) {
      params = params.set("level", filters.level.trim());
    }
    if (filters?.skill?.trim()) {
      params = params.set("skill", filters.skill.trim());
    }
    return this.http.get<PageResponse<JobListItem>>(`${this.baseUrl}/jobs/list`, { params });
  }

  getJob(id: string): Observable<Job> {
    return this.http.get<Job>(`${this.baseUrl}/jobs/${id}`);
  }

  getJobByLink(jobLink: string): Observable<Job> {
    const params = new HttpParams().set("jobLink", jobLink);
    return this.http.get<Job>(`${this.baseUrl}/jobs/by-link`, { params });
  }

  createJob(job: any): Observable<Job> {
    return this.http.post<Job>(`${this.baseUrl}/jobs`, job);
  }

  updateJob(id: string, job: any): Observable<Job> {
    return this.http.put<Job>(`${this.baseUrl}/jobs/${id}`, job);
  }

  updateJobByLink(jobLink: string, job: any): Observable<Job> {
    const params = new HttpParams().set("jobLink", jobLink);
    return this.http.put<Job>(`${this.baseUrl}/jobs/by-link`, job, { params });
  }

  parseJobFromDescription(rawText: string): Observable<SmartJobParseResponse> {
    return this.http.post<SmartJobParseResponse>(`${this.baseUrl}/jobs/smart-parse`, { rawText });
  }

  deleteJob(id: string): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/jobs/${id}`);
  }

  getSkills(page: number = 0, size: number = 50): Observable<PageResponse<Skill>> {
    let params = new HttpParams().set("page", page).set("size", size);
    return this.http.get<PageResponse<Skill>>(`${this.baseUrl}/skills`, { params });
  }

  searchSkillsByName(name: string, page: number = 0, size: number = 20): Observable<PageResponse<Skill>> {
    let params = new HttpParams()
      .set("name", name)
      .set("page", page)
      .set("size", size);
    return this.http.get<PageResponse<Skill>>(`${this.baseUrl}/skills/search`, { params });
  }

  resolveSkillName(name: string): Observable<{ input: string; normalized: string; canonical: string; changed: boolean }> {
    let params = new HttpParams().set("name", name);
    return this.http.get<{ input: string; normalized: string; canonical: string; changed: boolean }>(
      `${this.baseUrl}/skills/resolve`,
      { params }
    );
  }

  createSkill(skill: any): Observable<Skill> {
    return this.http.post<Skill>(`${this.baseUrl}/skills`, skill);
  }

  getCompanies(page: number = 0, size: number = 50): Observable<PageResponse<Company>> {
    let params = new HttpParams().set("page", page).set("size", size);
    return this.http.get<PageResponse<Company>>(`${this.baseUrl}/companies`, { params });
  }

  searchCompaniesByName(name: string, page: number = 0, size: number = 20): Observable<PageResponse<Company>> {
    let params = new HttpParams()
      .set("name", name)
      .set("page", page)
      .set("size", size);
    return this.http.get<PageResponse<Company>>(`${this.baseUrl}/companies/search`, { params });
  }

  createCompany(company: Company): Observable<Company> {
    return this.http.post<Company>(`${this.baseUrl}/companies`, company);
  }

  getLocations(page: number = 0, size: number = 50): Observable<PageResponse<Location>> {
    let params = new HttpParams().set("page", page).set("size", size);
    return this.http.get<PageResponse<Location>>(`${this.baseUrl}/locations`, { params });
  }

  searchLocationsByName(name: string, page: number = 0, size: number = 20): Observable<PageResponse<Location>> {
    let params = new HttpParams()
      .set("name", name)
      .set("page", page)
      .set("size", size);
    return this.http.get<PageResponse<Location>>(`${this.baseUrl}/locations/search`, { params });
  }

  createLocation(location: Location): Observable<Location> {
    return this.http.post<Location>(`${this.baseUrl}/locations`, location);
  }

  getRecommendations(candidateId: string): Observable<JobRecommendation[]> {
    return this.http.get<any[]>(`${this.baseUrl}/recommendations/${candidateId}`).pipe(
      map((items) => {
        const recommendations = items || [];
        return recommendations.map((rec: any) => {
          const mappedMatchedSkills = (rec.matchedSkillsList || [])
            .filter((name: string) => !!name)
            .map((name: string) => ({ name }));

          const hasUsableNestedJob = !!rec.job &&
            (!!rec.job.title || !!rec.job.jobLink || !!rec.job.level || !!rec.job.type);

          const normalizedJob = hasUsableNestedJob ? rec.job : {
            title: rec.jobTitle,
            jobLink: rec.jobLink,
            type: rec.jobType,
            level: rec.jobLevel,
            skills: mappedMatchedSkills
          };

          const normalizedNestedSkills = Array.isArray(normalizedJob.skills)
            ? normalizedJob.skills
                .map((s: any) => {
                  if (typeof s === 'string') {
                    return { name: s };
                  }
                  return { name: s?.name };
                })
                .filter((s: any) => !!s.name)
            : [];

          if (normalizedNestedSkills.length > 0) {
            normalizedJob.skills = normalizedNestedSkills;
          } else {
            normalizedJob.skills = mappedMatchedSkills;
          }

          return {
            ...rec,
            job: normalizedJob,
            matchedSkillsList: rec.matchedSkillsList || []
          } as JobRecommendation;
        });
      })
    );
  }

  getSemanticRecommendations(
    candidateId: string,
    options?: { threshold?: number; topJobs?: number; topKPerSkill?: number; embeddingDim?: number }
  ): Observable<SemanticRecommendation[]> {
    let params = new HttpParams()
      .set("threshold", options?.threshold ?? 0.8)
      .set("topJobs", options?.topJobs ?? 5)
      .set("topKPerSkill", options?.topKPerSkill ?? 20)
      .set("embeddingDim", options?.embeddingDim ?? 384);

    return this.http.get<SemanticRecommendation[]>(`${this.baseUrl}/recommendations/${candidateId}/semantic`, { params });
  }

  getSimilarSkillsByName(name: string, topK: number = 8): Observable<SkillSimilarityResult[]> {
    let params = new HttpParams().set("name", name).set("topK", topK);
    return this.http.get<SkillSimilarityResult[]>(`${this.baseUrl}/skills/semantic/search`, { params });
  }

  getSkillCentrality(limit: number = 20): Observable<SkillCentrality[]> {
    let params = new HttpParams().set("limit", limit);
    return this.http.get<SkillCentrality[]>(`${this.baseUrl}/graph-analytics/skills/centrality`, { params });
  }

  getSkillCommunities(
    options?: { limit?: number; minCooccurrence?: number; minDegree?: number; topNeighbors?: number }
  ): Observable<SkillCommunity[]> {
    let params = new HttpParams()
      .set("limit", options?.limit ?? 12)
      .set("minCooccurrence", options?.minCooccurrence ?? 5)
      .set("minDegree", options?.minDegree ?? 4)
      .set("topNeighbors", options?.topNeighbors ?? 6);

    return this.http.get<SkillCommunity[]>(`${this.baseUrl}/graph-analytics/skills/communities`, { params });
  }

  getCareerPaths(candidateId: string, topJobs: number = 5): Observable<CareerPath[]> {
    let params = new HttpParams().set("topJobs", topJobs);
    return this.http.get<CareerPath[]>(`${this.baseUrl}/graph-analytics/candidates/${candidateId}/career-paths`, { params });
  }

  getDataFunnel(): Observable<DataFunnel> {
    return this.http.get<DataFunnel>(`${this.baseUrl}/analytics/data-funnel`);
  }

  getDataDrift(): Observable<DataDrift> {
    return this.http.get<DataDrift>(`${this.baseUrl}/analytics/data-drift`);
  }

  getCandidateQuality(page: number = 0, size: number = 50): Observable<PageResponse<CandidateQualityRow>> {
    let params = new HttpParams().set("page", page).set("size", size);
    return this.http.get<PageResponse<CandidateQualityRow>>(`${this.baseUrl}/analytics/candidates/quality`, { params });
  }

  getSkillGapRoadmap(candidateId: string, limit: number = 5): Observable<SkillGapRoadmapRow[]> {
    let params = new HttpParams().set("limit", limit);
    return this.http.get<SkillGapRoadmapRow[]>(`${this.baseUrl}/analytics/candidates/${candidateId}/skill-gap-roadmap`, { params });
  }

  getRecommendationComparison(candidateId: string, topJobs: number = 5): Observable<RecommendationComparisonRow[]> {
    let params = new HttpParams().set("topJobs", topJobs);
    return this.http.get<RecommendationComparisonRow[]>(`${this.baseUrl}/analytics/recommendations/${candidateId}/comparison`, { params });
  }

  getRecommendationCounterfactual(candidateId: string, limit: number = 5, maxMissing: number = 3): Observable<CounterfactualRow[]> {
    let params = new HttpParams().set("limit", limit).set("maxMissing", maxMissing);
    return this.http.get<CounterfactualRow[]>(`${this.baseUrl}/analytics/recommendations/${candidateId}/counterfactual`, { params });
  }

  getCareerPathPrediction(
    candidateId: string,
    options?: { topK?: number; maxRemainingGaps?: number; withCoaching?: boolean }
  ): Observable<CareerPathPredictionResult> {
    let params = new HttpParams();
    if (options?.topK !== undefined) {
      params = params.set("topK", options.topK);
    }
    if (options?.maxRemainingGaps !== undefined) {
      params = params.set("maxRemainingGaps", options.maxRemainingGaps);
    }
    params = params.set("withCoaching", options?.withCoaching ?? true);

    return this.http.get<CareerPathPredictionResult>(
      `${this.baseUrl}/analytics/candidates/${candidateId}/career-path-predictor`,
      { params }
    );
  }

  analyzeCandidateGitHubProfile(
    candidateId: string,
    request: GitHubProfileAnalyzeRequest
  ): Observable<GitHubProfileAnalysisResult> {
    return this.http.post<GitHubProfileAnalysisResult>(`${this.baseUrl}/candidates/${candidateId}/github/analyze`, request);
  }

  applyCandidateGitHubAnalysis(
    candidateId: string,
    request: GitHubSkillApplyRequest
  ): Observable<GitHubSkillApplyResult> {
    return this.http.post<GitHubSkillApplyResult>(`${this.baseUrl}/candidates/${candidateId}/github/apply`, request);
  }

  uploadResumePortfolioIntelligence(
    candidateId: string,
    file: File,
    autoAnalyzeGithub: boolean = false
  ): Observable<ResumePortfolioUploadResponse> {
    const formData = new FormData();
    formData.append("file", file);
    const params = new HttpParams().set("autoAnalyzeGithub", autoAnalyzeGithub);
    return this.http.post<ResumePortfolioUploadResponse>(
      `${this.baseUrl}/candidates/${candidateId}/resume-intelligence/upload`,
      formData,
      { params }
    );
  }

  computeResumePortfolioDelta(
    candidateId: string,
    cvSkills: string[],
    githubSkills: string[]
  ): Observable<ResumePortfolioDelta> {
    return this.http.post<ResumePortfolioDelta>(
      `${this.baseUrl}/candidates/${candidateId}/resume-intelligence/delta`,
      { cvSkills, githubSkills }
    );
  }

  applyResumePortfolioSkills(
    candidateId: string,
    selectedSkills: string[]
  ): Observable<ResumePortfolioApplyResult> {
    return this.http.post<ResumePortfolioApplyResult>(
      `${this.baseUrl}/candidates/${candidateId}/resume-intelligence/apply`,
      { selectedSkills }
    );
  }

  startDynamicInterviewScript(
    candidateId: string,
    request: InterviewScriptStartRequest
  ): Observable<InterviewScriptJobResponse> {
    return this.http.post<InterviewScriptJobResponse>(
      `${this.baseUrl}/candidates/${candidateId}/resume-intelligence/interview-script/start`,
      request
    );
  }

  getDynamicInterviewScriptJob(
    candidateId: string,
    jobId: string
  ): Observable<InterviewScriptJobResponse> {
    return this.http.get<InterviewScriptJobResponse>(
      `${this.baseUrl}/candidates/${candidateId}/resume-intelligence/interview-script/${jobId}`
    );
  }

  startBehavioralProfileAnalysis(
    candidateId: string,
    request: SoftSkillAnalyzeStartRequest = {}
  ): Observable<SoftSkillAnalysisJobResponse> {
    return this.http.post<SoftSkillAnalysisJobResponse>(
      `${this.baseUrl}/candidates/${candidateId}/behavioral-profile/start`,
      request
    );
  }

  getBehavioralProfileAnalysisJob(
    candidateId: string,
    jobId: string
  ): Observable<SoftSkillAnalysisJobResponse> {
    return this.http.get<SoftSkillAnalysisJobResponse>(
      `${this.baseUrl}/candidates/${candidateId}/behavioral-profile/${jobId}`
    );
  }

  getBehavioralProfile(candidateId: string): Observable<BehavioralProfileResponse> {
    return this.http.get<BehavioralProfileResponse>(
      `${this.baseUrl}/candidates/${candidateId}/behavioral-profile`
    );
  }
}

