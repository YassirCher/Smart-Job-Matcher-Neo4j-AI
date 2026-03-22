# Documentation Technique Complete

Ce document est la reference technique exhaustive de l'application Smart Job Matcher (Neo4j + IA).

## 1. Vue d'ensemble

Smart Job Matcher est une plateforme de matching candidat/job orientee graphe qui combine:

- une modelisation relationnelle avancee sous Neo4j
- des recommandations lexicales et semantiques explicables
- des workflows IA pour la qualification des profils (CV, GitHub, entretien, soft skills)
- des modules analytics pour la gouvernance data et l'aide a la decision

Objectif produit: accelerer la decision RH tout en gardant une tracabilite explicable des signaux (skills, scores, preuves).

## 2. Architecture globale

### 2.1 Architecture logique

- Frontend SPA Angular 20 (standalone components)
- Backend Spring Boot 4 expose en API REST
- Base Neo4j pour le graphe metier + index full-text + index vectoriel
- LLM Groq pour extraction/generation, avec fallbacks applicatifs
- Embeddings locaux (runtime Java DJL + migration batch Python Sentence Transformers)

### 2.2 Style d'architecture backend

Organisation par couches:

1. Controllers: contrat HTTP, validation d'entrees, mapping API
2. Services: logique metier, orchestration IA, fallbacks, cache
3. Repositories + Neo4jClient: CRUD SDN + Cypher avance
4. Config: cache, properties, gouvernance skills

Choix cle: combiner Spring Data Neo4j pour les operations standards et Neo4jClient pour les queries analytiques complexes (vector search, projections, agregations).

### 2.3 Frontend

- un service facade unique (`Api`) pour centraliser tous les appels HTTP
- composants standalone par ecran (dashboard, candidats, jobs, recommandations, graphe)
- orchestration locale des etats: loading, erreurs, polling asynchrone
- D3.js pour rendu graphe interactif et mini-apercu

## 3. Modele de donnees graphe

### 3.1 Noeuds principaux

- Candidate: id, name, email, resumePath
- Job: job_link, job_title/title, type, level
- Skill: id, name, embedding (384 dimensions)
- Company: id, name
- Location: id, name
- SoftSkill: id, name

### 3.2 Relations principales

- (Candidate)-[:HAS_SKILL]->(Skill)
- (Job)-[:REQUIRES]->(Skill)
- (Company)-[:POSTED]->(Job)
- (Job)-[:LOCATED_IN]->(Location)
- (Candidate)-[:POSSESSES_SOFT_SKILL {confidence, evidence, source, updatedAtEpochMs}]->(SoftSkill)

### 3.3 Volumetrie

Le projet cible un graphe de production analytique:

- > 1 000 000 noeuds
- ~ 4 300 000 relations

Cette volumetrie justifie:

- index full-text pour la recherche metier
- index vectoriel pour les similarites semantiques
- cache applicatif et garde-fous de cardinalite

## 4. Pipeline Data Engineering

### 4.1 Preprocessing des datasets

Script principal: `fix data/process_neo4j_data.py`

Etapes:

1. echantillonnage controle de 200 000 jobs
2. filtrage des skills sur cet echantillon
3. explosion des skills multi-valeurs
4. normalisation syntaxique stricte des skills
5. traitement des summaries en chunks de 50k lignes

Sorties:

- `data/neo4j_jobs_200k.csv`
- `data/neo4j_skills_relations_200k.csv`
- `data/neo4j_summaries_200k.csv`

### 4.2 Migration embeddings batch

Script: `scripts/migrate_skill_embeddings.py`

Caracteristiques:

- modele: `sentence-transformers/all-MiniLM-L6-v2`
- dimension: 384
- embeddings normalises (cosine)
- traitement en batch (memoire bornee)
- reprise de progression via fichier de resume

Dependances: `scripts/requirements-embeddings.txt`

### 4.3 Embeddings runtime backend

Au runtime Java:

- resolution skill -> canonicalisation
- embedding local via `LocalTextEmbeddingService`
- MERGE du skill avec embedding dans Neo4j

Ainsi, les nouveaux skills restent alignes avec l'index vectoriel sans migration manuelle continue.

## 5. Gouvernance de qualite des skills

Composants cles:

- `SkillNameNormalizer`: normalisation syntaxique
- `SkillCanonicalizationService`: alias -> forme canonique
- `SkillResolutionService`: normalisation + canonicalisation + embedding

Objectif:

- eviter la creation de skills parasites
- dedupliquer les variantes lexicales
- maintenir une couverture vectorielle coherente

## 6. Moteurs de recommandation

### 6.1 Recommandation lexicale

Endpoint: `GET /api/recommendations/{candidateId}`

Principe:

- overlap direct entre skills candidat et skills requis job
- score interpretable base sur matchingSkills/totalSkills

