FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libopus0 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY agent/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY common /app/common
COPY marketplace /app/marketplace
COPY agent /app/agent
ENV PYTHONPATH=/app/common:/app PYTHONUNBUFFERED=1
CMD ["python", "-m", "agent.worker"]
