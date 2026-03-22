# Talent Intelligence Hub

Plateforme de matching candidat/job orientee graphe + IA : recommandations lexicales et semantiques, gouvernance data, analytics Neo4j, et workflow RH assiste (CV/GitHub/entretien).

## Stack technique

![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-6DB33F?logo=springboot&logoColor=white)
![Neo4j](https://img.shields.io/badge/Neo4j-Graph%20DB-4581C3?logo=neo4j&logoColor=white)
![Angular](https://img.shields.io/badge/Angular-20-DD0031?logo=angular&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript&logoColor=white)
![D3.js](https://img.shields.io/badge/D3.js-7-F9A03C?logo=d3.js&logoColor=white)
![Groq](https://img.shields.io/badge/Groq-LLM-111111)
![DJL](https://img.shields.io/badge/DJL-Embeddings-0A7EA4)
![HuggingFace](https://img.shields.io/badge/Hugging%20Face-Transformers-FFD21E?logo=huggingface&logoColor=black)
![PyTorch](https://img.shields.io/badge/PyTorch-Engine-EE4C2C?logo=pytorch&logoColor=white)
![Apache Tika](https://img.shields.io/badge/Apache%20Tika-2.9.2-D22128)
![Bootstrap](https://img.shields.io/badge/Bootstrap-5.3-7952B3?logo=bootstrap&logoColor=white)

## Architecture rapide

- Backend: API REST Spring Boot + Spring Data Neo4j + Neo4jClient pour les requetes Cypher analytiques.
- Frontend: Angular standalone components + RxJS + D3.js pour visualisation graphe.
- IA/ML: Groq (extraction/generation), embeddings locaux DJL (384 dimensions), recherche vectorielle Neo4j.
- Gouvernance: normalisation/canonicalisation des skills, quality audit, data funnel et drift proxy.

## Schema de donnees graphe et volumetrie

Le graphe metier tourne a grande echelle avec:

- plus de 1 000 000 de noeuds
- environ 4 300 000 relations

Schema logique principal (rendu graphe GitHub Mermaid):

```mermaid
flowchart LR
    C[Candidate]
    S[Skill]
    J[Job]
    CO[Company]
    L[Location]
    SS[SoftSkill]

    C -- HAS_SKILL --> S
    J -- REQUIRES --> S
    CO -- POSTED --> J
    J -- LOCATED_IN --> L
    C -- POSSESSES_SOFT_SKILL --> SS
```

Proprietes principales:

- Candidate: `id`, `name`, `email`, `resumePath`
- Skill: `id`, `name`, `embedding[384]`
- Job: `job_link`, `title`, `type`, `level`
- SoftSkill: `id`, `name`
- POSSESSES_SOFT_SKILL: `confidence`, `evidence`, `source`, `updatedAtEpochMs`

Relations cles exploitees par les modules IA/analytics:

- matching lexical: overlap `HAS_SKILL` vs `REQUIRES`
- matching semantique: voisinage vectoriel `Skill.embedding`
- career path: transitions multi-hop skills -> jobs
- behavioral AI: relation explicable `POSSESSES_SOFT_SKILL`

[👉 Documentation technique complete de l'architecture et de tous les modules](details_fonctionnalites.md#documentation-technique-complete)

## Preprocessing data et transformation embeddings

### Pipeline de preprocessing (datasets bruts -> fichiers Neo4j)

Le script [fix data/process_neo4j_data.py](fix%20data/process_neo4j_data.py) prepare les donnees avant import:

1. Echantillonnage controle de 200 000 jobs depuis `linkedin_job_postings.csv`.
2. Filtrage des skills uniquement sur ce sous-ensemble de jobs.
3. Explosion `job_skills` (1 ligne = 1 skill) puis nettoyage.
4. Normalisation stricte des skills (minuscule + suppression caracteres non alphanumeriques) pour reduire les doublons.
5. Traitement des summaries en chunks de 50 000 lignes pour limiter la RAM.

Sorties generees dans [data](data):

- `neo4j_jobs_200k.csv`
- `neo4j_skills_relations_200k.csv`
- `neo4j_summaries_200k.csv`

Execution:

```bash
python "fix data/process_neo4j_data.py"
```

### Modele d'embeddings utilise

Le projet utilise `sentence-transformers/all-MiniLM-L6-v2` avec vecteurs 384 dimensions et normalisation cosine:

- runtime backend: config `skills.embedding.model-id` + `skills.embedding.dimension=384` dans [backend/src/main/resources/application.properties](backend/src/main/resources/application.properties)
- migration batch massive: script [scripts/migrate_skill_embeddings.py](scripts/migrate_skill_embeddings.py)

Dependances batch embeddings: [scripts/requirements-embeddings.txt](scripts/requirements-embeddings.txt)

```bash
pip install -r scripts/requirements-embeddings.txt
python scripts/migrate_skill_embeddings.py --password "$NEO4J_PASSWORD"
```

### Infos techniques necessaires pour reproduire

- Neo4j actif sur `bolt://127.0.0.1:7687`
- variables d'environnement recommandees:
	- `NEO4J_USERNAME`
	- `NEO4J_PASSWORD`
	- `GROQ_API_KEY`
	- `GITHUB_TOKEN`
- index vectoriel Neo4j: `skill_embedding_index` (cosine, 384D), gere automatiquement par le backend
- gouvernance skills: normalisation + canonicalisation alias avant ecriture Neo4j

## Fonctionnalites majeures

### 1) Dashboard global et sante des donnees
![Dashboard](<screenshots/dashboard_main_page.png>)
Le dashboard centralise les KPIs globaux (jobs, skills, candidats) et l'etat de sante de la base. Il combine monitoring produit et lecture metier en une vue unique.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#dashboard-et-observabilite)

### 2) Coverage Funnel (Big Data Governance)
![Coverage Funnel](<screenshots/coverage funnel.png>)
Le funnel montre le passage du volume brut de skills au signal utile partage entre marche et candidats. Il met en evidence la couverture effective des competences.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#coverage-funnel-et-gouvernance)

### 3) Data Drift Proxy
![Data Drift Proxy](<screenshots/data drift proxy.png>)
Le drift est estime via concentration des top skills et entropie des distributions niveau/type. Cela permet un signal risque meme sans historique temporel complet.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#data-drift-proxy)

### 4) Graph Centrality et Skill Communities
![Graph Centrality et Communities](<screenshots/graph centrality et skill communities.png>)
Les skills leviers et communautes de co-occurrence sont extraits du graphe Job-Skill. Cette vue identifie les competences pivot du marche.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#graph-analytics-centralite-et-communautes)

### 5) Gestion des candidats
![Liste candidats](<screenshots/liste candidats.png>)
La liste candidats expose score de qualite/signal et actions rapides (profil, recommandations, graphe). La creation supporte recherche dynamique de competences.

![Creation candidat](<screenshots/creer un candidat.png>)

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#gestion-des-candidats-et-skills)

### 6) Resume & Portfolio Intelligence (CV + GitHub)
![Resume Portfolio Intelligence](<screenshots/profil Resume & Portfolio Intelligence.png>)
Le module extrait les skills du CV, detecte GitHub, calcule un delta (validated/claimed/hidden gems), puis permet une validation humaine avant ecriture Neo4j.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#resume-et-portfolio-intelligence)

### 7) Profil comportemental (soft skills)
![Profil comportemental](<screenshots/Profil Comportemental.png>)
Les soft skills sont inferees a partir de signaux CV/GitHub avec preuves explicables et score de confiance, puis ajoutables au profil candidat.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#profil-comportemental-soft-skills)

### 8) Dynamic Interview Generator
![Dynamic Interview Generator](<screenshots/Dynamic Interview Generator.png>)
Generation asynchrone de questions d'entretien senior ciblees sur les zones "Claimed but Unverified", avec fallback intelligent en cas d'indisponibilite upstream.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#dynamic-interview-generator)

### 9) Recommandations elargies (semantic matching)
![Recommandations Elargies](<screenshots/Recommandations Elargies (IA).png>)
Le matching vectoriel propose des jobs pertinents avec score semantique et explication skill-a-skill pour une meilleure interpretabilite cote recruteur.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#recommandations-semantiques-et-explicabilite)

### 10) Graphe candidat-competences-jobs
![Graphe recommandations](<screenshots/graphe utilisater et ses skills et jobs recommende.png>)
Visualisation interactive D3 du voisinage candidat/skills/jobs afin d'explorer rapidement les connexions et opportunites.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#visualisation-graphe-d3)

### 11) Skill Semantic Analyzer
![Semantic Analyzer](<screenshots/scemantic analyser .png>)
Recherche guidée de skill source avec suggestions dynamiques. L'ecran de resultat affiche les voisins semantiques et leur score de similarite.

![Semantic Analyzer Resultat](<screenshots/scemantic analyser result.png>)

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#skill-semantic-analyzer)

### 12) Gestion des jobs et filtrage
![Liste jobs](<screenshots/liste jobs.png>)
La gestion des offres couvre listing, edition, suppression et filtrage multi-criteres (titre, niveau, competence), avec support de recherche full-text.

![Liste jobs filtre](<screenshots/liste jobs par filtre.png>)

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#gestion-des-jobs-et-recherche)

### 13) Smart Job Creator (Magic Fill)
![Smart Job Creator - Etape 1](<screenshots/creer un job a partir dun prompt resultat1.png>)
L'utilisateur colle une description brute. L'IA propose un pre-remplissage structure et explique chaque champ via des evidences textuelles.

![Smart Job Creator - Etape 2](<screenshots/creer un job a partir dun prompt resultat2.png>)

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#smart-job-creator-magic-fill)

### 14) Skill-Gap Roadmap, Career Predictor et parcours multi-hop
![Skill Gap Roadmap](<screenshots/Skill-Gap Roadmap (Impact Marche).png>)
La roadmap priorise les competences a ajouter selon l'impact marche. Le predictor ajoute un coaching narratif, et les parcours multi-hop proposent des trajectoires vers des jobs cibles.

![Career Path Predictor](<screenshots/Career Path Predictor (Coaching IA).png>)
![Career Paths Multi-hop](<screenshots/career paths multi hop.png>)

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#roadmap-skill-gap-et-career-path-predictor)

### 15) Captures complementaires (vue complete de l'application)
![Infos et analyse candidat](<screenshots/infos et analyse et statistique candidat.png>)
Vue profil candidat avec synthese des informations metier et statistiques graphe personnalisees.

![Jobs par niveau et type](<screenshots/jobs par niveau et par type .png>)
Detail visuel des distributions de marche utilisees dans la partie gouvernance et pilotage RH.

![Top 10 competences](<screenshots/top 10 competences.png>)
Classement des competences les plus demandees, exploite pour les analyses d'impact et de priorisation.

![Simulation utilisateur recommandations graphe](<screenshots/simulation utilisateur et recommendation et graphe.png>)
Mode simulation en contexte candidat: recommandations, compatibilite et apercu graphe unifie.

[👉 Voir les details techniques de cette fonctionnalite](details_fonctionnalites.md#annexe-visuelle-complete-screenshots)

## Lancement local

### Backend (Spring Boot)
```bash
cd backend
./mvnw spring-boot:run
```

### Frontend (Angular)
```bash
cd frontend-job-recommender
npm install
npm start
```

Frontend: http://localhost:4200  
Backend API: http://localhost:8080/api

## Footer

« Ce projet est un POC (Proof of Concept) réalisé dans le cadre du projet du module Big Data. »
Auteur : Chergui Yassir