### 6.2 Recommandation semantique

Endpoint: `GET /api/recommendations/{candidateId}/semantic`

Pipeline:

1. recuperer skills candidat avec embeddings valides
2. nearest neighbors via `db.index.vector.queryNodes('skill_embedding_index', ...)`
3. rattacher aux jobs qui requierent les skills voisins
4. agreger explications skill-source -> skill-cible

Score hybride:

semanticScore = 0.7 * avgSimilarity + 0.3 * coverage

Sortie:

- job cible
- score semantique
- nombre de skills semantiquement couvertes
- explications detaillees

### 6.3 Comparaison lexical vs semantique

`AdvancedAnalyticsService#getRecommendationComparison` produit une vue delta pour expliquer les gains semantiques par rapport au matching strict.

## 7. Analytics graphe et gouvernance

### 7.1 Dashboard global

Endpoints:

- `/api/stats`
- `/api/admin/data-quality`
- `/api/analytics/data-funnel`
- `/api/analytics/data-drift`

### 7.2 Data Health

`DataQualityAuditService` calcule:

- ratio de doublons skills
- score qualite
- statut HEALTHY/WARNING/CRITICAL

### 7.3 Data Funnel

`AdvancedAnalyticsService#getDataFunnel` mesure la conversion volume brut -> signal utile:

- total skill nodes
- deduplicated skill nodes
- market/candidate/shared coverage

### 7.4 Drift Proxy

`getDataDriftProxy` derive un risque de derive sans historique complet:

- concentration top-10 skills
- entropie distributions niveau/type
- classification LOW/MEDIUM/HIGH

### 7.5 Graph centrality et communities

`GraphAnalyticsService` expose:

- centralite des skills (impact marche)
- communautes de co-occurrence
- extraction de voisins les plus structurants

## 8. Parcours de carriere et strategie skill-gap

### 8.1 Skill-Gap Roadmap

