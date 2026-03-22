// P0 migration - constraints and indexes for performance baseline

// ---------- Uniqueness constraints ----------
CREATE CONSTRAINT candidate_id_unique IF NOT EXISTS
FOR (c:Candidate) REQUIRE c.id IS UNIQUE;

CREATE CONSTRAINT skill_id_unique IF NOT EXISTS
FOR (s:Skill) REQUIRE s.id IS UNIQUE;

CREATE CONSTRAINT job_link_unique IF NOT EXISTS
FOR (j:Job) REQUIRE j.job_link IS UNIQUE;

// ---------- B-Tree indexes ----------
CREATE INDEX skill_name_idx IF NOT EXISTS
FOR (s:Skill) ON (s.name);

CREATE INDEX candidate_name_id_idx IF NOT EXISTS
FOR (c:Candidate) ON (c.name, c.id);

// ---------- Full-text indexes ----------
CREATE FULLTEXT INDEX skill_name_ft IF NOT EXISTS
FOR (s:Skill) ON EACH [s.name];

CREATE FULLTEXT INDEX job_title_level_ft IF NOT EXISTS
FOR (j:Job) ON EACH [j.job_title, j.level, j.type];

CREATE FULLTEXT INDEX company_name_ft IF NOT EXISTS
FOR (c:Company) ON EACH [c.name];

CREATE FULLTEXT INDEX location_name_ft IF NOT EXISTS
FOR (l:Location) ON EACH [l.name];

// ---------- Vector index ----------
CREATE VECTOR INDEX skill_embedding_index IF NOT EXISTS
FOR (s:Skill) ON (s.embedding)
OPTIONS {indexConfig: {`vector.dimensions`: 384, `vector.similarity_function`: 'cosine'}};
