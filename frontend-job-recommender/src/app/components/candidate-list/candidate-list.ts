import { Component, OnInit } from "@angular/core";
import { CommonModule } from "@angular/common";
import { RouterLink } from "@angular/router";
import { Api, CandidateListItem, CandidateQualityRow } from "../../services/api";

@Component({
  selector: "app-candidate-list",
  imports: [CommonModule, RouterLink],
  templateUrl: "./candidate-list.html",
  styleUrl: "./candidate-list.css",
})
export class CandidateList implements OnInit {
  candidates: CandidateListItem[] = [];
  qualityByCandidateId: Record<string, CandidateQualityRow> = {};
  page: number = 0;
  totalPages: number = 1;

  constructor(private api: Api) {}

  ngOnInit(): void {
    this.loadCandidates();
  }

  loadCandidates(): void {
    this.api.getCandidateSummaries(this.page, 50).subscribe({
      next: (data) => {
        this.candidates = data.content || [];
        this.totalPages = data.totalPages ?? data.page?.totalPages ?? 1;
        this.loadCandidateQuality();
      },
      error: (err) => console.error("Error loading candidates", err)
    });
  }

  loadCandidateQuality(): void {
    this.api.getCandidateQuality(this.page, 50).subscribe({
      next: (data) => {
        const rows = data.content || [];
        const map: Record<string, CandidateQualityRow> = {};
        rows.forEach((row) => {
          if (row?.candidateId) {
            map[row.candidateId] = row;
          }
        });
        this.qualityByCandidateId = map;
      },
      error: () => {
        this.qualityByCandidateId = {};
      }
    });
  }

  qualitySignalBadgeClass(signal: string | undefined): string {
    if (signal === "HIGH_SIGNAL") {
      return "bg-success";
    }
    if (signal === "MEDIUM_SIGNAL") {
      return "bg-warning text-dark";
    }
    if (signal === "LOW_SIGNAL") {
      return "bg-danger";
    }
    return "bg-secondary";
  }

  deleteCandidate(id: string): void {
    if (confirm("Êtes-vous sûr de vouloir supprimer ce candidat ?")) {
      this.api.deleteCandidate(id).subscribe(() => {
        this.loadCandidates();
      });
    }
  }

  nextPage(): void {
    if (this.page < this.totalPages - 1) {
      this.page++;
      this.loadCandidates();
    }
  }

  prevPage(): void {
    if (this.page > 0) {
      this.page--;
      this.loadCandidates();
    }
  }
}
