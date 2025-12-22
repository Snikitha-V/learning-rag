RAG Learning — CI / Docker Hub Guide
====================================

This project includes a GitHub Actions workflow (`.github/workflows/docker-publish.yml`) that builds the JAR, builds a Docker image, and pushes it to Docker Hub.

Quick setup
-----------
1. Create a Docker Hub access token (recommended):
   - Log in to https://hub.docker.com
   - Go to Account Settings → Security → New Access Token
   - Give it a name (e.g., `github-actions`) and copy the token value.

2. Add GitHub repository secrets:
   - In the repo on GitHub, go to **Settings → Secrets → Actions**
   - Add `DOCKERHUB_USERNAME` with your Docker Hub username
   - Add `DOCKERHUB_TOKEN` with the token you created in Docker Hub

3. What the workflow does:
   - Trigger: push to `main` or a tagged release (`v*.*.*`) (also manual via workflow_dispatch)
   - Steps: build JAR, build Docker image, push image tags `latest` and commit SHA

Tips & Best Practices
---------------------
- Prefer **Docker Hub access tokens** over passwords. Rotate tokens periodically.
- Use Git tags (e.g., `git tag v1.0.0 && git push origin v1.0.0`) to produce release images.
- Add test steps (unit tests, lint) before the build step to prevent broken releases.
- For production: consider signing images and adding vulnerability scanning.

If you want, I can also configure the workflow to push to GitHub Container Registry (GHCR) instead of Docker Hub or add tagging strategies (branches, PR preview images, etc.).