Identifie les skills manquantes avec fort impact debloquant (jobs potentiels + score d'impact).

### 8.2 Counterfactual

Simule les skills a ajouter pour maximiser les jobs debloquables sous contrainte de gaps.

### 8.3 Career Path Predictor

`CareerPathPredictorService` calcule un score composite base sur:

- cooccurrence support
- unlocked jobs
- compensation/seniority lift

Forme generale du score:

score = 0.45*log(1+cooccurrenceSupport) + 0.35*log(1+unlockedJobs) + 0.20*log(1+compensationLift)

### 8.4 Coaching IA

Le service de coaching produit un message actionnable pour le candidat (LLM Groq + fallback local).

### 8.5 Career paths multi-hop

`GraphAnalyticsService#getCareerPaths` propose des transitions skill source -> bridge skill -> target job avec fallback topologique si le vectoriel est indisponible.

## 9. Resume & Portfolio Intelligence (CV + GitHub)

Endpoint pivot:

- `POST /api/candidates/{id}/resume-intelligence/upload`

Pipeline:

1. validation fichier (type + taille)
2. extraction texte Apache Tika
3. compression NLP
4. extraction skills CV (Groq/fallback)
5. detection GitHub (URL/handle)
6. delta CV vs GitHub:
   - validated
   - claimed but unverified
   - hidden gems
7. validation humaine avant application en base

## 10. GitHub Analyzer (human in the loop)

### 10.1 Analyse

`POST /api/candidates/{id}/github/analyze`

- ingestion README/repos avec budgets
- preprocessing NLP README
- extraction skills
- reconciliation (exact + vector + seuils confiance)
- stockage temporaire d'un `analysisId`

### 10.2 Application

`POST /api/candidates/{id}/github/apply`

- verifie appartenance candidat + TTL
- attache les skills validees uniquement

## 11. Dynamic Interview Generator

Endpoints:

- start: `POST /api/candidates/{id}/resume-intelligence/interview-script/start`
- poll: `GET /api/candidates/{id}/resume-intelligence/interview-script/{jobId}`

Caracteristiques:

- job asynchrone en memoire
- statut queued -> in_progress -> completed
- garde-fou TPM (fenetre glissante 60s)
- fallback template senior si indisponibilite LLM

## 12. Profil comportemental (soft skills)

Endpoints:

- start: `POST /api/candidates/{id}/behavioral-profile/start`
- poll: `GET /api/candidates/{id}/behavioral-profile/{jobId}`
- read: `GET /api/candidates/{id}/behavioral-profile`

Implementation:

- inference soft skills via corpus CV + GitHub
- fallback heuristique
- persistance relation explicable `POSSESSES_SOFT_SKILL`

## 13. Recherche metier et UX de saisie

### 13.1 Full-text search

Recherche skills/jobs/companies/locations via index full-text Neo4j + normalisation de requete.

### 13.2 Smart Job Creator

Parsing IA de description brute avec:

- contrat JSON strict
- inference dynamique des champs autorises
- validation d'evidence dans le texte source
- mapping vers formulaire editable (humain garde la main)

## 14. Caching, performance et resilence

### 14.1 Cache

Caffeine sur recommandations et endpoints analytics pour reduire latence et charge Neo4j.

### 14.2 Invalidations

Evictions larges apres mutations metier (candidat/job/skill) pour conserver la coherence fonctionnelle.

### 14.3 Resilience

- soft-fail API sur certaines briques vector/LLM
- clamps de parametres pour limiter cardinalites
- index vectoriel auto-verifie et normalisation embeddings

## 15. Contrat API (high-level)

Principales familles d'endpoints:

- `/api/candidates` : CRUD + enrichissements IA
- `/api/jobs` : CRUD + smart parse
- `/api/recommendations` : lexical + semantic
- `/api/graph-analytics` : centrality, communities, career paths
- `/api/analytics` : funnel, drift, quality, roadmap, counterfactual, predictor
- `/api/skills` : search, resolve, semantic nearest
- `/api/stats` et `/api/admin/data-quality`

## 16. Configuration et variables d'environnement

Parametres critiques:

- `NEO4J_USERNAME`
- `NEO4J_PASSWORD`
- `GROQ_API_KEY`
- `GITHUB_TOKEN`

Fichier principal: `backend/src/main/resources/application.properties`

Bonnes pratiques:

- aucune cle API en dur
- utiliser `backend/local.properties` non partage pour le dev local
- verifier `.gitignore` avant commit

## 17. Securite et publication GitHub

Preconisations minimales:

1. rotation immediate de toute cle precedemment exposee
2. scan local des secrets avant push
3. verification des fichiers tracked (`git status`, `git diff --cached --name-only`)
4. protection branche + secret scanning active cote GitHub

## 18. Limites actuelles et axes d'evolution

Limites connues:

- securite API encore perfectible (auth/authz/rate-limit global)
- jobs asynchrones en memoire (non persistants)
- invalidations cache parfois larges

Roadmap logique:

- durcissement security-first
- persistance des jobs async
- optimisations Cypher supplementaires (EXPLAIN/PROFILE automatises)
- event-driven pipelines pour enrichissements IA lourds

## 19. Conclusion

L'application fournit un socle professionnel de Talent Intelligence base sur Neo4j et IA explicable:

- operationnel sur forte volumetrie graphe
- oriente decision RH
- extensible vers une architecture encore plus scalable

Ce document peut servir de base technique pour soutenance, onboarding, ou publication open-source.

## annexe-visuelle-complete-screenshots

Cette annexe reference l'ensemble des captures du dossier `screenshots` pour couvrir 100% des ecrans fonctionnels.

### Dashboard et gouvernance

![dashboard_main_page](<screenshots/dashboard_main_page.png>)
![coverage funnel](<screenshots/coverage funnel.png>)
![data drift proxy](<screenshots/data drift proxy.png>)
![jobs par niveau et par type](<screenshots/jobs par niveau et par type .png>)
![top 10 competences](<screenshots/top 10 competences.png>)
![graph centrality et skill communities](<screenshots/graph centrality et skill communities.png>)
![scemantic analyser](<screenshots/scemantic analyser .png>)
![scemantic analyser result](<screenshots/scemantic analyser result.png>)

### Candidats et profil

![liste candidats](<screenshots/liste candidats.png>)
![creer un candidat](<screenshots/creer un candidat.png>)
![infos et analyse et statistique candidat](<screenshots/infos et analyse et statistique candidat.png>)
![profil Resume Portfolio Intelligence](<screenshots/profil Resume & Portfolio Intelligence.png>)
![Profil Comportemental](<screenshots/Profil Comportemental.png>)
![Dynamic Interview Generator](<screenshots/Dynamic Interview Generator.png>)
![Recommandations Elargies](<screenshots/Recommandations Elargies (IA).png>)
![Skill-Gap Roadmap](<screenshots/Skill-Gap Roadmap (Impact Marche).png>)
![Career Path Predictor](<screenshots/Career Path Predictor (Coaching IA).png>)
![career paths multi hop](<screenshots/career paths multi hop.png>)
![simulation utilisateur et recommendation et graphe](<screenshots/simulation utilisateur et recommendation et graphe.png>)

### Jobs et smart creation

![liste jobs](<screenshots/liste jobs.png>)
![liste jobs par filtre](<screenshots/liste jobs par filtre.png>)
![creer un job prompt resultat1](<screenshots/creer un job a partir dun prompt resultat1.png>)
![creer un job prompt resultat2](<screenshots/creer un job a partir dun prompt resultat2.png>)

### Visualisation graphe

![graphe utilisateur skills jobs recommende](<screenshots/graphe utilisater et ses skills et jobs recommende.png>)
