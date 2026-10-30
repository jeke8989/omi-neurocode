FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libopus0 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY gateway/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY common /app/common
COPY marketplace /app/marketplace
COPY gateway /app/gateway
ENV PYTHONPATH=/app/common:/app PYTHONUNBUFFERED=1
CMD ["uvicorn", "gateway.app:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--ws-max-size", "4194304"]
