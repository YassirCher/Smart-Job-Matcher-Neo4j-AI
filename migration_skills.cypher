// migration_skills.cypher
// Objective: deduplicate Skill nodes by syntax and common acronym/synonym mappings
// Requirement: APOC plugin installed and enabled

// ============================================================
// 0) Pre-checks (read-only)
// ============================================================
MATCH (s:Skill) RETURN count(s) AS totalSkills;

MATCH (s:Skill)
WITH toLower(trim(s.name)) AS k, count(*) AS c
WHERE c > 1
RETURN count(*) AS duplicateKeysByTrimLower;

// ============================================================
// 1) Syntax normalization merge
//    Rule: lower-case + trim + collapse spaces + remove bullet prefix (add APOC via Plugins)
// ============================================================
CALL apoc.periodic.iterate(
  "
  MATCH (s:Skill)
  WITH s,
       apoc.text.regreplace(
         apoc.text.regreplace(toLower(trim(coalesce(s.name,''))), '^[*\\-•]+\\s*', ''),
         '\\s+', ' '
       ) AS canonical
  WHERE canonical <> ''
  RETURN canonical, collect(s) AS nodes
  ",
  "
  WITH canonical, nodes
  WHERE size(nodes) > 1
  CALL apoc.refactor.mergeNodes(nodes, {
      properties: 'discard',
      mergeRels: true,
      produceSelfRel: false,
      singleElementAsArray: false
  }) YIELD node
  SET node.name = canonical,
      node.id = coalesce(node.id, randomUUID())
  RETURN count(*)
  ",
  {batchSize: 50, parallel: false, retries: 1}
);

// Ensure all remaining singleton nodes also use normalized name format
MATCH (s:Skill)
WITH s,
     apoc.text.regreplace(
       apoc.text.regreplace(toLower(trim(coalesce(s.name,''))), '^[*\\-•]+\\s*', ''),
       '\\s+', ' '
     ) AS canonical
WHERE canonical <> ''
SET s.name = canonical,
    s.id = coalesce(s.id, randomUUID());

// ============================================================
// 2) Semantic normalization (acronyms/synonyms)
//    Extend the dictionary below as needed
// ============================================================
WITH [
  {alias: 'ml', canonical: 'machine learning'},
  {alias: 'machine-learning', canonical: 'machine learning'},
  {alias: 'ai', canonical: 'artificial intelligence'},
  {alias: 'js', canonical: 'javascript'},
  {alias: 'ts', canonical: 'typescript'},
  {alias: 'nlp', canonical: 'natural language processing'},
  {alias: 'dl', canonical: 'deep learning'},
  {alias: 'sql server', canonical: 'microsoft sql server'},
  {alias: 'node', canonical: 'node.js'},
  {alias: 'py', canonical: 'python'}
] AS synonymMap
UNWIND synonymMap AS row
CALL {
  WITH row
  MATCH (s:Skill)
  WITH row, s,
       apoc.text.regreplace(
         apoc.text.regreplace(toLower(trim(coalesce(s.name,''))), '^[*\\-•]+\\s*', ''),
         '\\s+', ' '
       ) AS normalized
  WHERE normalized = row.alias OR normalized = row.canonical
  RETURN row.canonical AS canonical, collect(s) AS nodes
}
WITH canonical, nodes
WHERE size(nodes) > 1
CALL apoc.refactor.mergeNodes(nodes, {
    properties: 'discard',
    mergeRels: true,
    produceSelfRel: false,
    singleElementAsArray: false
}) YIELD node
SET node.name = canonical,
    node.id = coalesce(node.id, randomUUID())
RETURN count(*) AS synonymMergeGroups;

// ============================================================
// 3) Post-checks (read-only)
// ============================================================
MATCH (s:Skill)
RETURN count(s) AS totalSkillsAfter;

MATCH (s:Skill)
WITH toLower(trim(s.name)) AS k, count(*) AS c
WHERE c > 1
RETURN k AS duplicateKeyRemaining, c
ORDER BY c DESC
LIMIT 20;

MATCH (:Candidate)-[r:HAS_SKILL]->(:Skill)
RETURN count(r) AS candidateSkillRelationsAfter;

MATCH (:Job)-[r:REQUIRES]->(:Skill)
RETURN count(r) AS jobSkillRelationsAfter;
