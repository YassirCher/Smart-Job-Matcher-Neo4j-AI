// 1) Create Neo4j 5.x vector index for Skill embeddings (384 dimensions, cosine)
CREATE VECTOR INDEX skill_embedding_index IF NOT EXISTS
FOR (s:Skill)
ON (s.embedding)
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 384,
    `vector.similarity_function`: 'cosine'
  }
};

// 2) Example nearest-neighbor lookup (top 5)
// Parameter $queryEmbedding must be a Float[] of length 384
CALL db.index.vector.queryNodes('skill_embedding_index', 5, $queryEmbedding)
YIELD node, score
RETURN node.id AS skillId, node.name AS skillName, score
ORDER BY score DESC;
