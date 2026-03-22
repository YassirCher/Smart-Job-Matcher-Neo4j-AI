import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { Api, CounterfactualRow, RecommendationComparisonRow } from '../../services/api';

@Component({
  selector: 'app-recommendation-list',
  imports: [CommonModule],
  templateUrl: './recommendation-list.html',
  styleUrl: './recommendation-list.css',
})
export class RecommendationList implements OnInit {
  candidateId: string | null = null;
  recommendations: any[] = [];
  comparisonRows: RecommendationComparisonRow[] = [];
  counterfactualRows: CounterfactualRow[] = [];

  constructor(private api: Api, private route: ActivatedRoute) {}

  ngOnInit(): void {
    this.candidateId = this.route.snapshot.paramMap.get('id');
    if (this.candidateId) {
      this.api.getRecommendations(this.candidateId).subscribe({
        next: (data) => this.recommendations = data,
        error: (err) => console.error(err)
      });

      this.api.getRecommendationComparison(this.candidateId, 5).subscribe({
        next: (rows) => this.comparisonRows = rows || [],
        error: () => this.comparisonRows = []
      });

      this.api.getRecommendationCounterfactual(this.candidateId, 5, 3).subscribe({
        next: (rows) => this.counterfactualRows = rows || [],
        error: () => this.counterfactualRows = []
      });
    }
  }
}
