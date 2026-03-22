import { Component, OnInit, ElementRef, ViewChild, AfterViewInit } from "@angular/core";
import { CommonModule } from "@angular/common";
import { ActivatedRoute } from "@angular/router";
import { Api } from "../../services/api";
import * as d3 from "d3";

@Component({
  selector: "app-graph-visualizer",
  imports: [CommonModule],
  templateUrl: "./graph-visualizer.html",
  styleUrl: "./graph-visualizer.css",
})
export class GraphVisualizer implements OnInit, AfterViewInit {
  @ViewChild("graphContainer", { static: true }) graphContainer!: ElementRef;   
  candidateId: string | null = null;
  candidate: any = null;
  recommendations: any[] = [];
  isLoading = true;

  constructor(private route: ActivatedRoute, private api: Api) {}

  ngOnInit(): void {
    this.candidateId = this.route.snapshot.paramMap.get("id");
  }

  ngAfterViewInit(): void {
    if (this.candidateId) {
      this.api.getCandidate(this.candidateId).subscribe({
        next: (c) => {
          this.candidate = c;
          this.api.getRecommendations(this.candidateId as string).subscribe({
            next: (recs) => {
              this.recommendations = recs;
              this.isLoading = false;
              this.createForceGraph();
            }
          });
        }
      });
    }
  }

  createForceGraph(): void {
    if (!this.candidateId || !this.candidate) return;

    const container = this.graphContainer.nativeElement;
    d3.select(container).selectAll("*").remove();

    const width = container.clientWidth || 800;
    const height = 600;

    const svg = d3.select(container)
      .append("svg")
      .attr("width", "100%")
      .attr("height", height);

    const nodes: any[] = [];
    const links: any[] = [];

    const candidateName = this.candidate.name || "Candidat";
    nodes.push({ id: "candidate", name: candidateName, group: 1, type: "Candidate" });

    if (this.candidate.skills) {
      this.candidate.skills.forEach((s: any) => {
        const sid = "skill_" + s.name;
        if (!nodes.find(n => n.id === sid)) {
          nodes.push({ id: sid, name: s.name, group: 2, type: "Skill" });
        }
        links.push({ source: "candidate", target: sid, type: "HAS_SKILL" });
      });
    }

    this.recommendations.forEach((rec, index) => {
      const recJob = rec?.job || {};
      const jobId = "job_" + index;
      nodes.push({
        id: jobId,
        name: recJob.title || rec.jobTitle || "Titre non disponible",
        group: 3,
        type: "Job",
        score: rec.score
      });
      
      const recSkills = (recJob.skills && recJob.skills.length > 0)
        ? recJob.skills
        : (rec.matchedSkillsList || []).map((name: string) => ({ name }));

      if (recSkills && recSkills.length > 0) {
        recSkills.forEach((s: any) => {
          if (!s?.name) {
            return;
          }
          const sid = "skill_" + s.name;
          if (!nodes.find(n => n.id === sid)) {
             nodes.push({ id: sid, name: s.name, group: 2, type: "Skill (Required)" });
          }
          links.push({ source: jobId, target: sid, type: "REQUIRES" });
        });
      }
    });

    const simulation = d3.forceSimulation(nodes as any)
      .force("link", d3.forceLink(links).id((d: any) => d.id).distance(120))    
      .force("charge", d3.forceManyBody().strength(-300))
      .force("center", d3.forceCenter(width / 2, height / 2));

    const link = svg.append("g")
      .selectAll("line")
      .data(links)
      .join("line")
      .attr("stroke", "#999")
      .attr("stroke-opacity", 0.6)
      .attr("stroke-width", 2);

    link.append("title").text((d: any) => d.type);

    const tooltip = d3.select("body").append("div")
      .attr("class", "d3-tooltip")
      .style("position", "absolute")
      .style("visibility", "hidden")
      .style("background", "rgba(0,0,0,0.8)")
      .style("color", "#fff")
      .style("padding", "8px")
      .style("border-radius", "4px")
      .style("font-size", "12px")
      .style("z-index", "1000")
      .style("pointer-events", "none");

    const node = svg.append("g")
      .attr("stroke", "#fff")
      .attr("stroke-width", 1.5)
      .selectAll("circle")
      .data(nodes)
      .join("circle")
      .attr("r", (d: any) => d.group === 1 ? 20 : (d.group === 3 ? 15 : 10))
      .attr("fill", (d: any) => d.group === 1 ? "#0dcaf0" : (d.group === 2 ? "#198754" : "#fd7e14"))
      .on("mouseover", (event, d: any) => {
        let content = "<strong>" + d.name + "</strong><br/>Type: " + d.type;
        if (d.score !== undefined) content += "<br/>Match: " + Math.round(d.score) + "%";
        tooltip.html(content)
               .style("visibility", "visible");
      })
      .on("mousemove", (event) => {
        tooltip.style("top", (event.pageY - 10) + "px")
               .style("left", (event.pageX + 10) + "px");
      })
      .on("mouseout", () => {
        tooltip.style("visibility", "hidden");
      });

    simulation.on("tick", () => {
      link
        .attr("x1", (d: any) => d.source.x)
        .attr("y1", (d: any) => d.source.y)
        .attr("x2", (d: any) => d.target.x)
        .attr("y2", (d: any) => d.target.y);

      node
        .attr("cx", (d: any) => d.x = Math.max(20, Math.min(width - 20, d.x)))
        .attr("cy", (d: any) => d.y = Math.max(20, Math.min(height - 20, d.y)));
    });
  }
}
