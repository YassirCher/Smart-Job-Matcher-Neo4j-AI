import { Component, OnInit } from "@angular/core";
import { CommonModule } from "@angular/common";
import { RouterModule } from "@angular/router";
import { ActivatedRoute, Router } from "@angular/router";
import { FormsModule } from "@angular/forms";
import { Api, JobListItem } from "../../services/api";

@Component({
  selector: "app-job-list-search",
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: "./job-list-search.html",
  styleUrl: "./job-list-search.css",
})
export class JobListSearch implements OnInit {
  jobs: JobListItem[] = [];
  noticeMessage: string = "";
  page: number = 0;
  totalPages: number = 1;
  pageSize: number = 50;
  filters: any = {
    title: "",
    level: "",
    skill: "",
  };

  constructor(private api: Api, private route: ActivatedRoute, private router: Router) {}

  ngOnInit(): void {
    const created = this.route.snapshot.queryParamMap.get("created");
    const updated = this.route.snapshot.queryParamMap.get("updated");
    if (created === "1") {
      this.noticeMessage = "Le job a été créé avec succès.";
    } else if (updated === "1") {
      this.noticeMessage = "Le job a été mis à jour avec succès.";
    }

    this.loadJobs();
  }

  dismissNotice(): void {
    this.noticeMessage = "";
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {},
      replaceUrl: true
    });
  }

  loadJobs(): void {
    this.api.getJobSummaries(this.page, this.pageSize, this.filters).subscribe({
      next: (data) => {
        this.jobs = data.content || [];
        this.totalPages = data.totalPages ?? data.page?.totalPages ?? 1;
      },
      error: (err) => console.error("Error loading jobs", err)
    });
  }

  applyFilters(): void {
    this.page = 0;
    this.loadJobs();
  }

  resetFilters(): void {
    this.filters = { title: "", level: "", skill: "" };
    this.applyFilters();
  }

  nextPage(): void {
    if (this.page < this.totalPages - 1) {
      this.page++;
      this.loadJobs();
    }
  }

  deleteJob(id: string): void {
    if (confirm("Sûr ?")) {
      this.api.deleteJob(id).subscribe(() => this.loadJobs());
    }
  }

  prevPage(): void {
    if (this.page > 0) {
      this.page--;
      this.loadJobs();
    }
  }
}

