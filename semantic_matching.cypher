// Semantic Matching for one candidate using vector index
// Parameters:
// $candidateId: String
// $threshold: Double (ex: 0.8)
// $topKPerSkill: Integer (ex: 20)
// $topJobs: Integer (ex: 5)
// $embeddingDim: Integer (ex: 384)
MATCH (c:Candidate {id: $candidateId})-[:HAS_SKILL]->(candidateSkill:Skill)
WHERE candidateSkill.embedding IS NOT NULL
  AND size(candidateSkill.embedding) = $embeddingDim
CALL {
  WITH candidateSkill
  CALL db.index.vector.queryNodes('skill_embedding_index', $topKPerSkill, candidateSkill.embedding)
  YIELD node, score
  WHERE score >= $threshold
  RETURN candidateSkill AS candidateSkillRef, node AS requiredSkill, score
}
MATCH (j:Job)-[:REQUIRES]->(requiredSkill)
WITH j, requiredSkill, candidateSkillRef, score
ORDER BY score DESC
WITH j, requiredSkill, collect({candidateSkill: candidateSkillRef, score: score})[0] AS bestMatch
CALL {
  WITH j
  MATCH (j)-[:REQUIRES]->(allRequired:Skill)
  RETURN count(allRequired) AS totalRequiredSkills
}
WITH j, totalRequiredSkills,
     collect({
       candidateSkillName: bestMatch.candidateSkill.name,
       requiredSkillName: requiredSkill.name,
       similarityScore: round(bestMatch.score * 10000) / 10000.0
     }) AS explanations,
     avg(bestMatch.score) AS avgSimilarity,
     count(requiredSkill) AS semanticMatchedSkills
WHERE semanticMatchedSkills > 0
WITH j, explanations, semanticMatchedSkills, totalRequiredSkills,
     avgSimilarity,
     (toFloat(semanticMatchedSkills) / toFloat(totalRequiredSkills)) AS coverage
WITH j, explanations, semanticMatchedSkills, totalRequiredSkills,
     round((0.7 * avgSimilarity + 0.3 * coverage) * 10000) / 10000.0 AS semanticScore
RETURN {
  jobLink: coalesce(j.job_link, j.jobLink),
  title: coalesce(j.job_title, j.title, j.name),
  type: coalesce(j.type, j.job_type),
  level: coalesce(j.level, j.job_level)
} AS job,
semanticScore,
semanticMatchedSkills,
totalRequiredSkills,
explanations
ORDER BY semanticScore DESC, semanticMatchedSkills DESC
LIMIT $topJobs;
