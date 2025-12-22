RAG Learning
=============

A small RAG microservice for experimenting with retrieval-augmented generation.

This repository does not include automated CI/CD workflows.

Run locally:

- Build: `mvn -B package`
- Run: `java -jar target/rag-learning-1.0-SNAPSHOT.jar`
- If you use Docker Compose: `docker-compose up -d` (requires Docker installed)

If you later want CI/CD, add workflows under `.github/workflows/` to suit your needs.
